package dev.jdtech.jellyfin.presentation.utils

import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import dev.jdtech.jellyfin.core.presentation.ephyra.EphyraMotion

/**
 * A [LazyListState] for a list whose items arrive or change order while it is on screen, such as
 * the rows of Home. A lazy list keeps its first visible item in place when another lands before it,
 * so the new first item would sit out of view, half in the content padding (#393). While the list
 * is at its start, a change of [firstKey] scrolls it back to the new first item; a list the user
 * has scrolled stays where it is.
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

/**
 * The item animation for the cards of a list held by [rememberLazyListStateAtStart] (#640). A card
 * that joins the row fades in where it lands, and the cards it pushes aside slide over to make
 * room, instead of the row snapping to its new order (VISION §6: list item appear over
 * `durationMedium`). A card that leaves fades out over `durationShort`. Cards that are there on the
 * first layout, or that scroll into view, don't animate.
 */
fun LazyItemScope.animateRowItem(): Modifier =
    Modifier.animateItem(
        fadeInSpec = tween(EphyraMotion.DURATION_MEDIUM, easing = EphyraMotion.EasingEnter),
        placementSpec = tween(EphyraMotion.DURATION_MEDIUM, easing = EphyraMotion.EasingStandard),
        fadeOutSpec = tween(EphyraMotion.DURATION_SHORT, easing = EphyraMotion.EasingExit),
    )
