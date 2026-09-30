package com.instachat.app.web

import org.junit.Assert.assertEquals
import org.junit.Test

class UserAgentTest {
    @Test
    fun `drops the webview markers and keeps the engine`() {
        val webView =
            "Mozilla/5.0 (Linux; Android 15; Pixel 8 Build/AP3A.241005.015; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
                "Chrome/139.0.7258.94 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 15; Pixel 8 Build/AP3A.241005.015) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/139.0.7258.94 Mobile Safari/537.36",
            chromeLikeUserAgent(webView),
        )
    }

    @Test
    fun `leaves a chrome string alone`() {
        val chrome =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/139.0.0.0 Mobile Safari/537.36"
        assertEquals(chrome, chromeLikeUserAgent(chrome))
    }
}
