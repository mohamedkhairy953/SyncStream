package com.syncstream.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wire-format compatibility of the "cmd" channel protocol (see CONTRACTS.md, `SyncMessage`). */
class SyncMessageTest {

    private fun roundTrip(message: SyncMessage): SyncMessage =
        SyncJson.decodeFromString(SyncMessage.serializer(), SyncJson.encodeToString(SyncMessage.serializer(), message))

    @Test
    fun `state carries presentation fields through a round trip`() {
        val state = SyncMessage.State(
            playing = false,
            positionMs = 0L,
            masterClockMs = 1234L,
            durationMs = 0L,
            mediaKind = "pdf",
            mediaTitle = "slides.pdf",
            mediaId = "abc123",
            mediaPage = 3,
            mediaPageCount = 12,
            mediaSize = 987_654L,
        )
        assertEquals(state, roundTrip(state))
    }

    @Test
    fun `state from an older master decodes with video defaults`() {
        // Pre-M1 heartbeat: no media* fields at all.
        val legacy = """{"type":"State","playing":true,"positionMs":500,"masterClockMs":99,"durationMs":1000}"""
        val decoded = SyncJson.decodeFromString(SyncMessage.serializer(), legacy) as SyncMessage.State
        assertEquals("video", decoded.mediaKind)
        assertEquals(null, decoded.mediaTitle)
        assertEquals(null, decoded.mediaId)
        assertEquals(0, decoded.mediaPage)
        assertEquals(0, decoded.mediaPageCount)
        assertEquals(-1L, decoded.mediaSize)
        assertEquals(1f, decoded.speed)
    }

    @Test
    fun `unknown keys from a newer master are ignored`() {
        val future = """{"type":"State","playing":false,"positionMs":0,"masterClockMs":1,"durationMs":0,"mediaKind":"image","somethingNew":42}"""
        val decoded = SyncJson.decodeFromString(SyncMessage.serializer(), future) as SyncMessage.State
        assertEquals("image", decoded.mediaKind)
    }

    @Test
    fun `page commands round trip with their discriminators`() {
        val show = SyncMessage.ShowPage(page = 4)
        val encodedShow = SyncJson.encodeToString(SyncMessage.serializer(), show)
        assertTrue(encodedShow, encodedShow.contains("\"type\":\"ShowPage\""))
        assertEquals(show, roundTrip(show))

        val req = SyncMessage.PageReq(page = 7)
        val encodedReq = SyncJson.encodeToString(SyncMessage.serializer(), req)
        assertTrue(encodedReq, encodedReq.contains("\"type\":\"PageReq\""))
        assertEquals(req, roundTrip(req))
    }
}
