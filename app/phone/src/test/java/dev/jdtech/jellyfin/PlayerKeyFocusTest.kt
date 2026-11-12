package dev.jdtech.jellyfin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #448: Space on a hardware keyboard closed the player. Android had put focus on the back button by
 * itself, and Space pressed it; with the controls locked, Space pressed the focused Unlock or skip.
 */
class PlayerKeyFocusTest {
    @Test
    fun `focus Android gave a control does not take Space`() {
        assertFalse(
            keyGoesToFocusedControl(
                focusChosenByKeyboard = false,
                focusOnControl = true,
                controlsShowing = true,
            )
        )
        assertEquals(
            SpaceKeyAction.PLAY_PAUSE,
            spaceKeyAction(locked = false, goesToFocusedControl = false),
        )
    }

    @Test
    fun `a control the user tabbed to takes Space`() {
        assertTrue(
            keyGoesToFocusedControl(
                focusChosenByKeyboard = true,
                focusOnControl = true,
                controlsShowing = true,
            )
        )
        assertEquals(
            SpaceKeyAction.FOCUSED_CONTROL,
            spaceKeyAction(locked = false, goesToFocusedControl = true),
        )
    }

    @Test
    fun `hidden controls never take Space`() {
        assertFalse(
            keyGoesToFocusedControl(
                focusChosenByKeyboard = true,
                focusOnControl = true,
                controlsShowing = false,
            )
        )
        assertFalse(
            keyGoesToFocusedControl(
                focusChosenByKeyboard = true,
                focusOnControl = false,
                controlsShowing = true,
            )
        )
    }

    @Test
    fun `locked Space does nothing unless the user moved to Unlock`() {
        assertEquals(
            SpaceKeyAction.NOTHING,
            spaceKeyAction(locked = true, goesToFocusedControl = false),
        )
        assertEquals(
            SpaceKeyAction.FOCUSED_CONTROL,
            spaceKeyAction(locked = true, goesToFocusedControl = true),
        )
    }

    /** #588: Up and Down changed the volume instead of moving through the episode sheet. */
    @Test
    fun `a row of the open episode sheet takes the arrows and Space`() {
        val onRow =
            focusIsOnControl(
                insideControls = false,
                insideEpisodeSheet = true,
                episodeSheetOpen = true,
            )
        assertTrue(onRow)
        assertTrue(
            keyGoesToFocusedControl(
                focusChosenByKeyboard = true,
                focusOnControl = onRow,
                controlsShowing = true,
            )
        )
    }

    @Test
    fun `with the sheet closed or focus on the player the arrows stay volume and seek`() {
        // Focus left behind in a sheet that has closed.
        assertFalse(
            focusIsOnControl(
                insideControls = false,
                insideEpisodeSheet = true,
                episodeSheetOpen = false,
            )
        )
        // The sheet opened by touch: focus is on the player view.
        assertFalse(
            focusIsOnControl(
                insideControls = false,
                insideEpisodeSheet = false,
                episodeSheetOpen = true,
            )
        )
        assertTrue(
            focusIsOnControl(
                insideControls = true,
                insideEpisodeSheet = false,
                episodeSheetOpen = false,
            )
        )
    }

    /** #588: the first open found no row to focus, and nothing tried again. */
    @Test
    fun `the sheet takes focus for its row only when opened from the keyboard and still wanted`() {
        assertTrue(episodeSheetTakesFocus(requested = true, sheetOpen = true, focusParked = true))
        // Opened by touch.
        assertFalse(episodeSheetTakesFocus(requested = false, sheetOpen = true, focusParked = true))
        // Closed while the season loaded.
        assertFalse(episodeSheetTakesFocus(requested = true, sheetOpen = false, focusParked = true))
        // The user tabbed to another control while the season loaded.
        assertFalse(episodeSheetTakesFocus(requested = true, sheetOpen = true, focusParked = false))
    }
}
