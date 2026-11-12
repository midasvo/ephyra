package dev.jdtech.jellyfin

import android.content.pm.ActivityInfo
import dev.jdtech.jellyfin.settings.domain.KeepPlayerLandscape

/** Below this many dp a window is compact width (Material window size classes). */
internal const val COMPACT_WIDTH_DP = 600

/**
 * CE (R6, T7, #363): the orientation the player asks for.
 * - Locked controls freeze the current orientation, whatever else holds.
 * - A phone or a folded foldable (the maximum window's shorter side under [COMPACT_WIDTH_DP])
 *   plays in landscape, either way round.
 * - A wider window (a tablet, an unfolded foldable) follows the device, unless "Keep the player in
 *   landscape" is on. That setting is left out in split-screen or a freeform window, in
 *   picture-in-picture and with a fold half-opened (tabletop or book posture, R9): there the
 *   window has to be free to turn. It is also left out where the system ignores the request
 *   ([KeepPlayerLandscape.isSupported]).
 *
 * The fold counts half-opened in either orientation: with landscape forced, a Pixel Fold held in
 * tabletop posture reports its hinge as vertical, because a fold's orientation is relative to the
 * window, so only a horizontal hinge would never release it.
 */
internal fun playerOrientation(
    shorterSideDp: Float,
    keepLandscape: Boolean,
    locked: Boolean,
    multiWindow: Boolean,
    pictureInPicture: Boolean,
    foldHalfOpened: Boolean,
    sdkInt: Int,
    landscapeRefused: Boolean = false,
): Int =
    when {
        locked -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
        shorterSideDp < COMPACT_WIDTH_DP -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        keepLandscape &&
            !landscapeRefused &&
            KeepPlayerLandscape.isSupported(sdkInt) &&
            !multiWindow &&
            !pictureInPicture &&
            !foldHalfOpened -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

/**
 * A window is "clearly smaller" than the maximum window when its area is below this share of it.
 * System bars and a display cutout are insets inside the window, not a smaller window, so they
 * never come near it; a landscape box inside an upright tablet is about half the screen.
 */
internal const val LETTERBOX_AREA_FRACTION = 0.8f

/**
 * CE (#363): whether the system letterboxed the player: it put a landscape-shaped window inside
 * an upright display instead of turning the display. Displays that ignore orientation requests do
 * that (`wm get-ignore-orientation-request`, set on many large screens from Android 12L).
 * Split-screen, freeform and picture-in-picture windows are smaller by design and do not count.
 */
internal fun isLetterboxed(
    windowWidth: Int,
    windowHeight: Int,
    maxWidth: Int,
    maxHeight: Int,
    multiWindow: Boolean,
    pictureInPicture: Boolean,
): Boolean {
    if (multiWindow || pictureInPicture) return false
    val displayUpright = maxHeight > maxWidth
    val landscapeWindow = windowWidth > windowHeight
    val clearlySmaller =
        windowWidth.toLong() * windowHeight <
            LETTERBOX_AREA_FRACTION * maxWidth.toLong() * maxHeight
    return displayUpright && landscapeWindow && clearlySmaller
}

/**
 * CE (#363): whether the player gives up "Keep the player in landscape" for the rest of its
 * session: it asked for landscape because of the setting, and the display answered with a
 * letterboxed box ([isLetterboxed]) rather than by turning. The box is worse than following the
 * device, so the player goes back to [ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED]. The caller
 * latches the answer, so the request cannot flip back and forth.
 */
internal fun refusesLandscape(
    requestedOrientation: Int,
    keepLandscape: Boolean,
    shorterSideDp: Float,
    letterboxed: Boolean,
): Boolean =
    keepLandscape &&
        shorterSideDp >= COMPACT_WIDTH_DP &&
        requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE &&
        letterboxed
