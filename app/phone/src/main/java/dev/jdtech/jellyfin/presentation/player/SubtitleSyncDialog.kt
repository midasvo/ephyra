package dev.jdtech.jellyfin.presentation.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.player.core.domain.models.SubtitleSyncCue
import dev.jdtech.jellyfin.player.local.R as PlayerR
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

const val SUBTITLE_DELAY_STEP_MS = 100
const val SUBTITLE_DELAY_MIN_MS = -180_000
const val SUBTITLE_DELAY_MAX_MS = 180_000
const val HUMAN_REACTION_COMPENSATION_MS = 300L

private const val STEPPER_HOLD_START_MS = 400L
private const val STEPPER_HOLD_REPEAT_MS = 80L

data class SubtitleTrackItem(
    val index: Int,
    val name: String,
    val isSelected: Boolean,
)

fun formatSubtitleDelay(delayMs: Int): String {
    val seconds = delayMs / 1000f
    return if (seconds > 0f) {
        "+%.1fs".format(seconds)
    } else {
        "%.1fs".format(seconds)
    }
}

fun formatCueTimestamp(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}

/**
 * CE (#619): A sleek right-side sheet for subtitle track selection and real-time subtitle sync.
 * Matches [PlayerEpisodeSheet] styling with translucent background and edge docking so playback
 * remains clearly visible on the left side of the screen.
 */
