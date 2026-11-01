package com.instachat.app.notify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InboxClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: InboxClient

    private val session =
        Session.fromCookieHeader("csrftoken=tok; ds_user_id=1000; sessionid=abc%3Adef")!!
    private val query = InboxQuery("123", """{"a":1}""")
    private val cached = Tokens("cached-dtsg", "cached-lsd")

    private val emptyInbox =
        """{"data":{"get_slide_mailbox_for_iris_subscription":{"threads_by_folder":""" +
            """{"edges":[]}}}}"""
    private val inboxPage =
        """<script>["DTSGInitialData",[],{"token":"page-dtsg"},258],""" +
            """["LSD",[],{"token":"page-lsd"},323]</script>"""

    private fun json(body: String) =
        MockResponse().setHeader("Content-Type", "text/javascript; charset=utf-8").setBody(body)

    private fun html(body: String) =
        MockResponse().setHeader("Content-Type", "text/html; charset=\"utf-8\"").setBody(body)

    private suspend fun fetch(tokens: Tokens? = cached) =
        client.fetch(session, "UA/1", query, tokens)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client =
            InboxClient(
                http = InboxClient.defaultClient(),
                baseUrl = server.url("/"),
                io = Dispatchers.Unconfined,
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `sends the query and session the way the site does`() =
        runTest {
            server.enqueue(json(emptyInbox))

            fetch()

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/graphql", request.requestUrl?.encodedPath)
            val form = parseForm(request.body.readUtf8())
            assertEquals("123", form["doc_id"])
            assertEquals("""{"a":1}""", form["variables"])
            assertEquals("cached-dtsg", form["fb_dtsg"])
            assertEquals("cached-lsd", form["lsd"])
            assertEquals(session.cookieHeader, request.getHeader("Cookie"))
            assertEquals("tok", request.getHeader("X-CSRFToken"))
            assertEquals("cached-lsd", request.getHeader("X-FB-LSD"))
            assertEquals(InboxClient.WEB_APP_ID, request.getHeader("X-IG-App-ID"))
            assertEquals("UA/1", request.getHeader("User-Agent"))
            // Without these the endpoint answers with a page, as for a refused token.
            assertEquals("same-origin", request.getHeader("Sec-Fetch-Site"))
            assertEquals("cors", request.getHeader("Sec-Fetch-Mode"))
        }

    @Test
    fun `a good response is parsed and the tokens kept`() =
        runTest {
            server.enqueue(json(emptyInbox))

            assertEquals(FetchResult.Done(InboxResult.Parsed(emptyList()), cached), fetch())
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `without tokens they are read from the inbox page first`() =
        runTest {
            server.enqueue(html(inboxPage))
            server.enqueue(json(emptyInbox))

            val result = fetch(tokens = null)

            assertEquals(
                FetchResult.Done(InboxResult.Parsed(emptyList()), Tokens("page-dtsg", "page-lsd")),
                result,
            )
            assertEquals("/direct/inbox/", server.takeRequest().requestUrl?.encodedPath)
            assertEquals("page-dtsg", parseForm(server.takeRequest().body.readUtf8())["fb_dtsg"])
        }

    @Test
    fun `refused tokens are replaced once, then the call is repeated`() =
        runTest {
            // A refused fb_dtsg is answered with a page, not an error.
            server.enqueue(html("<!DOCTYPE html><html>"))
            server.enqueue(html(inboxPage))
            server.enqueue(json(emptyInbox))

            val result = fetch()

            assertEquals(Tokens("page-dtsg", "page-lsd"), (result as FetchResult.Done).tokens)
            assertEquals(3, server.requestCount)
        }

    @Test
    fun `fresh tokens refused too is unreadable, not a loop`() =
        runTest {
            server.enqueue(html("<!DOCTYPE html><html>"))
            server.enqueue(html(inboxPage))
            server.enqueue(html("<!DOCTYPE html><html>"))

            val result = fetch()

            assertEquals(FetchResult.Done(InboxResult.Unreadable("fresh tokens refused")), result)
            assertEquals(3, server.requestCount)
        }

    @Test
    fun `an inbox page without tokens is unreadable`() =
        runTest {
            server.enqueue(html("<html>nothing here</html>"))

            val result = fetch(tokens = null) as FetchResult.Done

            assertTrue(result.inbox is InboxResult.Unreadable)
            assertNull(result.tokens)
        }

    @Test
    fun `a stale query is unreadable and keeps no tokens`() =
        runTest {
            server.enqueue(
                json("""{"errors":[{"message":"The GraphQL document was not found."}]}"""),
            )

            val result = fetch() as FetchResult.Done

            assertTrue(result.inbox is InboxResult.Unreadable)
            assertNull(result.tokens)
        }

    @Test
    fun `a redirect to login is logged out, not followed`() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(302).setHeader("Location", "/accounts/login/"),
            )

            assertEquals(FetchResult.Done(InboxResult.LoggedOut), fetch(tokens = null))
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `a 401 is logged out`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(401).setBody("nope"))
            assertEquals(FetchResult.Done(InboxResult.LoggedOut), fetch())
        }

    @Test
    fun `a 404 is unreadable, not transient`() =
        runTest {
            // How the retired endpoint failed: retried silently, it hid the breakage.
            server.enqueue(MockResponse().setResponseCode(404))
            assertEquals(FetchResult.Done(InboxResult.Unreadable("HTTP 404")), fetch())
        }

    @Test
    fun `rate limits and server errors are transient`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setResponseCode(503))

            assertTrue(fetch() is FetchResult.Transient)
            assertTrue(fetch() is FetchResult.Transient)
        }

    @Test
    fun `no connection is transient`() =
        runTest {
            server.shutdown()
            assertTrue(fetch() is FetchResult.Transient)
        }

    @Test
    fun `a session needs the sessionid cookie`() {
        assertNull(Session.fromCookieHeader(null))
        assertNull(Session.fromCookieHeader(""))
        assertNull(Session.fromCookieHeader("csrftoken=tok; ds_user_id=1000"))
        assertNull(Session.fromCookieHeader("sessionid="))
    }

    @Test
    fun `cookie values may contain equals signs`() {
        val parsed = Session.fromCookieHeader("sessionid=a=b; csrftoken=x=y")
        assertEquals("x=y", parsed?.csrfToken)
    }
}
