package com.instachat.app.notify

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What one inbox check came back with. */
sealed interface FetchResult {
    /**
     * @param tokens the tokens the call was made with when they were accepted, to keep
     *   for the next check; null when they were not, or the call never got that far.
     */
    data class Done(
        val inbox: InboxResult,
        val tokens: Tokens? = null,
    ) : FetchResult

    /** Worth trying again later: no connection, a rate limit, a server error. */
    data class Transient(
        val reason: String,
    ) : FetchResult
}

/**
 * The web session, as the WebView's cookie jar holds it.
 *
 * [cookieHeader] is sent verbatim; the CSRF token is read out of it because the request
 * also has to carry it as a header.
 */
data class Session(
    val cookieHeader: String,
    val csrfToken: String?,
) {
    companion object {
        /**
         * Build a session from a `Cookie` header, or null when there is no login in it.
         *
         * `sessionid` is the cookie that is the login; without it every request would only
         * be answered with a redirect to the login page, so there is no point asking.
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
            return Session(header, cookies["csrftoken"])
        }
    }
}

/**
 * The thread-list query instagram.com sends from its inbox, made from outside the page.
 *
 * Two requests at most, usually one. The call needs the session's fb_dtsg token, which is
 * kept between checks; only when there is none, or the endpoint refuses it - which it does
 * by answering with an HTML page instead of JSON - is it read afresh from the inbox page,
 * a ~450 KB download, and the call made once more.
 *
 * Replaces a GET against /api/v1/direct_v2/inbox/, which Instagram retired: it answered
 * 404 even from inside the logged-in site (checked over DevTools, 2026-11-01).
 */
class InboxClient(
    private val http: OkHttpClient = defaultClient(),
    private val baseUrl: HttpUrl = "https://www.instagram.com/".toHttpUrl(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val graphqlUrl = baseUrl.newBuilder().addPathSegments("api/graphql").build()
    private val inboxUrl = baseUrl.newBuilder().addPathSegments("direct/inbox/").build()

    suspend fun fetch(
        session: Session,
        userAgent: String,
        query: InboxQuery,
        cachedTokens: Tokens?,
    ): FetchResult =
        withContext(io) {
            try {
                val first =
                    cachedTokens?.let { post(session, userAgent, query, it) }
                        ?: Outcome.TokensRefused
                if (first != Outcome.TokensRefused) return@withContext first.result(cachedTokens)

                when (val page = fetchTokens(session, userAgent)) {
                    is TokenFetch.Got -> {
                        post(session, userAgent, query, page.tokens).result(page.tokens)
                    }

                    is TokenFetch.Failed -> {
                        page.result
                    }
                }
            } catch (e: IOException) {
                FetchResult.Transient(e.javaClass.simpleName + ": " + e.message)
            }
        }

    /** What one GraphQL call said, before deciding whether to try again. */
    private sealed interface Outcome {
        data class Final(
            val fetch: FetchResult,
        ) : Outcome

        data object TokensRefused : Outcome

        fun result(tokens: Tokens?): FetchResult =
            when (this) {
                is Final -> {
                    val done = fetch as? FetchResult.Done
                    // Tokens are only worth keeping once a call has actually read an inbox.
                    if (done?.inbox is InboxResult.Parsed) done.copy(tokens = tokens) else fetch
                }

                // Only reached with tokens fresh from the inbox page: a stale cached pair is
                // replaced before giving up.
                TokensRefused -> {
                    FetchResult.Done(InboxResult.Unreadable("fresh tokens refused"))
                }
            }
    }

    private sealed interface TokenFetch {
        data class Got(
            val tokens: Tokens,
        ) : TokenFetch

        data class Failed(
            val result: FetchResult,
        ) : TokenFetch
    }

    private fun post(
        session: Session,
        userAgent: String,
        query: InboxQuery,
        tokens: Tokens,
    ): Outcome {
        val form =
            FormBody
                .Builder()
                .add("doc_id", query.docId)
                .add("variables", query.variables)
                .add("fb_dtsg", tokens.fbDtsg)
                .apply { tokens.lsd?.let { add("lsd", it) } }
                .add("fb_api_req_friendly_name", InboxQuery.FRIENDLY_NAME)
                .add("server_timestamps", "true")
                .build()
        val request =
            siteRequest(session, userAgent)
                .url(graphqlUrl)
                .post(form)
                // The public id of the instagram.com web client. Every request the site
                // makes carries it.
                .header("X-IG-App-ID", WEB_APP_ID)
                .header("X-FB-Friendly-Name", InboxQuery.FRIENDLY_NAME)
                .header("Origin", baseUrl.toString().trimEnd('/'))
                .header("Accept", "*/*")
                // Required. A browser adds these to every fetch on its own, so the in-page
                // tests never showed it; without them the endpoint answers with an HTML page
                // exactly as for a refused token (measured from outside the WebView).
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
                .apply { tokens.lsd?.let { header("X-FB-LSD", it) } }
                .apply { session.csrfToken?.let { header("X-CSRFToken", it) } }
                .build()

        return http.newCall(request).execute().use { response ->
            val code = response.code
            when {
                code in HTTP_OK_RANGE && isHtml(response) -> {
                    Outcome.TokensRefused
                }

                code in HTTP_OK_RANGE -> {
                    Outcome.Final(
                        FetchResult.Done(parseInbox(response.body.string())),
                    )
                }

                else -> {
                    Outcome.Final(failure(code))
                }
            }
        }
    }

    private fun fetchTokens(
        session: Session,
        userAgent: String,
    ): TokenFetch {
        val request =
            siteRequest(session, userAgent)
                .url(inboxUrl)
                .header("Accept", "text/html")
                .build()
        return http.newCall(request).execute().use { response ->
            val code = response.code
            if (code !in HTTP_OK_RANGE) return@use TokenFetch.Failed(failure(code))
            extractTokens(response.body.string())?.let { TokenFetch.Got(it) }
                ?: TokenFetch.Failed(
                    FetchResult.Done(InboxResult.Unreadable("no tokens in the inbox page")),
                )
        }
    }

    private fun siteRequest(
        session: Session,
        userAgent: String,
    ): Request.Builder =
        Request
            .Builder()
            .header("Cookie", session.cookieHeader)
            .header("User-Agent", userAgent)
            .header("Referer", inboxUrl.toString())

    /** Any status that is not a 2xx, for either request. */
    private fun failure(code: Int): FetchResult =
        when {
            // Redirects are not followed: the only place the site sends a request is the
            // login page or a checkpoint, and both need the user.
            code in HTTP_REDIRECT_RANGE || code == HTTP_UNAUTHORIZED -> {
                FetchResult.Done(InboxResult.LoggedOut)
            }

            code == HTTP_TOO_MANY_REQUESTS || code >= HTTP_SERVER_ERROR -> {
                FetchResult.Transient("HTTP $code")
            }

            // Not transient: a 404 is how the old endpoint went away, and retrying it every
            // fifteen minutes in silence is exactly what hid that for days.
            else -> {
                FetchResult.Done(InboxResult.Unreadable("HTTP $code"))
            }
        }

    private fun isHtml(response: Response): Boolean =
        response.header("Content-Type")?.startsWith("text/html") == true

    companion object {
        const val WEB_APP_ID = "936619743392459"

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR = 500
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
