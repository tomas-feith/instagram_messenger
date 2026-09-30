package com.instachat.app.notify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxTest {
    private val sample =
        requireNotNull(javaClass.getResource("/inbox-sample.json")).readText()

    private fun parsed(cookieViewer: String? = null): InboxResult.Parsed =
        parseInbox(sample, cookieViewer) as InboxResult.Parsed

    @Test
    fun `reads the viewer from the response when there is no cookie`() {
        assertEquals("1000", parsed().viewerId)
    }

    @Test
    fun `the cookie viewer wins`() {
        assertEquals("999", parsed("999").viewerId)
    }

    @Test
    fun `a thread without an id is skipped, not fatal`() {
        val ids = parsed().threads.map { it.threadId }
        assertEquals(listOf("t-unread", "t-read", "t-mine", "t-group", "t-muted", "t-empty"), ids)
    }

    @Test
    fun `ids and timestamps are read as numbers or strings`() {
        val read = parsed().threads.single { it.threadId == "t-read" }
        assertEquals("3000", read.last?.senderId)
        assertEquals(1727700000000300L, read.last?.timestampMicros)
        assertEquals(1727700000000300L, read.viewerSeenMicros)
    }

    @Test
    fun `group titles fall back to the first member and name the sender`() {
        val group = parsed().threads.single { it.threadId == "t-group" }
        assertEquals("Dave D", group.title)
        assertEquals("Erin E: Sent a post", group.last?.preview)
    }

    @Test
    fun `only unseen, unmuted messages from others count`() {
        val unread = unreadSince(parsed(), watermarkMicros = 0L)
        // Newest first. t-read is seen, t-mine is the viewer's own, t-muted is muted.
        assertEquals(listOf("t-group", "t-unread"), unread.map { it.threadId })
        assertEquals("are you coming?", unread.last().text)
        assertEquals("alice", unread.last().title)
    }

    @Test
    fun `the watermark stops a repeat`() {
        val unread = unreadSince(parsed(), watermarkMicros = 1727700000000200L)
        assertEquals(listOf("t-group"), unread.map { it.threadId })
    }

    @Test
    fun `the next watermark is the newest message anywhere`() {
        // t-muted's message, which was not notified about, still moves it on.
        assertEquals(1727700000000600L, nextWatermark(parsed(), previous = 0L))
    }

    @Test
    fun `the watermark never moves backwards`() {
        assertEquals(1727800000000000L, nextWatermark(parsed(), previous = 1727800000000000L))
    }

    @Test
    fun `a login demand is logged out`() {
        assertEquals(
            InboxResult.LoggedOut,
            parseInbox("""{"message":"login_required","status":"fail"}""", "1"),
        )
        assertEquals(InboxResult.LoggedOut, parseInbox("""{"require_login":true}""", "1"))
    }

    @Test
    fun `a checkpoint is logged out too`() {
        assertEquals(
            InboxResult.LoggedOut,
            parseInbox("""{"message":"checkpoint_required","status":"fail"}""", "1"),
        )
    }

    @Test
    fun `the v2 thread id is preferred, since it is what web urls use`() {
        // t-unread carries both ids in the sample; the others only the legacy one.
        assertEquals("t-unread", parsed().threads.first().threadId)
    }

    @Test
    fun `anything else is unreadable`() {
        assertEquals(InboxResult.Unreadable, parseInbox("<!DOCTYPE html><html>", "1"))
        assertEquals(InboxResult.Unreadable, parseInbox("""{"status":"ok"}""", "1"))
        assertEquals(InboxResult.Unreadable, parseInbox("[]", "1"))
        assertEquals(InboxResult.Unreadable, parseInbox("", "1"))
    }

    @Test
    fun `no viewer anywhere is unreadable`() {
        assertEquals(
            InboxResult.Unreadable,
            parseInbox("""{"inbox":{"threads":[]}}""", null),
        )
    }

    @Test
    fun `previews describe what cannot be quoted`() {
        fun preview(json: String) = previewOf(Json.parseToJsonElement(json).jsonObject)

        assertEquals("hi", preview("""{"item_type":"text","text":"hi"}"""))
        assertEquals("Sent a message", preview("""{"item_type":"text","text":""}"""))
        assertEquals("Sent a reel", preview("""{"item_type":"clip"}"""))
        assertEquals("Sent a voice message", preview("""{"item_type":"voice_media"}"""))
        assertEquals(
            "look https://x.com",
            preview("""{"item_type":"link","link":{"text":"look https://x.com"}}"""),
        )
        assertTrue(preview("""{"item_type":"something_new"}""").isNotBlank())
    }
}
