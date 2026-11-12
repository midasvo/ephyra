package dev.jdtech.jellyfin

import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
import org.junit.Assert.assertEquals
import org.junit.Test

/** CE (R6, #363): which orientation the player asks for. */
class PlayerOrientationTest {
    private val phone = 412f
    private val tablet = 640f

    private fun orientation(
        shorterSideDp: Float = tablet,
        keepLandscape: Boolean = false,
        locked: Boolean = false,
        multiWindow: Boolean = false,
        pictureInPicture: Boolean = false,
        foldHalfOpened: Boolean = false,
        sdkInt: Int = 34,
    ) =
        playerOrientation(
            shorterSideDp = shorterSideDp,
            keepLandscape = keepLandscape,
            locked = locked,
            multiWindow = multiWindow,
            pictureInPicture = pictureInPicture,
            foldHalfOpened = foldHalfOpened,
            sdkInt = sdkInt,
        )

    @Test
    fun `a phone plays in landscape whatever the setting`() {
        assertEquals(SCREEN_ORIENTATION_SENSOR_LANDSCAPE, orientation(phone))
        assertEquals(SCREEN_ORIENTATION_SENSOR_LANDSCAPE, orientation(phone, keepLandscape = true))
        assertEquals(SCREEN_ORIENTATION_SENSOR_LANDSCAPE, orientation(phone, sdkInt = 36))
    }

    @Test
    fun `a phone keeps today's request in split-screen and picture-in-picture`() {
        assertEquals(SCREEN_ORIENTATION_SENSOR_LANDSCAPE, orientation(phone, multiWindow = true))
        assertEquals(
            SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            orientation(phone, multiWindow = true, pictureInPicture = true),
        )
    }

    @Test
    fun `the compact bound is 600 dp`() {
        assertEquals(SCREEN_ORIENTATION_SENSOR_LANDSCAPE, orientation(599.9f))
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, orientation(600f))
    }

    @Test
    fun `a wide window follows the device with the setting off`() {
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, orientation())
    }

    @Test
    fun `a wide window plays in landscape with the setting on`() {
        assertEquals(SCREEN_ORIENTATION_SENSOR_LANDSCAPE, orientation(keepLandscape = true))
        assertEquals(
            SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            orientation(keepLandscape = true, sdkInt = 35),
        )
    }

    @Test
    fun `the setting is left out in split-screen, picture-in-picture and on a half-opened fold`() {
        assertEquals(
            SCREEN_ORIENTATION_UNSPECIFIED,
            orientation(keepLandscape = true, multiWindow = true),
        )
        // Picture-in-picture is also multi-window on Android; each one alone releases it.
        assertEquals(
            SCREEN_ORIENTATION_UNSPECIFIED,
            orientation(keepLandscape = true, pictureInPicture = true),
        )
        assertEquals(
            SCREEN_ORIENTATION_UNSPECIFIED,
            orientation(keepLandscape = true, multiWindow = true, pictureInPicture = true),
        )
        assertEquals(
            SCREEN_ORIENTATION_UNSPECIFIED,
            orientation(keepLandscape = true, foldHalfOpened = true),
        )
    }

    @Test
    fun `the setting does nothing where Android 16 and later ignore the request`() {
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, orientation(keepLandscape = true, sdkInt = 36))
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, orientation(keepLandscape = true, sdkInt = 37))
    }

    @Test
    fun `locked controls freeze the current orientation in every case`() {
        for (shorterSideDp in listOf(phone, tablet)) {
            for (keepLandscape in listOf(false, true)) {
                for (multiWindow in listOf(false, true)) {
                    for (pictureInPicture in listOf(false, true)) {
                        for (foldHalfOpened in listOf(false, true)) {
                            assertEquals(
                                SCREEN_ORIENTATION_LOCKED,
                                orientation(
                                    shorterSideDp,
                                    keepLandscape,
                                    locked = true,
                                    multiWindow = multiWindow,
                                    pictureInPicture = pictureInPicture,
                                    foldHalfOpened = foldHalfOpened,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
