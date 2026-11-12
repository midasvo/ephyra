package dev.jdtech.jellyfin.repository

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaySessionsTest {
    private val item = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val other = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val sessions = PlaySessions()

    @Test
    fun `a transcoded item keeps its play session for the stop report`() {
        sessions.onPlaybackInfo(item, "abc", transcodes = true)

        assertEquals("abc", sessions.sessionOf(item))
        // Still there after a stop: Previous can go back to the stream the player already has.
        assertEquals("abc", sessions.sessionOf(item))
        assertNull(sessions.sessionOf(other))
    }

    @Test
    fun `an item that plays directly has no session to end`() {
        sessions.onPlaybackInfo(item, "abc", transcodes = false)

        assertNull(sessions.sessionOf(item))
    }

    @Test
    fun `a new request replaces the session`() {
        sessions.onPlaybackInfo(item, "abc", transcodes = true)
        sessions.onPlaybackInfo(item, "def", transcodes = true)
        assertEquals("def", sessions.sessionOf(item))

        // The quality cap was lifted and the item plays directly again.
        sessions.onPlaybackInfo(item, "ghi", transcodes = false)
        assertNull(sessions.sessionOf(item))
    }

    @Test
    fun `a missing or blank session is not kept`() {
        sessions.onPlaybackInfo(item, null, transcodes = true)
        assertNull(sessions.sessionOf(item))

        sessions.onPlaybackInfo(item, " ", transcodes = true)
        assertNull(sessions.sessionOf(item))
    }
}
