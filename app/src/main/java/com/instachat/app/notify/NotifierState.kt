package com.instachat.app.notify

import android.content.Context
import androidx.core.content.edit

/**
 * The only state this app keeps of its own: what the notifier has already said.
 *
 * SharedPreferences rather than Room. There is no user data here - the messages live on
 * Instagram's servers and the login lives in the WebView's cookie jar - only a watermark and
 * a few flags, all cheap to lose: a lost watermark costs one silent re-seed, and a lost
 * flag at worst one repeated dialog or notification.
 */
class NotifierState(
    context: Context,
) {
    private val prefs =
        context.applicationContext.getSharedPreferences("notifier", Context.MODE_PRIVATE)

    /**
     * Timestamp, in Instagram's microseconds, of the newest message already accounted for,
     * or null before the first successful check.
     */
    var watermarkMicros: Long?
        get() = if (prefs.contains(KEY_WATERMARK)) prefs.getLong(KEY_WATERMARK, 0L) else null
        set(value) =
            prefs.edit {
                if (value == null) remove(KEY_WATERMARK) else putLong(KEY_WATERMARK, value)
            }

    /** Whether the "log in again" notification has been posted since the session broke. */
    var loggedOutNotified: Boolean
        get() = prefs.getBoolean(KEY_LOGGED_OUT, false)
        set(value) = prefs.edit { putBoolean(KEY_LOGGED_OUT, value) }

    /** Whether the battery-exemption dialog has ever been shown. */
    var exemptionAsked: Boolean
        get() = prefs.getBoolean(KEY_EXEMPTION_ASKED, false)
        set(value) = prefs.edit { putBoolean(KEY_EXEMPTION_ASKED, value) }

    /** Whether the app was battery-exempt at the last launch, to notice it being lost. */
    var wasExempt: Boolean
        get() = prefs.getBoolean(KEY_WAS_EXEMPT, false)
        set(value) = prefs.edit { putBoolean(KEY_WAS_EXEMPT, value) }

    /** Whether the "notifications may stop" warning has been posted since the exemption went. */
    var restrictedNotified: Boolean
        get() = prefs.getBoolean(KEY_RESTRICTED, false)
        set(value) = prefs.edit { putBoolean(KEY_RESTRICTED, value) }

    private companion object {
        const val KEY_WATERMARK = "watermark_micros"
        const val KEY_LOGGED_OUT = "logged_out_notified"
        const val KEY_EXEMPTION_ASKED = "exemption_asked"
        const val KEY_WAS_EXEMPT = "was_exempt"
        const val KEY_RESTRICTED = "restricted_notified"
    }
}

/**
 * Whether the chat screen is on screen right now.
 *
 * A notification for a message the user is looking at is noise. Set from the activity's
 * onStart/onStop, and read by the worker, which runs in the same process whenever the
 * activity is alive - so the flag is never stale in the case it is needed for.
 */
object AppVisibility {
    @Volatile
    var inForeground: Boolean = false
}
