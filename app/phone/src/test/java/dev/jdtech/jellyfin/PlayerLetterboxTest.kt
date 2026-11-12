package dev.jdtech.jellyfin

import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CE (#363): a display that ignores orientation requests answers "Keep the player in landscape"
 * with a landscape box inside the upright screen; the player then gives the setting up.
 */
class PlayerLetterboxTest {
    // The 8" tablet AVD held upright: 640 x 1024 px, and the box it letterboxed the player into.
    private fun letterboxed(
        width: Int = 640,
        height: Int = 480,
        maxWidth: Int = 640,
        maxHeight: Int = 1024,
        multiWindow: Boolean = false,
        pictureInPicture: Boolean = false,
    ) = isLetterboxed(width, height, maxWidth, maxHeight, multiWindow, pictureInPicture)

    @Test
    fun `a landscape box in an upright display is letterboxed`() {
        assertTrue(letterboxed())
    }

    @Test
    fun `a full-screen window is not, upright or not`() {
        assertFalse(letterboxed(width = 640, height = 1024))
        assertFalse(letterboxed(width = 1024, height = 640, maxWidth = 1024, maxHeight = 640))
    }

    @Test
    fun `system bars or a cutout taken off the window are not letterboxing`() {
        // Even if a window came without the status and navigation bars, or a cutout strip.
        assertFalse(letterboxed(width = 640, height = 1024 - 24 - 48))
        assertFalse(letterboxed(width = 1024 - 48, height = 640, maxWidth = 1024, maxHeight = 640))
    }

    @Test
    fun `a display already in landscape is not letterboxing the player`() {
        assertFalse(letterboxed(width = 900, height = 500, maxWidth = 1024, maxHeight = 640))
    }

    @Test
    fun `split-screen, freeform and picture-in-picture are not letterboxing`() {
        assertFalse(letterboxed(multiWindow = true))
        assertFalse(letterboxed(pictureInPicture = true))
        assertFalse(letterboxed(multiWindow = true, pictureInPicture = true))
    }

    @Test
    fun `the player gives up the setting only when it asked for landscape because of it`() {
        assertTrue(refusesLandscape(SCREEN_ORIENTATION_SENSOR_LANDSCAPE, true, 640f, true))
        assertFalse(refusesLandscape(SCREEN_ORIENTATION_SENSOR_LANDSCAPE, false, 640f, true))
        assertFalse(refusesLandscape(SCREEN_ORIENTATION_UNSPECIFIED, true, 640f, true))
        assertFalse(refusesLandscape(SCREEN_ORIENTATION_SENSOR_LANDSCAPE, true, 640f, false))
        // A phone's landscape is not the setting's.
        assertFalse(refusesLandscape(SCREEN_ORIENTATION_SENSOR_LANDSCAPE, true, 412f, true))
    }

    private fun orientation(shorterSideDp: Float) =
        playerOrientation(
            shorterSideDp = shorterSideDp,
            keepLandscape = true,
            locked = false,
            multiWindow = false,
            pictureInPicture = false,
            foldHalfOpened = false,
            sdkInt = 34,
            landscapeRefused = true,
        )

    @Test
    fun `once refused, a wide window follows the device with the setting on`() {
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, orientation(640f))
        // A phone keeps its landscape.
        assertEquals(SCREEN_ORIENTATION_SENSOR_LANDSCAPE, orientation(412f))
    }
}
