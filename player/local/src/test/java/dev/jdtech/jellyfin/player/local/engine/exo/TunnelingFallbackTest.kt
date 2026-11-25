package dev.jdtech.jellyfin.player.local.engine.exo

import androidx.media3.common.PlaybackException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** #924: when [TunnelingFallback] turns tunneling off, and when it turns it back on. */
class TunnelingFallbackTest {

    private val audioTrackInitFailed = PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED

    /** The track selector's tunneling switch, and every value written to it. */
    private var tunneling = true
    private val writes = mutableListOf<Boolean>()
    private val fallback =
        TunnelingFallback(
            tunnelingEnabled = { tunneling },
            setTunnelingEnabled = {
                tunneling = it
                writes += it
            },
        )

    @Test
    fun `an audio track that fails to open with tunneling on retries without tunneling`() {
        assertTrue(fallback.onPlayerError(audioTrackInitFailed, "episode-1"))
        assertFalse(tunneling)
        assertEquals(listOf(false), writes)
    }

    @Test
    fun `the same failure without tunneling takes the normal error path`() {
        assertTrue(fallback.onPlayerError(audioTrackInitFailed, "episode-1"))

        assertFalse(fallback.onPlayerError(audioTrackInitFailed, "episode-1"))
        assertEquals(listOf(false), writes)
    }

    @Test
    fun `no retry when tunneling was off already`() {
        tunneling = false

        assertFalse(fallback.onPlayerError(audioTrackInitFailed, "episode-1"))
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `other errors leave tunneling on`() {
        for (code in
            listOf(
                PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_UNSPECIFIED,
            )) {
            assertFalse(fallback.onPlayerError(code, "episode-1"), "error code $code")
        }
        assertTrue(tunneling)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `tunneling stays off when the same item comes back after a switch`() {
        fallback.onPlayerError(audioTrackInitFailed, "episode-1")

        // A quality or audio switch replaces the playlist and reports the same item again.
        fallback.onMediaItemTransition("episode-1")

        assertFalse(tunneling)
        assertEquals(listOf(false), writes)
    }

    @Test
    fun `the next item gets tunneling back`() {
        fallback.onPlayerError(audioTrackInitFailed, "episode-1")

        fallback.onMediaItemTransition("episode-2")

        assertTrue(tunneling)
        assertEquals(listOf(false, true), writes)
    }

    @Test
    fun `the next item may fall back again once`() {
        fallback.onPlayerError(audioTrackInitFailed, "episode-1")
        fallback.onMediaItemTransition("episode-2")

        assertTrue(fallback.onPlayerError(audioTrackInitFailed, "episode-2"))
        assertFalse(fallback.onPlayerError(audioTrackInitFailed, "episode-2"))
        assertEquals(listOf(false, true, false), writes)
    }

    @Test
    fun `transitions without a fallback leave the switch alone`() {
        fallback.onMediaItemTransition("episode-1")
        fallback.onMediaItemTransition("episode-2")

        assertTrue(writes.isEmpty())
    }
}
