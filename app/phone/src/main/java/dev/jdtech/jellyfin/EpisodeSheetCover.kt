package dev.jdtech.jellyfin

import android.graphics.Rect

/**
 * CE (#456): whether a control at [view] lies under the episode sheet at [sheet], both in window
 * coordinates. The sheet is 92 % opaque (design §9), so a control under it shows through. A
 * control that only touches the sheet's edge is not under it.
 */
internal fun isUnderEpisodeSheet(view: Rect, sheet: Rect): Boolean = Rect.intersects(view, sheet)

/**
 * CE (#523): the controls the episode sheet hides, of [controls] with their bounds in the window:
 * every one the sheet at [sheet] covers or cuts, none while the sheet is closed ([sheet] null).
 */
internal fun <T> controlsUnderEpisodeSheet(controls: Map<T, Rect>, sheet: Rect?): Set<T> =
    if (sheet == null) emptySet() else controls.filterValues { isUnderEpisodeSheet(it, sheet) }.keys
