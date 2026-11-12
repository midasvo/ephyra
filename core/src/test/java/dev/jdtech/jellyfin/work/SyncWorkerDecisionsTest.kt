package dev.jdtech.jellyfin.work

import dev.jdtech.jellyfin.models.FindroidUserDataDto
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CE (#552): when the user data sync retries, and when it lets a row be. */
class SyncWorkerDecisionsTest {
    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000001")

    private fun row(item: Int, positionTicks: Long = 100L) =
        FindroidUserDataDto(
            userId = userId,
            itemId = UUID(0L, item.toLong()),
            played = false,
            favorite = false,
            playbackPositionTicks = positionTicks,
            toBeSynced = true,
        )

    @Test
    fun `an item the server does not have is dropped`() {
        assertEquals(AfterStatus.DROP, afterStatus(404))
    }

    @Test
    fun `a refusal is not retried on a timer`() {
        for (status in listOf(400, 401, 403)) assertEquals(AfterStatus.LEAVE, afterStatus(status))
    }

    @Test
    fun `a server error is retried`() {
        for (status in listOf(500, 502, 503)) assertEquals(AfterStatus.RETRY, afterStatus(status))
    }

    @Test
    fun `a session the server still has playing is ended before its row is sent`() {
        val item = UUID(0L, 1L)
        assertTrue(endsStaleSession(rowItemId = item, leftPlayingOnServer = item, playingHere = null))
        assertTrue(endsStaleSession(item, leftPlayingOnServer = item, playingHere = UUID(0L, 2L)))
    }

    @Test
    fun `no session, or one of another item, is left alone`() {
        val item = UUID(0L, 1L)
        assertFalse(endsStaleSession(item, leftPlayingOnServer = null, playingHere = null))
        assertFalse(endsStaleSession(item, leftPlayingOnServer = UUID(0L, 2L), playingHere = null))
    }

    @Test
    fun `an item that is open in the player here keeps its session`() {
        val item = UUID(0L, 1L)
        assertFalse(endsStaleSession(item, leftPlayingOnServer = item, playingHere = item))
    }

    @Test
    fun `nothing left marked needs no other run`() {
        assertFalse(needsAnotherRun(sent = listOf(row(1)), stillMarked = emptyList()))
    }

    @Test
    fun `a row that failed as it was sent does not ask for another run by itself`() {
        assertFalse(needsAnotherRun(sent = listOf(row(1), row(2)), stillMarked = listOf(row(2))))
    }

    @Test
    fun `a row that changed during the run needs another run`() {
        assertTrue(needsAnotherRun(sent = listOf(row(1)), stillMarked = listOf(row(1, 200L))))
    }

    @Test
    fun `a row marked during the run needs another run`() {
        assertTrue(needsAnotherRun(sent = listOf(row(1)), stillMarked = listOf(row(2))))
    }
}
