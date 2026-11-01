package com.instachat.app.notify

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLDecoder
import java.util.UUID

/*
 * What the inbox check needs to make the web inbox's own GraphQL call.
 *
 * Learned from the site rather than written down here: the page script
 * (assets/capture_inbox_query.js) passes on the body of the site's own request each time
 * the inbox is opened, and [parseCapturedRequest] keeps the parts that matter. The values
 * in [InboxQuery.fallback] are only for an install that has never seen one.
 */

/** A persisted GraphQL query: its id and the variables the site sends with it. */
data class InboxQuery(
    val docId: String,
    /** A JSON object, sent verbatim. */
    val variables: String,
) {
    companion object {
        const val FRIENDLY_NAME = "PolarisDirectInboxMobileQuery"

        /**
         * The query as captured on the S23 on 2026-11-01. The doc_id changes when
         * Instagram ships, so this goes stale; the check then reports itself broken and
         * opening the app captures the current one.
         */
        fun fallback(): InboxQuery =
            InboxQuery(
                docId = "28692310003774607",
                variables =
                    buildJsonObject {
                        put("device_id_for_iris_subscription", UUID.randomUUID().toString())
                        put("${PROVIDER}IGDIsProfessionalAccountGKrelayprovider", false)
                        put("${PROVIDER}IGDPinnedThreadsRenderEnabledGKrelayprovider", true)
                        put("${PROVIDER}IGDMaxUnreadMessagesCountrelayprovider", MAX_UNREAD_SHOWN)
                        put("${PROVIDER}IGDThreadListActionsEnabledGKrelayprovider", true)
                    }.toString(),
            )

        /** The prefix the site gives the feature flags it sends along with a query. */
        private const val PROVIDER = "__relay_internal__pv__"

        /** The value the site sent; it caps an unread badge the check never shows. */
        private const val MAX_UNREAD_SHOWN = 5
    }
}

/**
 * The per-session tokens a GraphQL call carries. Without a valid fb_dtsg the endpoint
 * answers with an HTML page rather than an error; lsd is sent the way the site sends it,
 * although it was not checked when tested.
 */
data class Tokens(
    val fbDtsg: String,
    val lsd: String?,
)

data class CapturedRequest(
    val query: InboxQuery,
    val tokens: Tokens?,
)

private val json = Json

/**
 * The parts of a captured request body worth keeping, or null if it is not the inbox
 * query or does not look like one. The body comes from a web page, so nothing in it is
 * kept unless it has the shape it should.
 */
fun parseCapturedRequest(body: String): CapturedRequest? {
    val form = parseForm(body)
    if (form["fb_api_req_friendly_name"] != InboxQuery.FRIENDLY_NAME) return null
    val docId = form["doc_id"]?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return null
    val variables = form["variables"]?.takeIf(::isJsonObject) ?: return null
    val tokens = form["fb_dtsg"]?.takeIf { it.isNotBlank() }?.let { Tokens(it, form["lsd"]) }
    return CapturedRequest(InboxQuery(docId, variables), tokens)
}

private val DTSG = Regex(""""DTSGInitialData",\[],\{"token":"([^"]+)"""")
private val LSD = Regex(""""LSD",\[],\{"token":"([^"]+)"""")

/** The tokens as the inbox page embeds them, or null without an fb_dtsg. */
fun extractTokens(html: String): Tokens? {
    val dtsg = DTSG.find(html)?.groupValues?.get(1) ?: return null
    return Tokens(dtsg, LSD.find(html)?.groupValues?.get(1))
}

/** `application/x-www-form-urlencoded`, decoded. A malformed pair is dropped. */
internal fun parseForm(body: String): Map<String, String> =
    body
        .split('&')
        .mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            try {
                // The String overload: the Charset one is API 33.
                URLDecoder.decode(pair.substring(0, eq), "UTF-8") to
                    URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            } catch (_: IllegalArgumentException) {
                null
            }
        }.toMap()

private fun isJsonObject(text: String): Boolean =
    try {
        json.parseToJsonElement(text) is JsonObject
    } catch (_: SerializationException) {
        false
    }
