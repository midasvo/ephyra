package dev.jdtech.jellyfin.presentation.cast.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.ephyra.EphyraMotion
import dev.jdtech.jellyfin.player.core.domain.models.Track
import dev.jdtech.jellyfin.player.local.R as PlayerR
import dev.jdtech.jellyfin.presentation.components.SelectableTrackItem
import dev.jdtech.jellyfin.utils.TrackFormatter
import dev.jdtech.jellyfin.presentation.utils.WidthClass
import dev.jdtech.jellyfin.presentation.utils.currentWidthClass
import kotlin.math.abs
import kotlin.math.roundToInt

enum class CastTrackType {
    AUDIO,
    SUBTITLE,
}

/**
 * Dialog for selecting audio or subtitle tracks during Cast playback.
 */
@Composable
fun CastTrackSelectionDialog(
    visible: Boolean,
    type: CastTrackType,
    tracks: List<Track>,
    onSetTrack: (Track?) -> Unit,
    onDismiss: () -> Unit,
    displayExtraInfo: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var offsetY by remember { mutableFloatStateOf(0f) }
    val animatedOffset by animateFloatAsState(targetValue = offsetY, label = "offset")

    LaunchedEffect(visible) {
        if (visible) {
            offsetY = 0f
        }
    }

    // A bottom sheet at Compact width, a centred dialog on anything wider (a phone in landscape,
    // a tablet): docs/tablet/4-design.md §1.
    val isCentered = currentWidthClass() != WidthClass.Compact
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 150.dp.toPx() }
    val context = LocalContext.current

    val listState = rememberLazyListState()
    val isSubtitle = type == CastTrackType.SUBTITLE
    val forcedLabel = stringResource(PlayerR.string.ce_track_forced)

    AnimatedVisibility(
        visible = visible,
        enter =
            fadeIn(tween(EphyraMotion.DURATION_MEDIUM, easing = EphyraMotion.EasingEnter)) +
                slideInVertically(
                    tween(EphyraMotion.DURATION_MEDIUM, easing = EphyraMotion.EasingEnter)
                ) {
                    it
                },
        exit =
            fadeOut(tween(EphyraMotion.DURATION_SHORT, easing = EphyraMotion.EasingExit)) +
                slideOutVertically(
                    tween(EphyraMotion.DURATION_SHORT, easing = EphyraMotion.EasingExit)
                ) {
                    it
                },
    ) {
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            contentAlignment = if (isCentered) Alignment.Center else Alignment.BottomCenter,
        ) {
            val nestedScrollConnection = remember {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        if (available.y < 0 && offsetY > 0) {
                            val newOffset = offsetY + available.y
                            offsetY = newOffset.coerceAtLeast(0f)
                            return Offset(0f, available.y)
                        }
                        return Offset.Zero
                    }

                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset {
                        if (available.y > 0 && listState.firstVisibleItemIndex == 0 &&
                            listState.firstVisibleItemScrollOffset == 0
                        ) {
                            offsetY += available.y
                            return Offset(0f, available.y)
                        }
                        return Offset.Zero
                    }

                    override suspend fun onPreFling(available: Velocity): Velocity {
                        if (offsetY > dismissThresholdPx) {
                            currentOnDismiss()
                        } else {
                            offsetY = 0f
                        }
                        return Velocity.Zero
                    }
                }
            }

            Surface(
                modifier =
                    modifier
                        .offset { IntOffset(0, animatedOffset.roundToInt()) }
                        .nestedScroll(nestedScrollConnection)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val initialY = down.position.y
                                var isDragging = false

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break

                                    if (change.changedToUp()) {
                                        if (isDragging) {
                                            if (offsetY > dismissThresholdPx) {
                                                currentOnDismiss()
                                            } else {
                                                offsetY = 0f
                                            }
                                        }
                                        break
                                    }

                                    val dragDistance = change.position.y - initialY
                                    if (!isDragging && abs(dragDistance) > 10f) {
                                        if (dragDistance > 0 && listState.firstVisibleItemIndex == 0 &&
                                            listState.firstVisibleItemScrollOffset == 0
                                        ) {
                                            isDragging = true
                                        }
                                    }

                                    if (isDragging) {
                                        change.consume()
                                        offsetY = dragDistance.coerceAtLeast(0f)
                                    }
                                }
                            }
                        }
                        .then(
                            if (isCentered) {
                                Modifier.widthIn(max = 480.dp)
                                    .fillMaxWidth(0.6f)
                                    .heightIn(max = screenHeight * 0.85f)
                            } else {
                                Modifier.fillMaxWidth().heightIn(max = screenHeight * 0.75f)
                            }
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier =
                        Modifier.padding(top = 16.dp, bottom = 24.dp)
                            .then(if (isCentered) Modifier.statusBarsPadding() else Modifier),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier =
                            Modifier.width(36.dp)
                                .height(4.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    shape = CircleShape,
                                )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = stringResource(if (isSubtitle) CoreR.string.subtitle else CoreR.string.audio),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    LazyColumn(
                        state = listState,
                        modifier =
                            Modifier.fillMaxWidth()
                                .weight(1f, fill = false)
                                .padding(horizontal = 16.dp)
                                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                                .drawWithContent {
                                    drawContent()
                                    // An alpha mask (DstIn) that fades the list edges, not a colour.
                                    val fadeLength = 16.dp.toPx()
                                    val topFade = listState.firstVisibleItemIndex > 0 ||
                                        listState.firstVisibleItemScrollOffset > 0
                                    val bottomFade = listState.canScrollForward

                                    if (topFade) {
                                        drawRect(
                                            brush =
                                                Brush.verticalGradient(
                                                    colors = listOf(Color.Transparent, Color.Black),
                                                    startY = 0f,
                                                    endY = fadeLength,
                                                ),
                                            blendMode = BlendMode.DstIn,
                                        )
                                    }
                                    if (bottomFade) {
                                        drawRect(
                                            brush =
                                                Brush.verticalGradient(
                                                    colors = listOf(Color.Black, Color.Transparent),
                                                    startY = size.height - fadeLength,
                                                    endY = size.height,
                                                ),
                                            blendMode = BlendMode.DstIn,
                                        )
                                    }
                                },
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (isSubtitle) {
                            val noneSelected = tracks.none { it.selected }
                            item {
                                SelectableTrackItem(
                                    title = stringResource(CoreR.string.none),
                                    chips = emptyList(),
                                    isSelected = noneSelected,
                                    onClick = {
                                        onSetTrack(null)
                                        onDismiss()
                                    },
                                )
                            }
                        }

                        items(tracks) { track ->
                            val info =
                                remember(track, isSubtitle, displayExtraInfo, forcedLabel) {
                                    TrackFormatter.getTrackDisplayInfo(
                                        track = track,
                                        context = context,
                                        forcedLabel = forcedLabel,
                                        isSubtitle = isSubtitle,
                                        displayExtraInfo = displayExtraInfo,
                                    )
                                }
                            SelectableTrackItem(
                                title = info.name,
                                chips = info.chips,
                                isSelected = track.selected,
                                onClick = {
                                    onSetTrack(track)
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
