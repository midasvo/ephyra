package dev.jdtech.jellyfin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #477: with background audio on, the player screen gave back the playWhenReady saved when it was
 * left, although leaving had not paused the player. A pause made in the meantime (the
 * still-watching prompt, the sleep timer, the notification) was undone on return.
 */
class PlayerResumeTest {
    @Test
    fun `a pause from leaving the screen is undone`() {
        assertEquals(true, playWhenReadyToRestore(pausedOnLeave = true, saved = true))
        assertEquals(false, playWhenReadyToRestore(pausedOnLeave = true, saved = false))
    }

    @Test
    fun `a player that played on in the background is left as it is`() {
        assertNull(playWhenReadyToRestore(pausedOnLeave = false, saved = true))
        assertNull(playWhenReadyToRestore(pausedOnLeave = false, saved = false))
    }
}
