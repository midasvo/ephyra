package dev.jdtech.jellyfin.ce.ui.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged

/**
 * CE (#660): an element that can leave the screen while it has focus. Restart does when a reload
 * finds the item no longer in progress (the TV woke on a page that was out of date, and the item
 * was finished on another device). Focus leaves with it and nothing has focus, so the remote does
 * nothing. Mark the element with [handsOffFocus] and call [HandOffFocusWhenGone] next to it.
 */
@Stable
class FocusHandOff {
    /** A plain field, so a focus change recomposes nothing. Read when the element leaves. */
    internal var focused = false
}

@Composable fun rememberFocusHandOff(): FocusHandOff = remember { FocusHandOff() }

fun Modifier.handsOffFocus(handOff: FocusHandOff): Modifier = onFocusChanged {
    handOff.focused = it.hasFocus
}

/**
 * Runs [onGone], which focuses the element that takes over, when the element marked with [handOff]
 * is no longer [shown] and had focus. Focus that is elsewhere (another button, a dialog) stays
 * there. Decided in composition: by the time effects run, the element has left and its focus is
 * gone.
 */
@Composable
fun HandOffFocusWhenGone(handOff: FocusHandOff, shown: Boolean, onGone: () -> Unit) {
    val leftWithFocus = !shown && handOff.focused
    val currentOnGone by rememberUpdatedState(onGone)
    LaunchedEffect(leftWithFocus) {
        if (!leftWithFocus) return@LaunchedEffect
        handOff.focused = false
        currentOnGone()
    }
}
