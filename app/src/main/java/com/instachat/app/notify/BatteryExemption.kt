package com.instachat.app.notify

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

/*
 * The battery exemption ("Unrestricted" under App info > Battery) is what lets the inbox
 * check reach the network at all while Power saving mode is on: Android then cuts every
 * background app off except the exempt ones. Messaging apps get past it on Google's push
 * service, which this app cannot use. Found on the S23 on 0.4, where no check ever ran.
 *
 * The exemption is lost on uninstall, and Samsung's "sleeping apps" can take it away, so
 * it is checked on every launch rather than set up once.
 */

fun isBatteryExempt(context: Context): Boolean =
    context
        .getSystemService(
            PowerManager::class.java,
        ).isIgnoringBatteryOptimizations(context.packageName)

/**
 * Whether to show the system's exemption dialog on this launch.
 *
 * Once, the first time it is missing; after that only when an exemption the app had has
 * gone again. A user who said no is not asked on every launch - the banner
 * ([bannerFor]) says what that costs instead.
 */
fun shouldAskForExemption(
    exempt: Boolean,
    askedBefore: Boolean,
    wasExempt: Boolean,
): Boolean = !exempt && (!askedBefore || wasExempt)

/** What keeps message notifications from arriving, in the order it should be fixed. */
enum class Blocker {
    /** Notifications are off for the app: nothing can be shown at all. */
    NOTIFICATIONS_OFF,

    /** No battery exemption: checks stop whenever Power saving mode is on. */
    NOT_EXEMPT,
}

fun blockerOf(
    notificationsEnabled: Boolean,
    exempt: Boolean,
): Blocker? =
    when {
        !notificationsEnabled -> Blocker.NOTIFICATIONS_OFF
        !exempt -> Blocker.NOT_EXEMPT
        else -> null
    }

/**
 * The banner to show over the chat, if any: the current [blocker], unless the user has
 * dismissed that same one. A dismissal holds until the problem has cleared, so a choice
 * is respected but a later, separate loss is still reported.
 */
fun bannerFor(
    blocker: Blocker?,
    dismissed: Blocker?,
): Blocker? = blocker?.takeIf { it != dismissed }

/**
 * The dismissal to keep after seeing [blocker]: forgotten once nothing blocks, so the
 * banner comes back if the problem does.
 */
fun dismissalAfter(
    blocker: Blocker?,
    dismissed: Blocker?,
): Blocker? = if (blocker == null) null else dismissed

/**
 * Ask for the exemption with the system's own yes/no dialog, falling back to the list of
 * apps if a vendor build has removed the dialog.
 *
 * Lint's BatteryLife check warns that Play restricts this request to a few app categories.
 * This app is side-loaded, and is one of those categories in substance: a messaging client
 * that has no push channel.
 */
@SuppressLint("BatteryLife")
fun requestBatteryExemption(context: Context) {
    val direct =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            "package:${context.packageName}".toUri(),
        )
    try {
        context.startActivity(direct)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            // Nothing left to open. The banner still says what to change.
        }
    }
}

/** Open the app's notification settings, where notifications can be turned back on. */
fun openNotificationSettings(context: Context) {
    val intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No settings screen to open; the banner's text is all that can be done.
    }
}
