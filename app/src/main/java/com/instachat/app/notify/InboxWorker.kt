package com.instachat.app.notify

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
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
        val session = Session.fromCookieHeader(cookies) ?: return Result.success()
        val state = NotifierState(applicationContext)

        return when (val fetched = InboxClient().fetch(session, userAgent)) {
            is FetchResult.Transient -> {
                Log.w(TAG, "Inbox check failed (attempt $runAttemptCount): ${fetched.reason}")
                // A retry is only worth it for a short blip. Beyond that the next periodic
                // run is as good, and hammering a rate limit only extends it.
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
            }

            is FetchResult.Done -> {
                handle(fetched.inbox, state)
                Result.success()
            }
        }
    }

    private fun handle(
        inbox: InboxResult,
        state: NotifierState,
    ) {
        when (inbox) {
            InboxResult.LoggedOut -> {
                if (!state.loggedOutNotified) {
                    notifyLoggedOut(applicationContext)
                    state.loggedOutNotified = true
                }
            }

            // Logged, not surfaced: most likely Instagram changed the response, and there
            // is nothing the user could do about it from a notification.
            InboxResult.Unreadable -> {
                Log.w(TAG, "Inbox response was not readable")
            }

            is InboxResult.Parsed -> {
                state.loggedOutNotified = false
                val watermark = state.watermarkMicros
                // The first check after install or login only seeds the watermark. The user
                // has just been looking at the inbox; announcing every chat they left unread
                // over the past months would be the opposite of useful.
                if (watermark != null && !AppVisibility.inForeground) {
                    notifyThreads(applicationContext, unreadSince(inbox, watermark))
                }
                state.watermarkMicros = nextWatermark(inbox, watermark ?: 0L)
            }
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
         * KEEP, not UPDATE: replacing the request on each start would reset its period, so
         * an app opened often would never sit long enough for the work to come due.
         */
        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<InboxWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
