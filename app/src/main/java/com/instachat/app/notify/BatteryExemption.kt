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
 * gone again. A user who said no is not asked on every launch.
 */
fun shouldAskForExemption(
    exempt: Boolean,
    askedBefore: Boolean,
    wasExempt: Boolean,
): Boolean = !exempt && (!askedBefore || wasExempt)

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
            // Nothing left to open. The warning notification still says what to change.
        }
    }
}
