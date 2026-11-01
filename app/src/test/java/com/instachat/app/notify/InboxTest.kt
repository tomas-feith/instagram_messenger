package com.instachat.app.notify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxTest {
    /**
     * The fixture as the endpoint sends it: the response on one line, then a further
     * streamed line that is not part of the thread list.
     */
    private val sample =
        Json
            .parseToJsonElement(
                requireNotNull(javaClass.getResource("/inbox-sample.json")).readText(),
            ).toString() + "\n" + """{"data":{"label":"streamed"},"extensions":{"is_final":true}}"""

    private fun parsed(): InboxResult.Parsed = parseInbox(sample) as InboxResult.Parsed

    private fun thread(id: String) = parsed().threads.single { it.threadId == id }

    @Test
    fun `a thread without an id or a viewer is skipped, not fatal`() {
        val ids = parsed().threads.map { it.threadId }
        assertEquals(
            listOf("t-unread", "t-read", "t-mine", "t-group", "t-share", "t-muted", "t-empty"),
            ids,
        )
    }

    @Test
    fun `only the first line of a streamed response is read`() {
        assertEquals(7, parsed().threads.size)
    }

    @Test
    fun `the thread id is the one web urls use`() {
        // thread_fbid, not the 39-digit legacy thread_id the sample also carries.
        assertEquals("t-unread", parsed().threads.first().threadId)
    }

    @Test
    fun `timestamps arrive in milliseconds and are kept in microseconds`() {
        val unread = thread("t-unread")
        assertEquals(1727700000200000L, unread.last?.timestampMicros)
        assertEquals(1727700000100000L, unread.viewerSeenMicros)
    }

    @Test
    fun `ids and timestamps are read as numbers or strings`() {
        val read = thread("t-read")
        val last = read.last!!
        assertFalse(last.fromViewer)
        assertEquals(1727700000300000L, last.timestampMicros)
        assertEquals(1727700000300000L, read.viewerSeenMicros)
    }

    @Test
    fun `the viewer is recognised by messaging fbid`() {
        assertTrue(thread("t-mine").last!!.fromViewer)
        assertFalse(thread("t-unread").last!!.fromViewer)
    }

    @Test
    fun `only the viewer's own read receipt counts as seen`() {
        // t-group's only receipt is Dave's.
        assertEquals(null, thread("t-group").viewerSeenMicros)
    }

    @Test
    fun `group titles fall back to the first member and name the sender`() {
        val group = thread("t-group")
        assertEquals("Dave D", group.title)
        // Erin has no full name, so the username stands in.
        assertEquals("erin: lunch?", group.last?.preview)
    }

    @Test
    fun `a share uses the site's own description`() {
        assertEquals("frank sent an attachment.", thread("t-share").last?.preview)
    }

    @Test
    fun `a thread with no messages has no last message`() {
        assertEquals(null, thread("t-empty").last)
    }

    @Test
    fun `only unseen, unmuted messages from others count`() {
        val unread = unreadSince(parsed(), watermarkMicros = 0L)
        // Newest first. t-read is seen, t-mine is the viewer's own, t-muted is muted.
        assertEquals(listOf("t-group", "t-share", "t-unread"), unread.map { it.threadId })
        assertEquals("are you coming?", unread.last().text)
        assertEquals("alice", unread.last().title)
    }

    @Test
    fun `the watermark stops a repeat`() {
        val unread = unreadSince(parsed(), watermarkMicros = 1727700000400000L)
        assertEquals(listOf("t-group"), unread.map { it.threadId })
    }

    @Test
    fun `the next watermark is the newest message anywhere`() {
        // t-muted's message, which was not notified about, still moves it on.
        assertEquals(1727700000600000L, nextWatermark(parsed(), previous = 0L))
    }

    @Test
    fun `the watermark never moves backwards`() {
        assertEquals(1727800000000000L, nextWatermark(parsed(), previous = 1727800000000000L))
    }

    @Test
    fun `a stale query is unreadable, with the reason`() {
        val result =
            parseInbox(
                """{"errors":[{"message":"The GraphQL document with ID 1 was not found."}]}""",
            )
        assertTrue(result is InboxResult.Unreadable)
        assertTrue((result as InboxResult.Unreadable).reason.contains("was not found"))
    }

    @Test
    fun `an error prefixed to be unexecutable is still read`() {
        val result = parseInbox("""for (;;);{"errors":[{"message":"nope"}]}""")
        assertEquals(InboxResult.Unreadable("GraphQL error: nope"), result)
    }

    @Test
    fun `anything else is unreadable`() {
        for (body in listOf("<!DOCTYPE html><html>", """{"data":{}}""", "[]", "")) {
            assertTrue(body, parseInbox(body) is InboxResult.Unreadable)
        }
    }

    @Test
    fun `previews quote text and describe the rest`() {
        fun preview(json: String) = previewOf(Json.parseToJsonElement(json).jsonObject)

        assertEquals(
            "hi",
            preview("""{"content_type":"TEXT","text_body":"hi","igd_snippet":"Al: hi"}"""),
        )
        assertEquals(
            "sent a voice message",
            preview(
                """{"content_type":"AUDIOS","text_body":"","igd_snippet":"sent a voice message"}""",
            ),
        )
        assertEquals("Sent a message", preview("""{"content_type":"SOMETHING_NEW"}"""))
    }
}
