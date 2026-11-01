package com.instachat.app.notify

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.instachat.app.web.UrlPolicy
import com.instachat.app.web.chromeLikeUserAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The periodic inbox check. This is the whole of the notification feature: a WebView cannot
 * receive Instagram's web push, and there is no server, so the app asks the inbox endpoint
 * itself, with the WebView's own session, and posts a local notification for anything new.
 * The question it asks is the one the site asks to draw its thread list; see InboxClient.
 *
 * Android decides when this actually runs. Fifteen minutes is WorkManager's floor, and Doze
 * stretches it further, so a message can arrive well after it was sent. That is the
 * accepted cost of not running a server.
 */
class InboxWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Both read on the main thread: CookieManager and the default-UA lookup initialise
        // the WebView engine on first use, which is only guaranteed safe there.
        val (cookies, userAgent) =
            withContext(Dispatchers.Main) {
                CookieManager.getInstance().getCookie(UrlPolicy.INBOX_URL) to
                    chromeLikeUserAgent(WebSettings.getDefaultUserAgent(applicationContext))
            }

        // Not logged in yet (or logged out in the app): nothing to check, and nothing to
        // say - the user can see the login page.
        val session = Session.fromCookieHeader(cookies)
        if (session == null) {
            Log.i(TAG, "No session cookie, skipping (cookies present: ${cookies != null})")
            return Result.success()
        }
        val state = NotifierState(applicationContext)
        checkExemption(state)

        // The job does not require a network (see [schedule]), so this is where a run
        // without one ends. getActiveNetwork() is also null while the app is blocked from
        // the network, as Power saving mode does to non-exempt apps.
        val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java)
        if (connectivity.activeNetwork == null) {
            Log.i(
                TAG,
                "No usable network, skipping (exempt: ${isBatteryExempt(applicationContext)})",
            )
            return Result.success()
        }

        val captured = state.inboxQuery
        val fetched =
            InboxClient().fetch(
                session = session,
                userAgent = userAgent,
                query = captured ?: InboxQuery.fallback(),
                cachedTokens = state.tokens,
            )
        val source = if (captured != null) "captured" else "fallback"
        return when (fetched) {
            is FetchResult.Transient -> {
                Log.w(TAG, "Inbox check failed (attempt $runAttemptCount): ${fetched.reason}")
                // A retry is only worth it for a short blip. Beyond that the next periodic
                // run is as good, and hammering a rate limit only extends it.
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
            }

            is FetchResult.Done -> {
                fetched.tokens?.let { state.tokens = it }
                handle(fetched.inbox, state, source)
                Result.success()
            }
        }
    }

    private fun handle(
        inbox: InboxResult,
        state: NotifierState,
        querySource: String,
    ) {
        when (inbox) {
            InboxResult.LoggedOut -> {
                if (!state.loggedOutNotified) {
                    notifyLoggedOut(applicationContext)
                    state.loggedOutNotified = true
                }
            }

            // Surfaced, once. A stale query or a format change otherwise looks exactly like
            // a quiet inbox, which is how the retired endpoint went unnoticed for days.
            is InboxResult.Unreadable -> {
                Log.w(TAG, "Inbox not readable (query: $querySource): ${inbox.reason}")
                if (!state.brokenNotified) {
                    notifyBroken(applicationContext)
                    state.brokenNotified = true
                }
            }

            is InboxResult.Parsed -> {
                state.loggedOutNotified = false
                if (state.brokenNotified) {
                    cancelBroken(applicationContext)
                    state.brokenNotified = false
                }
                val watermark = state.watermarkMicros
                val unread = if (watermark == null) emptyList() else unreadSince(inbox, watermark)
                val foreground = AppVisibility.inForeground
                // The first check after install or login only seeds the watermark. The user
                // has just been looking at the inbox; announcing every chat they left unread
                // over the past months would be the opposite of useful.
                if (!foreground) notifyThreads(applicationContext, unread)
                val next = nextWatermark(inbox, watermark ?: 0L)
                state.watermarkMicros = next
                // Counts and timestamps only, never names or text: enough to tell from
                // `adb logcat -s InboxWorker` which step dropped a message.
                Log.i(
                    TAG,
                    "Checked (query: $querySource): threads=${inbox.threads.size} " +
                        "unread=${unread.size} foreground=$foreground " +
                        "watermark=$watermark->$next " +
                        "newSinceWatermark=[${describeNew(inbox, watermark)}]",
                )
            }
        }
    }

    /** Warn once when the battery exemption has gone, and re-arm once it is back. */
    private fun checkExemption(state: NotifierState) {
        if (isBatteryExempt(applicationContext)) {
            state.restrictedNotified = false
            cancelRestricted(applicationContext)
        } else if (!state.restrictedNotified) {
            Log.i(TAG, "Not battery-exempt: checks stop whenever Power saving mode is on")
            notifyRestricted(applicationContext)
            state.restrictedNotified = true
        }
    }

    /**
     * For each thread with a message newer than [watermark], the one fact that decides
     * whether it is notified: sent by the viewer, already read, muted, or unread.
     */
    private fun describeNew(
        inbox: InboxResult.Parsed,
        watermark: Long?,
    ): String =
        inbox.threads
            .filter { watermark != null && (it.last?.timestampMicros ?: 0L) > watermark }
            .joinToString(",") { thread ->
                val last = thread.last ?: return@joinToString "?"
                val seen = thread.viewerSeenMicros
                when {
                    last.fromViewer -> "own"
                    seen != null && last.timestampMicros <= seen -> "read"
                    thread.muted -> "muted"
                    else -> "unread"
                }
            }

    companion object {
        private const val TAG = "InboxWorker"

        const val WORK_NAME = "instachat-inbox-check"

        /** WorkManager's minimum. Anything less is silently rounded up to it. */
        private const val INTERVAL_MINUTES = 15L

        private const val MAX_ATTEMPTS = 2

        /**
         * Register the periodic check. Safe to call on every launch.
         *
         * No network constraint, deliberately. With one, a check cut off by Power saving
         * mode never runs at all, and so can never say why notifications stopped; without
         * one it runs, posts the warning, and returns at the network check in [doWork].
         *
         * UPDATE, not REPLACE: UPDATE keeps the existing schedule, so an app opened often
         * still lets the work come due, while installs from before this change pick up the
         * dropped constraint. REPLACE would restart the period on every launch.
         */
        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<InboxWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                    .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