@Composable
fun PlayerSubtitleSyncSheet(
    tracks: List<SubtitleTrackItem>,
    selectedTrackIndex: Int,
    subtitleDelayMs: Int,
    currentPlaybackPositionMs: Long = 0L,
    getCurrentPositionMs: () -> Long = { currentPlaybackPositionMs },
    availableCues: List<SubtitleSyncCue>,
    isLoadingCues: Boolean = false,
    onTrackSelected: (Int) -> Unit,
    onDelayChanged: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val sheetWidth = (maxWidth * 0.38f).coerceIn(340.dp, 440.dp)

        // Backdrop: tap outside the sheet to dismiss
        Box(
            modifier =
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures { onDismiss() }
                }
        )

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f),
            shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
            modifier =
                Modifier.align(Alignment.CenterEnd)
                    .width(sheetWidth)
                    .fillMaxHeight()
                    .windowInsetsPadding(
                        WindowInsets.displayCutout.only(WindowInsetsSides.End)
                    ),
        ) {
            SubtitleSyncSheetContent(
                tracks = tracks,
                selectedTrackIndex = selectedTrackIndex,
                subtitleDelayMs = subtitleDelayMs,
                getCurrentPositionMs = getCurrentPositionMs,
                availableCues = availableCues,
                isLoadingCues = isLoadingCues,
                onTrackSelected = onTrackSelected,
                onDelayChanged = onDelayChanged,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun SubtitleSyncSheetContent(
    tracks: List<SubtitleTrackItem>,
    selectedTrackIndex: Int,
    subtitleDelayMs: Int,
    getCurrentPositionMs: () -> Long,
    availableCues: List<SubtitleSyncCue>,
    isLoadingCues: Boolean,
    onTrackSelected: (Int) -> Unit,
    onDelayChanged: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedTab by remember {
        mutableIntStateOf(if (selectedTrackIndex >= 0) 1 else 0)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_closed_caption),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = stringResource(CoreR.string.subtitle),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_x),
                    contentDescription = stringResource(CoreR.string.close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Navigation Segment Pills
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                label = { Text(stringResource(CoreR.string.subtitle)) },
                modifier = Modifier.weight(1f),
                colors =
                    FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                    ),
            )
            FilterChip(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                enabled = selectedTrackIndex >= 0,
                label = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // The label gives way to the badge: the chip is half the sheet wide.
                        Text(
                            text = stringResource(PlayerR.string.ce_subtitle_sync),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (subtitleDelayMs != 0) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                Text(
                                    text = formatSubtitleDelay(subtitleDelayMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    // One line: in the narrow chip "+2.7s" broke over three.
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                colors =
                    FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                    ),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Content
        if (selectedTab == 0) {
            SubtitleTrackPicker(
                tracks = tracks,
                selectedTrackIndex = selectedTrackIndex,
                onTrackSelected = { index ->
                    onTrackSelected(index)
                    if (index >= 0) selectedTab = 1
                },
            )
        } else {
            SubtitleTimingPanel(
                subtitleDelayMs = subtitleDelayMs,
                getCurrentPositionMs = getCurrentPositionMs,
                cues = availableCues,
                isLoadingCues = isLoadingCues,
                onDelayChanged = onDelayChanged,
            )
        }
    }
}

@Composable
private fun SubtitleTrackPicker(
    tracks: List<SubtitleTrackItem>,
    selectedTrackIndex: Int,
    onTrackSelected: (Int) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        // "None" option
        item {
            TrackRow(
                title = stringResource(PlayerR.string.none),
                isSelected = selectedTrackIndex == -1,
                leadingIcon = {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_eye_off),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onClick = { onTrackSelected(-1) },
            )
        }

        items(tracks, key = { it.index }) { track ->
            TrackRow(
                title = track.name,
                isSelected = track.isSelected,
                onClick = { onTrackSelected(track.index) },
            )
        }
    }
}

@Composable
private fun TrackRow(
    title: String,
    isSelected: Boolean,
    leadingIcon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color =
            if (isSelected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                leadingIcon?.invoke()
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color =
                        if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isSelected) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun SubtitleTimingPanel(
    subtitleDelayMs: Int,
    getCurrentPositionMs: () -> Long,
    cues: List<SubtitleSyncCue>,
    isLoadingCues: Boolean,
    onDelayChanged: (Int) -> Unit,
) {
    var capturedPositionMs by remember { mutableStateOf<Long?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 20.dp),
    ) {
        // 1. Delay Stepper Section
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(PlayerR.string.ce_subtitle_delay),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        TextButton(
                            onClick = { onDelayChanged(0) },
                            enabled = subtitleDelayMs != 0,
                        ) {
                            Text(stringResource(PlayerR.string.ce_reset))
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    HoldToRepeatStepper(
                        value = formatSubtitleDelay(subtitleDelayMs),
                        onDecrease = {
                            onDelayChanged(
                                (subtitleDelayMs - SUBTITLE_DELAY_STEP_MS).coerceAtLeast(
                                    SUBTITLE_DELAY_MIN_MS
                                )
                            )
                        },
                        onIncrease = {
                            onDelayChanged(
                                (subtitleDelayMs + SUBTITLE_DELAY_STEP_MS).coerceAtMost(
                                    SUBTITLE_DELAY_MAX_MS
                                )
                            )
                        },
                    )
                }
            }
        }

        // 2. Smart Line Sync Section
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_sparkles),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(PlayerR.string.ce_subtitle_sync),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = stringResource(PlayerR.string.ce_capture_line_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    FilledTonalButton(
                        onClick = {
                            capturedPositionMs = getCurrentPositionMs().coerceAtLeast(0L)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                            ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        shape = CircleShape,
                    ) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_sparkles),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (capturedPositionMs == null)
                                stringResource(PlayerR.string.ce_capture_spoken_line)
                            else stringResource(PlayerR.string.ce_recapture_spoken_line)
                        )
                    }

                    if (capturedPositionMs != null) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text =
                                stringResource(
                                    PlayerR.string.ce_dialogue_near_position,
                                    formatCueTimestamp(capturedPositionMs!!),
                                ),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (isLoadingCues) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = stringResource(PlayerR.string.ce_loading_dialogue),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        } else {
                            val nearby =
                                cues.sortedBy { abs(it.startTimeMs - capturedPositionMs!!) }
                                    .take(5)
                                    .sortedBy { it.startTimeMs }

                            if (nearby.isEmpty()) {
                                Text(
                                    text = stringResource(PlayerR.string.ce_no_cues_found),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for (cue in nearby) {
                                        val offsetMs =
                                            (capturedPositionMs!! - cue.startTimeMs - HUMAN_REACTION_COMPENSATION_MS)
                                                .toInt()
                                                .coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)

                                        CueRow(
                                            cue = cue,
                                            offsetMs = offsetMs,
                                            onClick = { onDelayChanged(offsetMs) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CueRow(
    cue: SubtitleSyncCue,
    offsetMs: Int,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier =
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Text(
                    text = formatCueTimestamp(cue.startTimeMs),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = cue.text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier =
                    Modifier.clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = formatSubtitleDelay(offsetMs),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HoldToRepeatStepper(
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HoldToRepeatButton(
            iconRes = CoreR.drawable.ic_minus_fat,
            contentDescription = stringResource(PlayerR.string.ce_decrease_delay),
            onAction = onDecrease,
        )

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
            modifier = Modifier.weight(1f).height(48.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        HoldToRepeatButton(
            iconRes = CoreR.drawable.ic_plus,
            contentDescription = stringResource(PlayerR.string.ce_increase_delay),
            onAction = onIncrease,
        )
    }
}

@Composable
private fun HoldToRepeatButton(
    iconRes: Int,
    contentDescription: String,
    onAction: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val latestAction by rememberUpdatedState(onAction)
    var didRepeat by remember { mutableStateOf(false) }

    LaunchedEffect(isPressed) {
        if (!isPressed) {
            didRepeat = false
            return@LaunchedEffect
        }
        delay(STEPPER_HOLD_START_MS)
        didRepeat = true
        while (isActive) {
            latestAction()
            delay(STEPPER_HOLD_REPEAT_MS)
        }
    }

    FilledTonalIconButton(
        onClick = {
            if (!didRepeat) {
                onAction()
            }
        },
        interactionSource = interactionSource,
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
        )
    }
}
