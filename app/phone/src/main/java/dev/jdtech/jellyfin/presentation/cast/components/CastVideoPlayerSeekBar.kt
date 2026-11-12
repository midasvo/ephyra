package dev.jdtech.jellyfin.presentation.cast.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.models.FindroidSegment
import dev.jdtech.jellyfin.models.FindroidSegmentType
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter
import dev.jdtech.jellyfin.player.local.R as PlayerR

@Composable
fun CastVideoPlayerSeekBar(
    progress: Float,
    onSeek: (seekProgress: Float) -> Unit,
    modifier: Modifier = Modifier,
    durationMs: Long = 0L,
    chapters: List<PlayerChapter> = emptyList(),
    segments: List<FindroidSegment> = emptyList(),
    onScrubbingChanged: (isScrubbing: Boolean, scrubPositionMs: Long, touchX: Float) -> Unit =
        { _, _, _ -> },
    isBuffering: Boolean = false,
    trackHeight: Dp = 8.dp,
    highlightTrackHeight: Dp = 10.dp,
    thumbWidth: Dp = 4.dp,
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnScrubbingChanged by rememberUpdatedState(onScrubbingChanged)
    val currentProgress by rememberUpdatedState(progress)

    val primaryColor = MaterialTheme.colorScheme.primary
    val trackBackgroundColor = MaterialTheme.colorScheme.surfaceVariant
    val thumbColor = MaterialTheme.colorScheme.primary
    // One role for every skippable segment: the label above the bar names its type.
    val segmentColor = MaterialTheme.colorScheme.secondary
    // Chapter dividers are gaps in the bar, so they take the colour behind it.
    val dividerColor = MaterialTheme.colorScheme.surface
    val progressDescription = stringResource(PlayerR.string.player_controls_progress)

    val displayedProgress = if (isDragging) dragProgress else currentProgress

    val bufferAlpha = remember { Animatable(1f) }
    LaunchedEffect(isBuffering) {
        if (isBuffering) {
            while (true) {
                bufferAlpha.animateTo(0.4f)
                bufferAlpha.animateTo(1f)
            }
        } else {
            bufferAlpha.snapTo(1f)
        }
    }

    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(48.dp)
                .semantics {
                    contentDescription = progressDescription
                    progressBarRangeInfo =
                        ProgressBarRangeInfo(displayedProgress.coerceIn(0f, 1f), 0f..1f)
                }
                .pointerInput(durationMs) {
                    detectTapGestures(
                        // onTap, not onPress: a press also starts every drag, which seeked twice.
                        onTap = { offset ->
                            val width = size.width.toFloat()
                            val frac = calculateProgressFromX(offset.x, width)
                            val scrubMs = (frac * durationMs).toLong()
                            currentOnScrubbingChanged(true, scrubMs, offset.x)
                            currentOnSeek(frac)
                            currentOnScrubbingChanged(false, scrubMs, offset.x)
                        }
                    )
                }
                .pointerInput(durationMs) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            val width = size.width.toFloat()
                            val frac = calculateProgressFromX(offset.x, width)
                            dragProgress = frac
                            val scrubMs = (frac * durationMs).toLong()
                            currentOnScrubbingChanged(true, scrubMs, offset.x)
                        },
                        onDragEnd = {
                            isDragging = false
                            currentOnSeek(dragProgress)
                            val scrubMs = (dragProgress * durationMs).toLong()
                            currentOnScrubbingChanged(false, scrubMs, 0f)
                        },
                        onDragCancel = {
                            isDragging = false
                            val scrubMs = (dragProgress * durationMs).toLong()
                            currentOnScrubbingChanged(false, scrubMs, 0f)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val width = size.width.toFloat()
                            val frac = calculateProgressFromX(change.position.x, width)
                            dragProgress = frac
                            val scrubMs = (frac * durationMs).toLong()
                            currentOnScrubbingChanged(true, scrubMs, change.position.x)
                        },
                    )
                }
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val barHeight = if (isDragging) highlightTrackHeight.toPx() else trackHeight.toPx()
        val top = (canvasHeight - barHeight) / 2f
        val cornerRadius = CornerRadius(barHeight / 2f, barHeight / 2f)

        // Draw background track
        drawRoundRect(
            color = trackBackgroundColor,
            topLeft = Offset(0f, top),
            size = Size(canvasWidth, barHeight),
            cornerRadius = cornerRadius,
        )

        // Draw segments if present
        if (durationMs > 0L) {
            for (seg in segments) {
                val segStart = (seg.startTicks.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) * canvasWidth
                val segEnd = (seg.endTicks.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) * canvasWidth
                if (seg.type != FindroidSegmentType.UNKNOWN && segEnd > segStart) {
                    drawRect(
                        color = segmentColor.copy(alpha = 0.5f),
                        topLeft = Offset(segStart, top),
                        size = Size(segEnd - segStart, barHeight),
                    )
                }
            }
        }

        // Draw active progress track
        val activeWidth = (displayedProgress * canvasWidth).coerceIn(0f, canvasWidth)
        if (activeWidth > 0f) {
            drawRoundRect(
                color = primaryColor.copy(alpha = bufferAlpha.value),
                topLeft = Offset(0f, top),
                size = Size(activeWidth, barHeight),
                cornerRadius = cornerRadius,
            )
        }

        // Draw chapter dividers if any
        if (durationMs > 0L && chapters.size > 1) {
            val dividerWidth = 2.dp.toPx()
            for (chapter in chapters) {
                if (chapter.startPosition > 0L) {
                    val chapX = (chapter.startPosition.toFloat() / durationMs.toFloat()) * canvasWidth
                    drawRect(
                        color = dividerColor,
                        topLeft = Offset(chapX - dividerWidth / 2f, top),
                        size = Size(dividerWidth, barHeight),
                    )
                }
            }
        }

        // Draw thumb
        val thumbW = if (isDragging) thumbWidth.toPx() * 1.5f else thumbWidth.toPx()
        val thumbH = barHeight + if (isDragging) 12.dp.toPx() else 6.dp.toPx()
        val thumbTop = (canvasHeight - thumbH) / 2f
        val thumbX = (activeWidth - thumbW / 2f).coerceIn(0f, canvasWidth - thumbW)
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(thumbX, thumbTop),
            size = Size(thumbW, thumbH),
            cornerRadius = CornerRadius(thumbW / 2f, thumbW / 2f),
        )
    }
}
