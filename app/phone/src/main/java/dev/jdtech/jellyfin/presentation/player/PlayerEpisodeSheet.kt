package dev.jdtech.jellyfin.presentation.player

import android.graphics.Rect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.ephyra.EphyraMotion
import dev.jdtech.jellyfin.film.presentation.season.SeasonState
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.presentation.film.CompactEpisodeRow
import dev.jdtech.jellyfin.presentation.film.components.groupedItemShape
import java.util.UUID
import kotlin.math.roundToInt

/**
 * CE (R10, design §9): the player's episode sheet at expanded width. The current season's
 * episodes in a side sheet over the right 30 % of the window (320 to 420 dp), the playing one
 * highlighted as the selected row of the Season list-detail. A tap anywhere outside the sheet
 * calls [onDismiss]; [onClosed] runs once the sheet has gone, so the host can hide its view.
 * [onBoundsChanged] gets the bounds the open sheet takes in the window (#456).
 *
 * CE (#588): with [wantsFocus] (the sheet was opened from the keyboard) focus moves to the playing
 * episode's row as soon as the rows are there, which on the first open is after the season has
 * loaded. [mayTakeFocus] is asked right before, and [onFocusSettled] hears whether the row got it.
 */
@Composable
fun PlayerEpisodeSheet(
    visibleState: MutableTransitionState<Boolean>,
    state: SeasonState,
    seasonNumber: Int?,
    currentEpisodeId: UUID?,
    onEpisodeClick: (FindroidEpisode) -> Unit,
    onDismiss: () -> Unit,
    onClosed: () -> Unit,
    onBoundsChanged: (Rect) -> Unit = {},
    wantsFocus: Boolean = false,
    mayTakeFocus: () -> Boolean = { true },
    onFocusSettled: (Boolean) -> Unit = {},
) {
    val currentOnClosed by rememberUpdatedState(onClosed)
    LaunchedEffect(visibleState) {
        snapshotFlow { visibleState.isIdle && !visibleState.currentState }
            .collect { closed -> if (closed) currentOnClosed() }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val sheetWidth = (maxWidth * 0.3f).coerceIn(320.dp, 420.dp)

        // CE (#456): where the sheet ends up, without its slide, so the player can clear the
        // controls under it as the sheet opens.
        val currentOnBoundsChanged by rememberUpdatedState(onBoundsChanged)
        Box(
            modifier =
                Modifier.align(Alignment.CenterEnd)
                    .width(sheetWidth)
                    .fillMaxHeight()
                    .onGloballyPositioned { coordinates ->
                        val bounds = coordinates.boundsInWindow()
                        currentOnBoundsChanged(
                            Rect(
                                bounds.left.roundToInt(),
                                bounds.top.roundToInt(),
                                bounds.right.roundToInt(),
                                bounds.bottom.roundToInt(),
                            )
                        )
                    }
        )

        if (visibleState.targetState) {
            Box(
                modifier =
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTapGestures { onDismiss() }
                    }
            )
        }

        AnimatedVisibility(
            visibleState = visibleState,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter =
                slideInHorizontally(
                    tween(EphyraMotion.DURATION_MEDIUM, easing = EphyraMotion.EasingEnter)
                ) { it },
            exit = fadeOut(tween(EphyraMotion.DURATION_SHORT, easing = EphyraMotion.EasingExit)),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
                shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
                modifier = Modifier.width(sheetWidth).fillMaxHeight(),
            ) {
                EpisodeSheetContent(
                    state = state,
                    seasonNumber = seasonNumber,
                    currentEpisodeId = currentEpisodeId,
                    scrollToCurrent = visibleState.targetState,
                    onEpisodeClick = onEpisodeClick,
                    wantsFocus = wantsFocus,
                    mayTakeFocus = mayTakeFocus,
                    onFocusSettled = onFocusSettled,
                )
            }
        }
    }
}

@Composable
private fun EpisodeSheetContent(
    state: SeasonState,
    seasonNumber: Int?,
    currentEpisodeId: UUID?,
    scrollToCurrent: Boolean,
    onEpisodeClick: (FindroidEpisode) -> Unit,
    wantsFocus: Boolean,
    mayTakeFocus: () -> Boolean,
    onFocusSettled: (Boolean) -> Unit,
) {
    val episodes = state.episodes
    val listState = rememberLazyListState()
    LaunchedEffect(scrollToCurrent, episodes.isNotEmpty()) {
        val index = episodes.indexOfFirst { it.id == currentEpisodeId }
        if (scrollToCurrent && index > 0) listState.scrollToItem(index - 1)
    }

    // CE (#588): the row focus goes to when the sheet is opened from the keyboard: the playing
    // episode's, or the first when the season shown does not have it.
    val focusIndex = episodes.indexOfFirst { it.id == currentEpisodeId }.coerceAtLeast(0)
    val focusRow = remember { FocusRequester() }
    val currentMayTakeFocus by rememberUpdatedState(mayTakeFocus)
    val currentOnFocusSettled by rememberUpdatedState(onFocusSettled)
    LaunchedEffect(wantsFocus, episodes.isNotEmpty()) {
        if (!wantsFocus || episodes.isEmpty()) return@LaunchedEffect
        // A lazy list only has the rows on screen: bring the row in, and wait for it.
        listState.scrollToItem((focusIndex - 1).coerceAtLeast(0))
        withFrameNanos {}
        val focused =
            currentMayTakeFocus() &&
                try {
                    focusRow.requestFocus()
                } catch (_: IllegalStateException) {
                    false
                }
        currentOnFocusSettled(focused)
    }

    Column(
        modifier =
            Modifier.fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.displayCutout.only(
                        WindowInsetsSides.End + WindowInsetsSides.Vertical
                    )
                )
    ) {
        val title =
            state.season?.name
                ?: seasonNumber?.let { stringResource(CoreR.string.season_number, it) }
                ?: stringResource(CoreR.string.episodes_label)
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier.fillMaxWidth()
                    .padding(start = 24.dp, end = 16.dp, top = 24.dp, bottom = 16.dp),
        )
        if (episodes.isEmpty()) {
            if (state.error == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            return@Column
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(items = episodes, key = { _, episode -> episode.id }) { index, episode ->
                CompactEpisodeRow(
                    episode = episode,
                    selected = episode.id == currentEpisodeId,
                    shape = groupedItemShape(index, episodes.size),
                    onClick = { onEpisodeClick(episode) },
                    showDownloadControl = false,
                    modifier =
                        if (index == focusIndex) Modifier.focusRequester(focusRow) else Modifier,
                )
            }
        }
    }
}
