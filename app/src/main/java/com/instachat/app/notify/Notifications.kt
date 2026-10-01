package com.instachat.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.instachat.app.MainActivity
import com.instachat.app.R

const val MESSAGES_CHANNEL_ID = "messages"
const val ACCOUNT_CHANNEL_ID = "account"

/**
 * Messages are posted under the thread id as the tag and this one id, so a second message
 * in the same thread replaces the first rather than stacking, and each thread can be
 * cancelled on its own.
 */
private const val MESSAGE_ID = 1
private const val SUMMARY_ID = 2
private const val LOGGED_OUT_ID = 3
private const val RESTRICTED_ID = 4

private const val GROUP_KEY = "com.instachat.app.MESSAGES"

/**
 * At most this many threads get a notification from one check. Past that the shade is
 * a list, and the app is one tap away.
 */
const val MAX_THREADS_NOTIFIED = 5

fun ensureChannels(context: Context) {
    val manager = NotificationManagerCompat.from(context)
    manager.createNotificationChannel(
        NotificationChannel(
            MESSAGES_CHANNEL_ID,
            "Messages",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description =
                "New direct messages. Checked about every 15 minutes, so they arrive late."
        },
    )
    manager.createNotificationChannel(
        NotificationChannel(
            ACCOUNT_CHANNEL_ID,
            "Account",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Tells you when something will stop message notifications."
        },
    )
}

/**
 * True when there is a runtime permission to ask the user for at all.
 *
 * Annotated so Lint can follow the version check through the call.
 */
@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
fun notificationPermissionIsRuntime(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/** The URL a notification for [threadId] opens. */
fun threadUrl(threadId: String): String = "https://www.instagram.com/direct/t/$threadId/"

/**
 * Post one notification per thread, newest first, plus a group summary when there are
 * several so the system can bundle them.
 */
fun notifyThreads(
    context: Context,
    threads: List<UnreadThread>,
) {
    if (threads.isEmpty()) return

    // Spelled out here rather than delegated to a helper: Lint verifies the guard only when
    // it can see it in the same function as the notify() call.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }

    val manager = NotificationManagerCompat.from(context)
    val shown = threads.take(MAX_THREADS_NOTIFIED)

    for (thread in shown) {
        val notification =
            NotificationCompat
                .Builder(context, MESSAGES_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(thread.title)
                .setContentText(thread.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(thread.text))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                // Instagram's timestamps are microseconds; the shade wants milliseconds.
                .setWhen(thread.timestampMicros / MICROS_PER_MILLI)
                .setShowWhen(true)
                .setGroup(GROUP_KEY)
                .setAutoCancel(true)
                .setContentIntent(openIntent(context, threadUrl(thread.threadId)))
                .build()
        manager.notify(thread.threadId, MESSAGE_ID, notification)
    }

    if (shown.size > 1) {
        val summary =
            NotificationCompat
                .Builder(context, MESSAGES_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("${shown.size} new chats")
                .setGroup(GROUP_KEY)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setContentIntent(openIntent(context, null))
                .build()
        manager.notify(SUMMARY_ID, summary)
    }
}

/** Say once that the session is gone, so notifications stopping is not a mystery. */
fun notifyLoggedOut(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    val notification =
        NotificationCompat
            .Builder(context, ACCOUNT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Logged out of Instagram")
            .setContentText("Open Insta Chat and log in again to get message notifications.")
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, null))
            .build()
    NotificationManagerCompat.from(context).notify(LOGGED_OUT_ID, notification)
}

/**
 * Say once that the battery exemption is gone. Posted from a check that did get through,
 * which may well be the last one before Power saving mode or a sleeping-apps sweep stops
 * them. Tapping it opens the app, which asks for the exemption back.
 */
fun notifyRestricted(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    val notification =
        NotificationCompat
            .Builder(context, ACCOUNT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Message notifications may stop")
            .setContentText("Open Insta Chat and allow it to run in the background.")
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, null))
            .build()
    NotificationManagerCompat.from(context).notify(RESTRICTED_ID, notification)
}

/** Clear everything: opening the app is reading the messages. */
fun cancelAllNotifications(context: Context) {
    NotificationManagerCompat.from(context).cancelAll()
}

private fun openIntent(
    context: Context,
    url: String?,
): PendingIntent {
    val intent =
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            url?.let { data = it.toUri() }
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    return PendingIntent.getActivity(
        context,
        // A request code per URL, or every thread's notification would share one
        // PendingIntent and all open whichever thread was posted last.
        url.hashCode(),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

private const val MICROS_PER_MILLI = 1000L
