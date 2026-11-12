package dev.jdtech.jellyfin

/** What Space does in the phone player (#448). */
internal enum class SpaceKeyAction {
    PLAY_PAUSE,
    /** Passed on: the focused control is pressed, as Android does for a confirm key. */
    FOCUSED_CONTROL,
    NOTHING,
}

/**
 * CE (#448): whether Space and the arrows go to the focused control instead of acting as player
 * shortcuts. Only when the user put focus on that control with Tab or the arrows: Android also
 * focuses a control by itself, when the first key leaves touch mode or the focused view is hidden,
 * and Space on that control pressed it unseen (the back button closed the player).
 */
internal fun keyGoesToFocusedControl(
    focusChosenByKeyboard: Boolean,
    focusOnControl: Boolean,
    controlsShowing: Boolean,
): Boolean = focusChosenByKeyboard && focusOnControl && controlsShowing

/**
 * CE (#588): whether focus is on something a key can go to. The episode sheet lies outside the
 * player view, so focus on one of its rows did not count: Up and Down changed the volume instead
 * of moving through the episodes. Only while the sheet is open: closed, it holds no focus a key
 * should go to.
 */
internal fun focusIsOnControl(
    insideControls: Boolean,
    insideEpisodeSheet: Boolean,
    episodeSheetOpen: Boolean,
): Boolean = insideControls || (insideEpisodeSheet && episodeSheetOpen)

/**
 * CE (#588): whether the episode sheet moves focus to the playing episode's row. It was asked to
 * (opened from the keyboard), is still open, and focus is still where opening it left it: the
 * rows can arrive seconds later, and by then the user may have closed the sheet or moved on.
 */
internal fun episodeSheetTakesFocus(
    requested: Boolean,
    sheetOpen: Boolean,
    focusParked: Boolean,
): Boolean = requested && sheetOpen && focusParked

/** CE (#448): with the controls locked, Space only presses an Unlock or skip the user moved to. */
internal fun spaceKeyAction(locked: Boolean, goesToFocusedControl: Boolean): SpaceKeyAction =
    when {
        goesToFocusedControl -> SpaceKeyAction.FOCUSED_CONTROL
        locked -> SpaceKeyAction.NOTHING
        else -> SpaceKeyAction.PLAY_PAUSE
    }
