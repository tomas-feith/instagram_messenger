package com.instachat.app.notify

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What one inbox check came back with. */
sealed interface FetchResult {
    data class Done(
        val inbox: InboxResult,
    ) : FetchResult

    /** Worth trying again later: no connection, a rate limit, a server error. */
    data class Transient(
        val reason: String,
    ) : FetchResult
}

/**
 * The web session, as the WebView's cookie jar holds it.
 *
 * [cookieHeader] is sent verbatim; the other two are read out of it because the request
 * also has to carry them separately.
 */
data class Session(
    val cookieHeader: String,
    val csrfToken: String?,
    val viewerId: String?,
) {
    companion object {
        /**
         * Build a session from a `Cookie` header, or null when there is no login in it.
         *
         * `sessionid` is the cookie that is the login; without it the endpoint would only
         * answer with a redirect to the login page, so there is no point asking.
         */
        fun fromCookieHeader(header: String?): Session? {
            if (header.isNullOrBlank()) return null
            val cookies =
                header
                    .split(';')
                    .mapNotNull { part ->
                        val eq = part.indexOf('=')
                        if (eq <= 0) {
                            null
                        } else {
                            part.substring(0, eq).trim() to part.substring(eq + 1).trim()
                        }
                    }.toMap()
            if (cookies["sessionid"].isNullOrEmpty()) return null
            return Session(header, cookies["csrftoken"], cookies["ds_user_id"])
        }
    }
}

/**
 * One GET against the endpoint instagram.com uses to draw its own thread list.
 *
 * The request is shaped like the site's own: the same cookies, user agent and headers the
 * WebView would send. It asks for the most recent threads and only their last message,
 * which is all a notification needs.
 */
class InboxClient(
    private val http: OkHttpClient = defaultClient(),
    private val baseUrl: HttpUrl = "https://www.instagram.com/".toHttpUrl(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun fetch(
        session: Session,
        userAgent: String,
    ): FetchResult =
        withContext(io) {
            val url =
                baseUrl
                    .newBuilder()
                    .addPathSegments("api/v1/direct_v2/inbox/")
                    .addQueryParameter("persistentBadging", "true")
                    .addQueryParameter("folder", "")
                    .addQueryParameter("limit", THREAD_LIMIT.toString())
                    .addQueryParameter("thread_message_limit", "1")
                    .build()

            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("Cookie", session.cookieHeader)
                    .header("User-Agent", userAgent)
                    // The public id of the instagram.com web client. Every request the site
                    // makes carries it, and the API refuses the request without it.
                    .header("X-IG-App-ID", WEB_APP_ID)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", "https://www.instagram.com/direct/inbox/")
                    .header("Accept", "application/json")
                    .apply { session.csrfToken?.let { header("X-CSRFToken", it) } }
                    .build()

            try {
                http.newCall(request).execute().use { response ->
                    val code = response.code
                    val body = { parseInbox(response.body?.string().orEmpty(), session.viewerId) }
                    when {
                        code in HTTP_OK_RANGE -> {
                            FetchResult.Done(body())
                        }

                        // Redirects are not followed: the only place this endpoint sends a
                        // request is the login page or a checkpoint, and both need the user.
                        code in HTTP_REDIRECT_RANGE -> {
                            FetchResult.Done(InboxResult.LoggedOut)
                        }

                        code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN -> {
                            val parsed = body()
                            FetchResult.Done(
                                if (parsed == InboxResult.Unreadable && code == HTTP_UNAUTHORIZED) {
                                    InboxResult.LoggedOut
                                } else {
                                    parsed
                                },
                            )
                        }

                        else -> {
                            FetchResult.Transient("HTTP $code")
                        }
                    }
                }
            } catch (e: IOException) {
                FetchResult.Transient(e.javaClass.simpleName + ": " + e.message)
            }
        }

    companion object {
        const val WEB_APP_ID = "936619743392459"

        private const val THREAD_LIMIT = 20
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private val HTTP_OK_RANGE = 200..299
        private val HTTP_REDIRECT_RANGE = 300..399
        private const val TIMEOUT_SECONDS = 20L

        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
    }
}
