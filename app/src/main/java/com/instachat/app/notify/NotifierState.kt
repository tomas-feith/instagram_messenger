package com.instachat.app.notify

import android.content.Context
import androidx.core.content.edit

/**
 * The only state this app keeps of its own: what the notifier has already said.
 *
 * SharedPreferences rather than Room. There is no user data here - the messages live on
 * Instagram's servers and the login lives in the WebView's cookie jar - only a watermark,
 * the inbox query and its tokens, and a few flags, all cheap to lose: a lost watermark
 * costs one silent re-seed, lost tokens one extra page download, a lost query a fallback
 * until the inbox is next opened, and a lost flag at worst one repeated notification.
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

    /** The inbox query last captured from the site, or null if none has been yet. */
    var inboxQuery: InboxQuery?
        get() {
            val docId = prefs.getString(KEY_QUERY_DOC_ID, null) ?: return null
            val variables = prefs.getString(KEY_QUERY_VARIABLES, null) ?: return null
            return InboxQuery(docId, variables)
        }
        set(value) =
            prefs.edit {
                if (value == null) {
                    remove(KEY_QUERY_DOC_ID)
                    remove(KEY_QUERY_VARIABLES)
                } else {
                    putString(KEY_QUERY_DOC_ID, value.docId)
                    putString(KEY_QUERY_VARIABLES, value.variables)
                }
            }

    /**
     * The session's GraphQL tokens, captured from the site or read from the inbox page.
     * As private as the cookie jar they belong to, and Auto Backup is off for the app.
     */
    var tokens: Tokens?
        get() {
            val dtsg = prefs.getString(KEY_FB_DTSG, null) ?: return null
            return Tokens(dtsg, prefs.getString(KEY_LSD, null))
        }
        set(value) =
            prefs.edit {
                if (value == null) {
                    remove(KEY_FB_DTSG)
                    remove(KEY_LSD)
                } else {
                    putString(KEY_FB_DTSG, value.fbDtsg)
                    if (value.lsd == null) remove(KEY_LSD) else putString(KEY_LSD, value.lsd)
                }
            }

    /** Whether the "notifications stopped working" warning is up since checks broke. */
    var brokenNotified: Boolean
        get() = prefs.getBoolean(KEY_BROKEN, false)
        set(value) = prefs.edit { putBoolean(KEY_BROKEN, value) }

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

    /** The blocker whose banner the user dismissed, until that problem clears. */
    var dismissedBlocker: Blocker?
        get() {
            val name = prefs.getString(KEY_DISMISSED, null)
            return Blocker.entries.firstOrNull { it.name == name }
        }
        set(value) =
            prefs.edit {
                if (value == null) remove(KEY_DISMISSED) else putString(KEY_DISMISSED, value.name)
            }

    private companion object {
        const val KEY_DISMISSED = "dismissed_blocker"
        const val KEY_WATERMARK = "watermark_micros"
        const val KEY_LOGGED_OUT = "logged_out_notified"
        const val KEY_EXEMPTION_ASKED = "exemption_asked"
        const val KEY_WAS_EXEMPT = "was_exempt"
        const val KEY_RESTRICTED = "restricted_notified"
        const val KEY_QUERY_DOC_ID = "inbox_query_doc_id"
        const val KEY_QUERY_VARIABLES = "inbox_query_variables"
        const val KEY_FB_DTSG = "fb_dtsg"
        const val KEY_LSD = "lsd"
        const val KEY_BROKEN = "broken_notified"
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
