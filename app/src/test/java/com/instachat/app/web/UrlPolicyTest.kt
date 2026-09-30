package com.instachat.app.web

import com.instachat.app.web.UrlPolicy.Decision
import com.instachat.app.web.UrlPolicy.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlPolicyTest {
    private val ig = "https://www.instagram.com"
    private val inbox = UrlPolicy.INBOX_URL
    private val thread = "$ig/direct/t/340282366841710300949128137443944319108/"

    @Test
    fun `chat pages are chat`() {
        assertEquals(Kind.CHAT, UrlPolicy.kind(inbox))
        assertEquals(Kind.CHAT, UrlPolicy.kind(thread))
        assertEquals(Kind.CHAT, UrlPolicy.kind("$ig/direct/new/"))
        assertEquals(Kind.CHAT, UrlPolicy.kind("$ig/direct/requests/"))
        assertEquals(Kind.CHAT, UrlPolicy.kind("https://instagram.com/direct/inbox/"))
    }

    @Test
    fun `everything that is content is blocked`() {
        val paths =
            listOf(
                "/",
                "",
                "/explore/",
                "/reels/",
                "/natgeo/",
                "/natgeo/reels/",
                "/stories/natgeo/",
                "/accounts/activity/",
                "/explore/tags/cats/",
            )
        for (path in paths) {
            assertEquals(path, Kind.BLOCKED, UrlPolicy.kind("$ig$path"))
        }
    }

    @Test
    fun `login and checkpoints are auth`() {
        assertEquals(Kind.AUTH, UrlPolicy.kind("$ig/accounts/login/?next=%2Fdirect%2Finbox%2F"))
        assertEquals(Kind.AUTH, UrlPolicy.kind("$ig/accounts/onetap/?next=%2F"))
        assertEquals(Kind.AUTH, UrlPolicy.kind("$ig/challenge/action/AXE/"))
        assertEquals(Kind.AUTH, UrlPolicy.kind("$ig/two_factor?next=%2F"))
        assertEquals(Kind.AUTH, UrlPolicy.kind("https://www.facebook.com/login/"))
        assertEquals(Kind.AUTH, UrlPolicy.kind("https://www.facebook.com/v19.0/dialog/oauth?x=1"))
    }

    @Test
    fun `the rest of facebook is not auth`() {
        assertEquals(Kind.EXTERNAL, UrlPolicy.kind("https://www.facebook.com/"))
        assertEquals(Kind.EXTERNAL, UrlPolicy.kind("https://www.facebook.com/watch/"))
    }

    @Test
    fun `single shared items are items`() {
        assertEquals(Kind.ITEM, UrlPolicy.kind("$ig/p/C9xYz_1-AbC/"))
        assertEquals(Kind.ITEM, UrlPolicy.kind("$ig/p/C9xYz_1-AbC/?img_index=2"))
        assertEquals(Kind.ITEM, UrlPolicy.kind("$ig/reel/C9xYz_1-AbC/"))
        assertEquals(Kind.ITEM, UrlPolicy.kind("$ig/natgeo/p/C9xYz_1-AbC/"))
        assertEquals(Kind.ITEM, UrlPolicy.kind("$ig/reels/C9xYz_1-AbC/"))
        assertEquals(Kind.ITEM, UrlPolicy.kind("$ig/stories/natgeo/3456789012345678901/"))
        assertEquals(Kind.ITEM, UrlPolicy.kind("$ig/stories/highlights/17912345678901234/"))
    }

    @Test
    fun `other hosts and schemes are external`() {
        assertEquals(Kind.EXTERNAL, UrlPolicy.kind("https://example.com/article"))
        assertEquals(
            Kind.EXTERNAL,
            UrlPolicy.kind("https://l.instagram.com/?u=https%3A%2F%2Fx.com"),
        )
        assertEquals(
            Kind.EXTERNAL,
            UrlPolicy.kind("intent://user/natgeo#Intent;scheme=instagram;end"),
        )
        assertEquals(Kind.EXTERNAL, UrlPolicy.kind("mailto:someone@example.com"))
    }

    @Test
    fun `garbage is blocked, not crashed on`() {
        assertEquals(Kind.BLOCKED, UrlPolicy.kind("not a url at all"))
        assertEquals(Kind.BLOCKED, UrlPolicy.kind("about:blank"))
    }

    @Test
    fun `chat and auth are always allowed`() {
        assertEquals(Decision.Allow, UrlPolicy.decide(thread, inbox))
        assertEquals(Decision.Allow, UrlPolicy.decide(inbox, null))
        assertEquals(Decision.Allow, UrlPolicy.decide("$ig/accounts/login/", null))
    }

    @Test
    fun `the feed after login goes to the inbox instead`() {
        assertEquals(Decision.Redirect(inbox), UrlPolicy.decide("$ig/", "$ig/accounts/login/"))
        assertEquals(Decision.Redirect(inbox), UrlPolicy.decide("$ig/", null))
    }

    @Test
    fun `the feed from chat is refused in place`() {
        assertEquals(Decision.Block, UrlPolicy.decide("$ig/", thread))
        assertEquals(Decision.Block, UrlPolicy.decide("$ig/natgeo/", thread))
        assertEquals(Decision.Block, UrlPolicy.decide("$ig/explore/", "$ig/p/abc/"))
    }

    @Test
    fun `a shared post opens from a chat`() {
        assertEquals(Decision.Allow, UrlPolicy.decide("$ig/p/abc/", thread))
    }

    @Test
    fun `the reels viewer from a chat becomes the single reel`() {
        assertEquals(
            Decision.Redirect("$ig/reel/abc/"),
            UrlPolicy.decide("$ig/reels/abc/", thread),
        )
    }

    @Test
    fun `one post does not lead to another`() {
        assertEquals(Decision.Block, UrlPolicy.decide("$ig/p/other/", "$ig/p/abc/"))
        assertEquals(Decision.Block, UrlPolicy.decide("$ig/reels/next/", "$ig/reel/abc/"))
    }

    @Test
    fun `the same post settling its url is allowed`() {
        assertEquals(Decision.Allow, UrlPolicy.decide("$ig/p/abc/?img_index=1", "$ig/p/abc/"))
        assertEquals(Decision.Allow, UrlPolicy.decide("$ig/p/abc", "$ig/p/abc/"))
    }

    @Test
    fun `a post is not reachable except from chat`() {
        assertEquals(Decision.Block, UrlPolicy.decide("$ig/p/abc/", null))
        assertEquals(Decision.Block, UrlPolicy.decide("$ig/p/abc/", "$ig/accounts/login/"))
    }

    @Test
    fun `external links are handed out`() {
        val link = "https://example.com/a"
        assertEquals(Decision.External(link), UrlPolicy.decide(link, thread))
    }

    @Test
    fun `reels viewer rewrite ignores everything else`() {
        assertNull(UrlPolicy.reelsViewerAsSingleReel("$ig/reels/"))
        assertNull(UrlPolicy.reelsViewerAsSingleReel("$ig/reel/abc/"))
        assertEquals("$ig/reel/abc/", UrlPolicy.reelsViewerAsSingleReel("$ig/reels/abc"))
    }
}
