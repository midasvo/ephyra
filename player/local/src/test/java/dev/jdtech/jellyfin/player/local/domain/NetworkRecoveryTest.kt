package dev.jdtech.jellyfin.player.local.domain

import androidx.media3.common.PlaybackException
import dev.jdtech.jellyfin.player.local.domain.NetworkRecovery.Check
import dev.jdtech.jellyfin.player.local.domain.NetworkRecovery.OnError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #450: after the network came back, ExoPlayer stayed stopped until Play was pressed. The player
 * now waits for the network after a network error and loads again by itself, for ten minutes.
 */
class NetworkRecoveryTest {

    private var now = 0L
    private val recovery = NetworkRecovery { now }

    private val connectionFailed = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED

    @Test
    fun `a network error on a loaded item waits for the network`() {
        assertEquals(OnError.WAIT, recovery.onError(connectionFailed, "a", canPrepare = true))
        assertTrue(recovery.isWaiting)
        assertEquals(Check.RETRY, recovery.check("a"))
    }

    @Test
    fun `a timeout is a network error too`() {
        val timeout = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        assertEquals(OnError.WAIT, recovery.onError(timeout, "a", canPrepare = true))
    }

    @Test
    fun `other errors show the error`() {
        for (code in
            listOf(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            )) {
            assertEquals(OnError.SHOW_ERROR, recovery.onError(code, "a", canPrepare = true))
            assertFalse(recovery.isWaiting)
        }
    }

    @Test
    fun `nothing loaded shows the error`() {
        assertEquals(OnError.SHOW_ERROR, recovery.onError(connectionFailed, null, canPrepare = true))
    }

    @Test
    fun `a player that cannot load again (mpv) shows the error`() {
        assertEquals(
            OnError.SHOW_ERROR,
            recovery.onError(connectionFailed, "a", canPrepare = false),
        )
        assertEquals(Check.STOPPED, recovery.check("a"))
    }

    @Test
    fun `a failed retry keeps waiting from the first error`() {
        recovery.onError(connectionFailed, "a", canPrepare = true)
        now = 5 * 60_000L
        assertEquals(OnError.KEEP_WAITING, recovery.onError(connectionFailed, "a", canPrepare = true))
        now = NetworkRecovery.GIVE_UP_MS - 1
        assertEquals(Check.RETRY, recovery.check("a"))
    }

    @Test
    fun `after ten minutes it gives up`() {
        recovery.onError(connectionFailed, "a", canPrepare = true)
        now = NetworkRecovery.GIVE_UP_MS
        assertEquals(Check.GIVE_UP, recovery.check("a"))
        assertFalse(recovery.isWaiting)
        assertEquals(Check.STOPPED, recovery.check("a"))
    }

    @Test
    fun `a retry failing after ten minutes gives up`() {
        recovery.onError(connectionFailed, "a", canPrepare = true)
        now = NetworkRecovery.GIVE_UP_MS + 1
        assertEquals(OnError.GIVE_UP, recovery.onError(connectionFailed, "a", canPrepare = true))
        assertFalse(recovery.isWaiting)
    }

    @Test
    fun `another error while waiting ends the wait and is shown`() {
        recovery.onError(connectionFailed, "a", canPrepare = true)
        assertEquals(
            OnError.GIVE_UP,
            recovery.onError(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                "a",
                canPrepare = true,
            ),
        )
        assertFalse(recovery.isWaiting)
    }

    @Test
    fun `nothing loads again after the item changed`() {
        recovery.onError(connectionFailed, "a", canPrepare = true)
        assertEquals(Check.STOPPED, recovery.check("b"))
        assertFalse(recovery.isWaiting)
        assertEquals(Check.STOPPED, recovery.check("a"))
    }

    @Test
    fun `nothing loads again after playback picked up or the player closed`() {
        recovery.onError(connectionFailed, "a", canPrepare = true)
        recovery.stop()
        assertEquals(Check.STOPPED, recovery.check("a"))
    }

    @Test
    fun `a network error on a new item starts a new wait`() {
        recovery.onError(connectionFailed, "a", canPrepare = true)
        now = 9 * 60_000L
        assertEquals(OnError.WAIT, recovery.onError(connectionFailed, "b", canPrepare = true))
        now = 9 * 60_000L + NetworkRecovery.GIVE_UP_MS - 1
        assertEquals(Check.RETRY, recovery.check("b"))
    }
}
