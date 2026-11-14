package dev.jdtech.jellyfin.ce.presentation.player

import dev.jdtech.jellyfin.ce.presentation.player.TvResumeOnReturn.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #462: after Home (or standby) and back, the TV player sat paused with nothing on screen. It now
 * plays again if it was playing, or shows the controls if it was paused, unless something else took
 * over the play state while the app was away.
 */
class TvResumeOnReturnTest {

    private val resume = TvResumeOnReturn()

    /** ON_PAUSE, then the pause the screen makes itself. */
    private fun leave(playing: Boolean) {
        resume.onPause(playWhenReady = playing)
        if (playing) resume.onPlayWhenReadyChanged(false)
    }

    @Test
    fun `playing before Home plays again on return`() {
        leave(playing = true)

        assertEquals(Action.PLAY, resume.onResume(blocked = false))
    }

    @Test
    fun `paused before Home shows the controls on return`() {
        leave(playing = false)

        assertEquals(Action.SHOW_CONTROLS, resume.onResume(blocked = false))
    }

    @Test
    fun `a second ON_PAUSE does not overwrite the state from the first`() {
        leave(playing = true)
        resume.onPause(playWhenReady = false) // paused by the screen itself by now

        assertEquals(Action.PLAY, resume.onResume(blocked = false))
    }

    @Test
    fun `play and pause from the remote while away cancel the resume`() {
        leave(playing = true)
        resume.onPlayWhenReadyChanged(true) // media key: play in the background
        resume.onPlayWhenReadyChanged(false) // and pause again

        // Not played again; the controls show (when it is not playing) so the pause is visible.
        assertEquals(Action.SHOW_CONTROLS, resume.onResume(blocked = false))
    }

    @Test
    fun `the sleep timer firing while away cancels the resume`() {
        leave(playing = true)
        resume.cancel()

        assertEquals(Action.SHOW_CONTROLS, resume.onResume(blocked = false))
    }

    @Test
    fun `no playback starts under the still-watching prompt or an error`() {
        leave(playing = true)

        assertEquals(Action.NONE, resume.onResume(blocked = true))
    }

    @Test
    fun `a resume without a pause does nothing`() {
        assertEquals(Action.NONE, resume.onResume(blocked = false))
    }

    @Test
    fun `play state changes while in front are ignored`() {
        resume.onPlayWhenReadyChanged(false)
        resume.cancel()
        leave(playing = true)

        assertEquals(Action.PLAY, resume.onResume(blocked = false))
    }

    @Test
    fun `each return starts over`() {
        leave(playing = true)
        resume.onResume(blocked = false)
        leave(playing = false)

        assertEquals(Action.SHOW_CONTROLS, resume.onResume(blocked = false))
    }
}
