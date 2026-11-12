package dev.jdtech.jellyfin

/**
 * CE: how far the player's top row of buttons scrolls, when the window is too narrow for every
 * button: the width of the buttons beyond the visible part, 0 when they all fit.
 */
internal fun topRowMaxScroll(contentWidth: Int, viewportWidth: Int): Int =
    (contentWidth - viewportWidth).coerceAtLeast(0)

/**
 * CE: whether the top row stays scrolled to its end, so the last buttons (audio, subtitles, the
 * episodes) are the ones on screen and lock and picture-in-picture are the ones scrolled away. The
 * row follows its end until the user scrolls it away from there, by touch or by moving focus.
 */
internal fun topRowFollowsEnd(scrollX: Int, maxScroll: Int): Boolean = scrollX >= maxScroll
