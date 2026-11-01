package com.instachat.app.notify

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/*
 * Reading the response of the web inbox's GraphQL query (see InboxQuery.kt).
 *
 * The query is the one instagram.com itself sends to draw the thread list; it is not a
 * published API and has no schema. Everything here therefore reads defensively: a field
 * that is missing or has changed type makes that one thread unreadable, never the whole
 * response, and IDs and timestamps are accepted as either JSON numbers or strings.
 *
 * Timestamps arrive in milliseconds and are kept in microseconds, the unit of the REST
 * endpoint this replaced, so a watermark stored before the switch still means the same.
 *
 * Pure, so it can be tested against captured responses without a device.
 */

/** The last message in a thread, as far as a notification needs it. */
data class LastMessage(
    val fromViewer: Boolean,
    /** Microseconds since the epoch. */
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
        val threads: List<InboxThread>,
    ) : InboxResult

    /** The session is gone: the user has to open the app and log in again. */
    data object LoggedOut : InboxResult

    /**
     * A response that is neither an inbox nor a login demand: a stale query, refused
     * tokens, or a format change. [reason] is for the log only.
     */
    data class Unreadable(
        val reason: String,
    ) : InboxResult
}

/** One thread worth a notification. */
data class UnreadThread(
    val threadId: String,
    val title: String,
    val text: String,
    val timestampMicros: Long,
)

private val json = Json { ignoreUnknownKeys = true }

private const val MICROS_PER_MILLI = 1000L
private const val MAX_REASON_LENGTH = 120

/**
 * Parse [body], the first line of a GraphQL response - the endpoint streams further lines
 * after it, which a thread list does not need.
 */
fun parseInbox(body: String): InboxResult {
    val root =
        try {
            // Error responses are sometimes prefixed to make them unexecutable as script.
            json.parseToJsonElement(body.removePrefix("for (;;);").lineSequence().first())
                as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return InboxResult.Unreadable("not JSON")

    (root["errors"] as? JsonArray)?.firstOrNull()?.let { error ->
        val message = (error as? JsonObject)?.str("message") ?: error.toString()
        return InboxResult.Unreadable("GraphQL error: ${message.take(MAX_REASON_LENGTH)}")
    }

    val edges =
        root
            .obj("data")
            ?.obj("get_slide_mailbox_for_iris_subscription")
            ?.obj("threads_by_folder")
            ?.get("edges") as? JsonArray
            ?: return InboxResult.Unreadable("no thread list in the response")

    return InboxResult.Parsed(
        edges.mapNotNull { edge ->
            (edge as? JsonObject)?.obj("node")?.obj("as_ig_direct_thread")?.let(::parseThread)
        },
    )
}

private fun parseThread(thread: JsonObject): InboxThread? {
    // thread_fbid is what the site's own /direct/t/<id>/ URLs hold; checked by tapping a
    // thread on the device. `id` has carried the same value.
    val threadId = thread.str("thread_fbid") ?: thread.str("id") ?: return null
    // Messages and read receipts name people by their messaging fbid, not the Instagram
    // user id, so this is the id the viewer has to be recognised by.
    val viewer = thread.obj("viewer")?.str("interop_messaging_user_fbid") ?: return null
    val users = (thread["users"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    val isGroup = thread.bool("is_group") ?: (users.size > 1)

    val title =
        thread.str("thread_title")?.takeIf { it.isNotBlank() }
            ?: users.firstOrNull()?.let(::displayName)
            ?: "Instagram"

    // Newest first, as the site draws them.
    val lastNode =
        (thread.obj("slide_messages")?.get("edges") as? JsonArray)
            ?.firstOrNull()
            ?.let { (it as? JsonObject)?.obj("node") }
    val last =
        lastNode?.let { node ->
            val sender = node.str("sender_fbid") ?: return@let null
            val timestamp = node.str("timestamp_ms")?.toLongOrNull() ?: return@let null
            val fromViewer = sender == viewer
            val body = previewOf(node)
            val text =
                if (isGroup && !fromViewer && node.str("content_type") == "TEXT") {
                    val name =
                        users
                            .firstOrNull { it.str("interop_messaging_user_fbid") == sender }
                            ?.let(::displayName)
                    if (name != null) "$name: $body" else body
                } else {
                    body
                }
            LastMessage(fromViewer, timestamp * MICROS_PER_MILLI, text)
        }

    val seen =
        (thread["slide_read_receipts"] as? JsonArray)
            .orEmpty()
            .filterIsInstance<JsonObject>()
            .firstOrNull { it.str("participant_fbid") == viewer }
            ?.str("watermark_timestamp_ms")
            ?.toLongOrNull()
            ?.times(MICROS_PER_MILLI)

    return InboxThread(
        threadId = threadId,
        title = title,
        isGroup = isGroup,
        muted = thread.bool("is_muted") == true,
        last = last,
        viewerSeenMicros = seen,
    )
}

private fun displayName(user: JsonObject): String? =
    user.str("full_name")?.takeIf { it.isNotBlank() } ?: user.str("username")

/**
 * What the notification says the message was.
 *
 * Text is quoted. Everything else uses the site's own one-line snippet, which for shares,
 * reels and voice messages is a description ("sent an attachment.") and for a story reply
 * is the reply - never the caption of someone else's post.
 */
internal fun previewOf(node: JsonObject): String {
    val text = node.str("text_body")?.takeIf { it.isNotBlank() }
    val snippet = node.str("igd_snippet")?.takeIf { it.isNotBlank() }
    return when {
        node.str("content_type") == "TEXT" -> text ?: snippet
        else -> snippet ?: text
    } ?: "Sent a message"
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
            val unread = !last.fromViewer && (seen == null || last.timestampMicros > seen)
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

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.let {
        it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull()
    }
