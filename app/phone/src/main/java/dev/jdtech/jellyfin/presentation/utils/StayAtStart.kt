package dev.jdtech.jellyfin.presentation.utils

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * A [LazyListState] for a list whose items arrive or change order while it is on screen, such as
 * the rows of Home. A lazy list keeps its first visible item in place when another lands before
 * it, so the new first item would sit out of view, half in the content padding (#393). While the
 * list is at its start, a change of [firstKey] scrolls it back to the new first item; a list the
 * user has scrolled stays where it is.
 */
@Composable
fun rememberLazyListStateAtStart(firstKey: Any?): LazyListState {
    val state = rememberLazyListState()
    DisposableEffect(firstKey) {
        if (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0) {
            state.requestScrollToItem(0)
        }
        onDispose {}
    }
    return state
}
