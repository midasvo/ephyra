package dev.jdtech.jellyfin.ce.presentation.player

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #486: the TV player showed nothing while buffering, on either backend. It now shows a spinner
 * while the player buffers or an item has not loaded yet, unless something else is on screen.
 */
class TvBufferingIndicatorTest {

    private fun shown(
        state: Int = Player.STATE_READY,
        fileLoaded: Boolean = true,
        hasError: Boolean = false,
        pendingSeek: Boolean = false,
        stillWatching: Boolean = false,
    ) = bufferingShown(state, fileLoaded, hasError, pendingSeek, stillWatching)

    @Test
    fun `buffering shows it`() {
        assertTrue(shown(state = Player.STATE_BUFFERING))
    }

    @Test
    fun `ready playback does not show it`() {
        assertFalse(shown(state = Player.STATE_READY))
    }

    @Test
    fun `an item that has not loaded shows it, also before the player buffers`() {
        assertTrue(shown(state = Player.STATE_IDLE, fileLoaded = false))
        assertTrue(shown(state = Player.STATE_BUFFERING, fileLoaded = false))
    }

    @Test
    fun `the error dialog hides it`() {
        assertFalse(shown(state = Player.STATE_BUFFERING, hasError = true))
        assertFalse(shown(state = Player.STATE_IDLE, fileLoaded = false, hasError = true))
    }

    @Test
    fun `a pending seek hides it, so the trickplay thumbnail stays clear`() {
        assertFalse(shown(state = Player.STATE_BUFFERING, pendingSeek = true))
    }

    @Test
    fun `the still-watching prompt hides it`() {
        assertFalse(shown(state = Player.STATE_BUFFERING, stillWatching = true))
    }

    @Test
    fun `an ended item does not show it`() {
        assertFalse(shown(state = Player.STATE_ENDED))
    }

    @Test
    fun `while buffering to play, the button shows Pause, so a press pauses`() {
        assertFalse(showsPlayButton(playWhenReady = true, playbackState = Player.STATE_BUFFERING))
    }

    @Test
    fun `playing shows Pause, paused shows Play`() {
        assertFalse(showsPlayButton(playWhenReady = true, playbackState = Player.STATE_READY))
        assertTrue(showsPlayButton(playWhenReady = false, playbackState = Player.STATE_READY))
        assertTrue(showsPlayButton(playWhenReady = false, playbackState = Player.STATE_BUFFERING))
    }

    @Test
    fun `an idle or ended player shows Play`() {
        assertTrue(showsPlayButton(playWhenReady = true, playbackState = Player.STATE_IDLE))
        assertTrue(showsPlayButton(playWhenReady = true, playbackState = Player.STATE_ENDED))
    }
}
