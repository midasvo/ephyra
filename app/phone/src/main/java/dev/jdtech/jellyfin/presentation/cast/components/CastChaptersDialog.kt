package dev.jdtech.jellyfin.presentation.cast.components

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.presentation.theme.CentralShapes
import dev.jdtech.jellyfin.models.FindroidSegment
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter
import dev.jdtech.jellyfin.presentation.components.ChapterCard
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Dialog for selecting chapters during Cast playback.
 */
@Composable
fun CastChaptersDialog(
    visible: Boolean,
    chapters: List<PlayerChapter>,
    currentPositionMs: Long,
    onChapterSelected: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    segments: List<FindroidSegment> = emptyList(),
    durationMs: Long = 0L,
) {
    var offsetY by remember { mutableFloatStateOf(0f) }
    val animatedOffset by animateFloatAsState(targetValue = offsetY, label = "offset")

    LaunchedEffect(visible) {
        if (visible) {
            offsetY = 0f
        }
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val screenHeight = configuration.screenHeightDp.dp
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 150.dp.toPx() }

    val listState = rememberLazyListState()

    val currentChapterIndex =
        remember(chapters, currentPositionMs) {
            chapters.indexOfLast { currentPositionMs >= it.startPosition }
        }

    LaunchedEffect(visible, currentChapterIndex) {
        if (visible && currentChapterIndex >= 0) {
            listState.animateScrollToItem(currentChapterIndex)
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
    ) {
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            contentAlignment = if (isLandscape) Alignment.Center else Alignment.BottomCenter,
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
                            onDismiss()
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
                                                onDismiss()
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
                            if (isLandscape) {
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
                shape = CentralShapes.Dialog,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier =
                        Modifier.padding(top = 16.dp, bottom = 24.dp)
                            .then(if (isLandscape) Modifier.statusBarsPadding() else Modifier),
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
                        text = "Chapters",
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
                        itemsIndexed(chapters) { index, chapter ->
                            val isCurrent = index == currentChapterIndex
                            ChapterCard(
                                chapter = chapter,
                                index = index,
                                isCurrent = isCurrent,
                                onClick = {
                                    onChapterSelected(chapter.startPosition)
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
