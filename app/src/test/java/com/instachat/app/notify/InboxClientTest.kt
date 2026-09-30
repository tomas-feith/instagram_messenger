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
    fun `sends the session the way the site does`() =
        runTest {
            server.enqueue(MockResponse().setBody("""{"inbox":{"threads":[]}}"""))

            client.fetch(session, "UA/1")

            val request = server.takeRequest()
            assertEquals("/api/v1/direct_v2/inbox/", request.requestUrl?.encodedPath)
            assertEquals("1", request.requestUrl?.queryParameter("thread_message_limit"))
            assertEquals(session.cookieHeader, request.getHeader("Cookie"))
            assertEquals("tok", request.getHeader("X-CSRFToken"))
            assertEquals(InboxClient.WEB_APP_ID, request.getHeader("X-IG-App-ID"))
            assertEquals("UA/1", request.getHeader("User-Agent"))
        }

    @Test
    fun `a good response is parsed with the cookie viewer`() =
        runTest {
            server.enqueue(MockResponse().setBody("""{"inbox":{"threads":[]}}"""))

            val result = client.fetch(session, "UA")

            assertEquals(FetchResult.Done(InboxResult.Parsed("1000", emptyList())), result)
        }

    @Test
    fun `a redirect to login is logged out, not followed`() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(302).setHeader("Location", "/accounts/login/"),
            )

            assertEquals(FetchResult.Done(InboxResult.LoggedOut), client.fetch(session, "UA"))
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `a 401 is logged out`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(401).setBody("nope"))
            assertEquals(FetchResult.Done(InboxResult.LoggedOut), client.fetch(session, "UA"))
        }

    @Test
    fun `a 403 that says login required is logged out`() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(403).setBody("""{"message":"login_required"}"""),
            )
            assertEquals(FetchResult.Done(InboxResult.LoggedOut), client.fetch(session, "UA"))
        }

    @Test
    fun `rate limits and server errors are transient`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setResponseCode(503))

            assertTrue(client.fetch(session, "UA") is FetchResult.Transient)
            assertTrue(client.fetch(session, "UA") is FetchResult.Transient)
        }

    @Test
    fun `no connection is transient`() =
        runTest {
            server.shutdown()
            assertTrue(client.fetch(session, "UA") is FetchResult.Transient)
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
        assertNull(parsed?.viewerId)
    }
}
