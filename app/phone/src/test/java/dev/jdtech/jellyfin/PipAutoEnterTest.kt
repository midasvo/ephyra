package dev.jdtech.jellyfin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #447: the PiP button passes its params to enterPictureInPictureMode, and the system keeps them.
 * With auto-enter on in those params, Home entered PiP for the rest of the session although the
 * PiP home gesture was off.
 */
class PipAutoEnterTest {
    @Test
    fun `gesture off never auto-enters`() {
        assertFalse(pipAutoEnterEnabled(gestureEnabled = false, isPlaying = true))
        assertFalse(pipAutoEnterEnabled(gestureEnabled = false, isPlaying = false))
    }

    @Test
    fun `gesture on auto-enters only while playing`() {
        assertTrue(pipAutoEnterEnabled(gestureEnabled = true, isPlaying = true))
        assertFalse(pipAutoEnterEnabled(gestureEnabled = true, isPlaying = false))
    }

    // #480: the params kept auto-enter on after the gesture was turned off with the player open.
    @Test
    fun `params follow a changed auto-enter`() {
        assertTrue(pipParamsNeedUpdate(autoEnterSet = true, autoEnter = false, false))
        assertTrue(pipParamsNeedUpdate(autoEnterSet = false, autoEnter = true, false))
    }

    @Test
    fun `unchanged params are not built again unless asked`() {
        assertFalse(pipParamsNeedUpdate(autoEnterSet = false, autoEnter = false, false))
        assertFalse(pipParamsNeedUpdate(autoEnterSet = true, autoEnter = true, false))
        assertTrue(pipParamsNeedUpdate(autoEnterSet = true, autoEnter = true, refreshAnyway = true))
    }
}
