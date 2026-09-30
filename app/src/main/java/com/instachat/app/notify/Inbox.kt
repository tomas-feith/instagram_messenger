package com.instachat.app.notify

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/*
 * Reading the response of Instagram's web inbox endpoint.
 *
 * The endpoint is the one instagram.com itself calls to draw the thread list; it is not a
 * published API and has no schema. Everything here therefore reads defensively: a field
 * that is missing or has changed type makes that one thread unreadable, never the whole
 * response, and IDs and timestamps are accepted as either JSON numbers or strings because
 * the site has sent both.
 *
 * Pure, so it can be tested against captured responses without a device.
 */

/** The last message in a thread, as far as a notification needs it. */
data class LastMessage(
    val senderId: String,
    /** Microseconds since the epoch, which is the unit Instagram uses throughout. */
    val timestampMicros: Long,
    val preview: String,
)

data class InboxThread(
    val threadId: String,
    val title: String,
    val isGroup: Boolean,
    val muted: Boolean,
    val last: LastMessage?,
    /** When the viewer last read this thread, or null if never. */
    val viewerSeenMicros: Long?,
)

sealed interface InboxResult {
    data class Parsed(
        val viewerId: String,
        val threads: List<InboxThread>,
    ) : InboxResult

    /** The session is gone: the user has to open the app and log in again. */
    data object LoggedOut : InboxResult

    /** A response that is neither an inbox nor a login demand. Probably a format change. */
    data object Unreadable : InboxResult
}

/** One thread worth a notification. */
data class UnreadThread(
    val threadId: String,
    val title: String,
    val text: String,
    val timestampMicros: Long,
)

private val json = Json { ignoreUnknownKeys = true }

private val LOGIN_MESSAGES = setOf("login_required", "checkpoint_required", "challenge_required")

/**
 * Parse [body].
 *
 * [cookieViewerId] is the `ds_user_id` cookie. The response usually names the viewer
 * too, but the cookie is what the session is actually for, so it wins when both exist.
 */
fun parseInbox(
    body: String,
    cookieViewerId: String?,
): InboxResult {
    val root =
        try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return InboxResult.Unreadable

    // A checkpoint counts as logged out too: either way nothing more will be read until
    // the user opens the app and deals with it, which is what the notification asks.
    if (root.bool("require_login") == true || root.str("message") in LOGIN_MESSAGES) {
        return InboxResult.LoggedOut
    }

    val threads = (root["inbox"] as? JsonObject)?.get("threads") as? JsonArray
    val viewerId =
        cookieViewerId?.takeIf { it.isNotBlank() }
            ?: (root["viewer"] as? JsonObject)?.str("pk")
    if (threads == null || viewerId == null) return InboxResult.Unreadable

    return InboxResult.Parsed(
        viewerId = viewerId,
        threads = threads.mapNotNull { (it as? JsonObject)?.let { t -> parseThread(t, viewerId) } },
    )
}

private fun parseThread(
    thread: JsonObject,
    viewerId: String,
): InboxThread? {
    // The web client addresses threads by the short v2 id in its URLs (/direct/t/<id>/);
    // the long legacy thread_id is kept only as a fallback for responses without one.
    val threadId = thread.str("thread_v2_id") ?: thread.str("thread_id") ?: return null
    val users = (thread["users"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    val isGroup = thread.bool("is_group") ?: (users.size > 1)

    val title =
        thread.str("thread_title")?.takeIf { it.isNotBlank() }
            ?: users.firstOrNull()?.let(::displayName)
            ?: "Instagram"

    val lastItem = (thread["items"] as? JsonArray)?.firstOrNull() as? JsonObject
    val last =
        lastItem?.let { item ->
            val sender = item.str("user_id") ?: return@let null
            val timestamp = item.str("timestamp")?.toLongOrNull() ?: return@let null
            val body = previewOf(item)
            val text =
                if (isGroup && sender != viewerId) {
                    val name = users.firstOrNull { it.str("pk") == sender }?.let(::displayName)
                    if (name != null) "$name: $body" else body
                } else {
                    body
                }
            LastMessage(sender, timestamp, text)
        }

    val seen =
        ((thread["last_seen_at"] as? JsonObject)?.get(viewerId) as? JsonObject)
            ?.str("timestamp")
            ?.toLongOrNull()

    return InboxThread(
        threadId = threadId,
        title = title,
        isGroup = isGroup,
        muted = thread.bool("muted") == true,
        last = last,
        viewerSeenMicros = seen,
    )
}

private fun displayName(user: JsonObject): String? =
    user.str("full_name")?.takeIf { it.isNotBlank() } ?: user.str("username")

/**
 * What the notification says the message was.
 *
 * Only text is quoted. Everything else is described, because the notification cannot show
 * a reel - and quoting the caption of someone else's post as if a friend had written it
 * would be misleading.
 */
internal fun previewOf(item: JsonObject): String {
    val type = item.str("item_type")
    return when (type) {
        "text" -> {
            item.str("text")?.takeIf { it.isNotBlank() } ?: "Sent a message"
        }

        "link" -> {
            (item["link"] as? JsonObject)?.str("text")?.takeIf { it.isNotBlank() }
                ?: "Sent a link"
        }

        "media", "raven_media", "visual_media" -> {
            "Sent a photo or video"
        }

        "voice_media" -> {
            "Sent a voice message"
        }

        "clip", "xma_clip" -> {
            "Sent a reel"
        }

        "media_share", "xma_media_share" -> {
            "Sent a post"
        }

        "story_share", "reel_share", "xma_story_share", "xma_reel_share" -> {
            "Replied to a story"
        }

        "animated_media" -> {
            "Sent a GIF"
        }

        "like" -> {
            "Sent a like"
        }

        else -> {
            "Sent a message"
        }
    }
}

/**
 * Threads to notify about.
 *
 * A thread qualifies when its last message came from someone else, the viewer has not
 * read past it, it is not muted, and it is newer than [watermarkMicros] - the newest
 * message already notified about. The watermark is what stops a thread left unread for a
 * day from being announced again every fifteen minutes.
 */
fun unreadSince(
    inbox: InboxResult.Parsed,
    watermarkMicros: Long,
): List<UnreadThread> =
    inbox.threads
        .asSequence()
        .filter { !it.muted }
        .mapNotNull { thread ->
            val last = thread.last ?: return@mapNotNull null
            val seen = thread.viewerSeenMicros
            val unread =
                last.senderId != inbox.viewerId && (seen == null || last.timestampMicros > seen)
            if (unread && last.timestampMicros > watermarkMicros) {
                UnreadThread(thread.threadId, thread.title, last.preview, last.timestampMicros)
            } else {
                null
            }
        }.sortedByDescending { it.timestampMicros }
        .toList()

/**
 * The watermark to store after a check: the newest message seen anywhere in the inbox.
 *
 * Every thread counts, not only the ones notified about. A message the user sent, or read
 * in the app, is equally "already accounted for", and advancing past it keeps the next
 * check from announcing something older that only now appears unread - a thread marked
 * unread by hand, say.
 */
fun nextWatermark(
    inbox: InboxResult.Parsed,
    previous: Long,
): Long = maxOf(previous, inbox.threads.maxOfOrNull { it.last?.timestampMicros ?: 0L } ?: 0L)

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.let {
        it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull()
    }
