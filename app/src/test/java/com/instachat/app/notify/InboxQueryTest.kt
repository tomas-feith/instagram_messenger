package com.instachat.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

class InboxQueryTest {
    private fun form(vararg pairs: Pair<String, String>) =
        pairs.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }

    private val variables = """{"device_id_for_iris_subscription":"d","x":true}"""

    private val siteRequest =
        form(
            "av" to "17841400000000000",
            "__a" to "1",
            "fb_dtsg" to "NAf:17:1700000000",
            "jazoest" to "26000",
            "lsd" to "AVq",
            "fb_api_caller_class" to "RelayModern",
            "fb_api_req_friendly_name" to "PolarisDirectInboxMobileQuery",
            "variables" to variables,
            "server_timestamps" to "true",
            "doc_id" to "28692310003774607",
        )

    @Test
    fun `the site's own request yields the query and tokens`() {
        val captured = parseCapturedRequest(siteRequest)
        assertEquals(InboxQuery("28692310003774607", variables), captured?.query)
        assertEquals(Tokens("NAf:17:1700000000", "AVq"), captured?.tokens)
    }

    @Test
    fun `other queries are ignored`() {
        assertNull(
            parseCapturedRequest(
                siteRequest.replace("PolarisDirectInboxMobileQuery", "IGDThreadDetailQuery"),
            ),
        )
    }

    @Test
    fun `a body without the right shape is not kept`() {
        val name = "fb_api_req_friendly_name" to InboxQuery.FRIENDLY_NAME
        assertNull(parseCapturedRequest(form(name, "doc_id" to "12a", "variables" to "{}")))
        assertNull(parseCapturedRequest(form(name, "doc_id" to "12", "variables" to "[1]")))
        assertNull(parseCapturedRequest(form(name, "doc_id" to "12", "variables" to "{oops")))
        assertNull(parseCapturedRequest(form(name, "variables" to "{}")))
        assertNull(parseCapturedRequest(""))
    }

    @Test
    fun `a query without a token still counts`() {
        val captured =
            parseCapturedRequest(
                form(
                    "fb_api_req_friendly_name" to InboxQuery.FRIENDLY_NAME,
                    "doc_id" to "12",
                    "variables" to "{}",
                ),
            )
        assertEquals(InboxQuery("12", "{}"), captured?.query)
        assertNull(captured?.tokens)
    }

    @Test
    fun `tokens are read from the inbox page`() {
        val html =
            """x["DTSGInitialData",[],{"token":"NAf:17:1"},258]y["LSD",[],{"token":"AVq"},323]"""
        assertEquals(Tokens("NAf:17:1", "AVq"), extractTokens(html))
        assertNull(extractTokens("<html></html>"))
    }

    @Test
    fun `the fallback is a valid query`() {
        val fallback = InboxQuery.fallback()
        val captured =
            parseCapturedRequest(
                form(
                    "fb_api_req_friendly_name" to InboxQuery.FRIENDLY_NAME,
                    "doc_id" to fallback.docId,
                    "variables" to fallback.variables,
                ),
            )
        assertEquals(fallback, captured?.query)
        assertTrue(fallback.variables.contains("device_id_for_iris_subscription"))
    }

    @Test
    fun `malformed percent escapes drop only that pair`() {
        assertEquals(mapOf("b" to "2"), parseForm("a=%zz&b=2"))
    }
}
