package dev.jdtech.jellyfin.presentation.cast

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.theme.EphyraColor
import dev.jdtech.jellyfin.core.presentation.ephyra.EphyraMotion
import dev.jdtech.jellyfin.core.presentation.ephyra.ephyraGlow
import dev.jdtech.jellyfin.core.presentation.ephyra.glowEnabled
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.player.cast.models.CastPlaybackStatus
import dev.jdtech.jellyfin.player.cast.presentation.CastNowPlaying
import dev.jdtech.jellyfin.player.cast.presentation.CastUiState
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter
import dev.jdtech.jellyfin.player.core.domain.models.Track
import dev.jdtech.jellyfin.player.local.R as PlayerR
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.utils.isCompactHeight
import org.jellyfin.sdk.model.api.BaseItemKind

const val CAST_PLAYER_TAG = "cast_player"

/** Width of the player as a side panel at expanded width (4-design.md §9, the supporting pane). */
val CastPanelWidth = 360.dp

/** What the panel needs in height for everything but the picture. */
private val PanelControlsHeight = 420.dp

/** The steps of the rewind and forward buttons: the local player's. */
private const val REWIND_MILLIS = -10_000L
private const val FORWARD_MILLIS = 30_000L

@Immutable
class CastPlayerActions(
    val onClose: () -> Unit,
    val onOpenDevices: () -> Unit,
    val onTogglePlay: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onSeekBy: (Long) -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onSkipSegment: () -> Unit,
    val onVolume: (Float) -> Unit,
    val onAudioTrack: (Track) -> Unit,
    val onSubtitleTrack: (Track?) -> Unit,
    val onRetry: () -> Unit,
    val onPlayAgain: () -> Unit,
)

/**
 * CE (#626): the controls of what the receiver plays, as the app's usual sheet. On a phone it
 * fills the screen; on a wider window it keeps a sheet's width (4-design.md §1.4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastPlayerSheet(state: CastUiState, actions: CastPlayerActions) {
    ModalBottomSheet(
        onDismissRequest = actions.onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        CastPlayerContent(
            state = state,
            actions = actions,
            sideBySide = isCompactHeight(),
            closeButton = false,
            modifier = Modifier.testTag(CAST_PLAYER_TAG),
        )
    }
}

/**
 * The same controls beside the content at expanded width: the next step sits next to what the user
 * is looking at (4-design.md §1.5). The panel is flush with the window's end, so only its start
 * corners are round (VISION §5, side panels are `large`).
 */
@Composable
fun CastPlayerPanel(state: CastUiState, actions: CastPlayerActions, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.large
    Surface(
        modifier = modifier.fillMaxHeight().width(CastPanelWidth),
        shape =
            RoundedCornerShape(
                topStart = shape.topStart,
                bottomStart = shape.bottomStart,
                topEnd = CornerSize(0.dp),
                bottomEnd = CornerSize(0.dp),
            ),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        // The panel runs under the system bars; what is in it stays clear of them. The picture
        // gives way first on a short window (an 8 inch tablet), so the controls stay in view.
        BoxWithConstraints(
            modifier =
                Modifier.windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
                )
        ) {
            CastPlayerContent(
                state = state,
                actions = actions,
                sideBySide = false,
                closeButton = true,
                artworkMaxHeight = (maxHeight - PanelControlsHeight).coerceIn(72.dp, 240.dp),
                modifier = Modifier.testTag(CAST_PLAYER_TAG),
            )
        }
    }
}

@Composable
internal fun CastPlayerContent(
    state: CastUiState,
    actions: CastPlayerActions,
    sideBySide: Boolean,
    closeButton: Boolean,
    modifier: Modifier = Modifier,
    artworkMaxHeight: Dp = 320.dp,
) {
    var dialog by rememberSaveable { mutableStateOf(CastDialog.NONE) }
    val shown = state.nowPlaying ?: state.startingItem ?: state.failedItem ?: state.finished

    if (sideBySide) {
        // A phone in landscape: the picture beside the controls, each in half the width.
        Row(
            modifier =
                modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacings.default),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
        ) {
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Header(state, actions, closeButton)
                Spacer(Modifier.height(MaterialTheme.spacings.small))
                Artwork(state, actions, shown, maxHeight = 200.dp)
                Spacer(Modifier.height(MaterialTheme.spacings.medium))
            }
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Titles(shown)
                Controls(state, actions, onDialog = { dialog = it })
                Spacer(Modifier.height(MaterialTheme.spacings.medium))
            }
        }
    } else {
        Column(
            modifier =
                modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = MaterialTheme.spacings.default)
        ) {
            if (closeButton) Spacer(Modifier.height(MaterialTheme.spacings.medium))
            Header(state, actions, closeButton)
            Spacer(Modifier.height(MaterialTheme.spacings.medium))
            Artwork(state, actions, shown, maxHeight = artworkMaxHeight)
            Spacer(Modifier.height(MaterialTheme.spacings.default))
            Titles(shown)
            Controls(state, actions, onDialog = { dialog = it })
            Spacer(Modifier.height(MaterialTheme.spacings.default))
        }
    }

    when (dialog) {
        CastDialog.AUDIO ->
            CastTrackDialog(
                title = stringResource(PlayerR.string.select_audio_track),
                tracks = state.audioTracks,
                isSubtitle = false,
                onSelect = { track ->
                    track?.let(actions.onAudioTrack)
                    dialog = CastDialog.NONE
                },
                onDismiss = { dialog = CastDialog.NONE },
            )
        CastDialog.SUBTITLE ->
            CastTrackDialog(
                title = stringResource(PlayerR.string.select_subtitle_track),
                tracks = state.subtitleTracks,
                isSubtitle = true,
                onSelect = { track ->
                    actions.onSubtitleTrack(track)
                    dialog = CastDialog.NONE
                },
                onDismiss = { dialog = CastDialog.NONE },
            )
        CastDialog.CHAPTERS ->
            CastChaptersDialog(
                chapters = state.chapters,
                positionMs = state.player.currentPosition,
                onSelect = { chapter ->
                    actions.onSeek(chapter.startPosition)
                    dialog = CastDialog.NONE
                },
                onDismiss = { dialog = CastDialog.NONE },
            )
        CastDialog.NONE -> Unit
    }
}

internal enum class CastDialog {
    NONE,
    AUDIO,
    SUBTITLE,
    CHAPTERS,
}

/** The receiver's name, as a pill that opens the device sheet; the panel also gets its close. */
@Composable
private fun Header(state: CastUiState, actions: CastPlayerActions, closeButton: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = actions.onOpenDevices,
            modifier = Modifier.weight(1f, fill = false).heightIn(min = 48.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = MaterialTheme.spacings.medium),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CastIcon(
                    state =
                        if (state.isReconnecting) CastButtonState.CONNECTING
                        else CastButtonState.CONNECTED,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = state.device?.name.orEmpty(),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (closeButton) {
            IconButton(onClick = actions.onClose) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_x),
                    contentDescription = stringResource(CoreR.string.close),
                )
            }
        }
    }
}

@Composable
private fun Artwork(
    state: CastUiState,
    actions: CastPlayerActions,
    shown: CastNowPlaying?,
    maxHeight: Dp,
) {
    val ratio = if (shown?.kind == BaseItemKind.MOVIE) 2f / 3f else 16f / 9f
    BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // As wide as the column, unless that makes it taller than its share of the screen.
        val width = minOf(maxWidth, maxHeight * ratio)
        Box(modifier = Modifier.width(width).aspectRatio(ratio)) {
            AsyncImage(
                model = shown?.artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier.fillMaxSize()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
            // "Skip intro" sits on the picture, where the local player has it, so the
            // controls below do not move when it comes and goes.
            val canSkip =
                state.segment != null &&
                    state.nowPlaying != null &&
                    !state.playbackFailed &&
                    !state.isReconnecting
            AnimatedVisibility(
                visible = canSkip,
                modifier = Modifier.align(Alignment.BottomEnd).padding(MaterialTheme.spacings.small),
                enter =
                    fadeIn(tween(EphyraMotion.DURATION_SHORT, easing = EphyraMotion.EasingEnter)),
                exit = fadeOut(tween(EphyraMotion.DURATION_SHORT, easing = EphyraMotion.EasingExit)),
            ) {
                FilledTonalButton(onClick = actions.onSkipSegment) {
                    Text(stringResource(state.skipSegmentLabel))
                }
            }
        }
    }
}

@Composable
private fun Titles(shown: CastNowPlaying?) {
    if (shown == null) return
    val overline = listOfNotNull(shown.seriesName, shown.episodeCode).joinToString(" · ")
    if (overline.isNotEmpty()) {
        Text(
            text = overline,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Text(
        text = shown.title,
        modifier = Modifier.semantics { heading() },
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.headlineSmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Controls(state: CastUiState, actions: CastPlayerActions, onDialog: (CastDialog) -> Unit) {
    val deviceName = state.device?.name.orEmpty()
    val playing = state.nowPlaying

    when {
        state.playbackFailed ->
            Notice(
                text = stringResource(CoreR.string.ce_cast_playback_failed, deviceName),
                isError = true,
            ) {
                if (state.canRetry) {
                    TextButton(onClick = actions.onRetry) {
                        Text(stringResource(CoreR.string.retry))
                    }
                }
            }
        state.isStarting && playing == null ->
            Notice(text = stringResource(CoreR.string.ce_cast_starting_on, deviceName)) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        state.isReconnecting ->
            Notice(text = stringResource(CoreR.string.ce_cast_reconnecting_to, deviceName)) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        playing == null && state.finished != null -> {
            // The queue ran out. What is left to do is play it again, or pick something else.
            Spacer(Modifier.height(MaterialTheme.spacings.small))
            Text(
                text = stringResource(CoreR.string.ce_cast_finished),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(MaterialTheme.spacings.medium))
            Button(onClick = actions.onPlayAgain) {
                Icon(painter = painterResource(CoreR.drawable.ic_play), contentDescription = null)
                Spacer(Modifier.width(MaterialTheme.spacings.small))
                Text(stringResource(CoreR.string.ce_cast_play_again))
            }
            return
        }
    }

    val enabled = playing != null && !state.playbackFailed && !state.isReconnecting
    val player = state.player

    Spacer(Modifier.height(MaterialTheme.spacings.medium))
    SeekBar(
        positionMs = player.currentPosition,
        durationMs = player.duration,
        chapters = state.chapters,
        enabled = enabled && player.duration > 0L,
        onSeek = actions.onSeek,
    )

    Spacer(Modifier.height(MaterialTheme.spacings.small))
    Transport(state, enabled, actions)
    Spacer(Modifier.height(MaterialTheme.spacings.medium))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement =
            Arrangement.spacedBy(MaterialTheme.spacings.small, Alignment.CenterHorizontally),
    ) {
        IconButton(
            onClick = { onDialog(CastDialog.AUDIO) },
            enabled = enabled && state.audioTracks.size > 1,
        ) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_speaker),
                contentDescription = stringResource(PlayerR.string.select_audio_track),
            )
        }
        IconButton(
            onClick = { onDialog(CastDialog.SUBTITLE) },
            enabled = enabled && state.subtitleTracks.isNotEmpty(),
        ) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_closed_caption),
                contentDescription = stringResource(PlayerR.string.select_subtitle_track),
            )
        }
        IconButton(
            onClick = { onDialog(CastDialog.CHAPTERS) },
            enabled = enabled && state.chapters.isNotEmpty(),
        ) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_list),
                contentDescription = stringResource(PlayerR.string.player_controls_chapters),
            )
        }
    }

    Volume(volume = player.volume, isMuted = player.isMuted, onVolume = actions.onVolume)
}

/** One line about the session's state, with what can be done about it at its end. */
@Composable
private fun Notice(text: String, isError: Boolean = false, trailing: @Composable () -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(top = MaterialTheme.spacings.small)
                .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isError) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_alert_circle),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
        trailing()
    }
}

@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    chapters: List<PlayerChapter>,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val range = 0f..durationMs.coerceAtLeast(1L).toFloat()
    val value = if (dragging) dragValue else positionMs.toFloat().coerceIn(range)
    val description = stringResource(PlayerR.string.player_controls_progress)
    val gap = MaterialTheme.colorScheme.surfaceContainerLow

    // Playback progress is `primary`, and its thumb is one of the three things that glow
    // (VISION §3.4, §6).
    ThinSlider(
        value = value,
        onValueChange = {
            dragging = true
            dragValue = it
        },
        onValueChangeFinished = {
            onSeek(dragValue.toLong())
            dragging = false
        },
        modifier =
            Modifier.fillMaxWidth().semantics {
                contentDescription = description
                stateDescription = formatTime(value.toLong())
            },
        enabled = enabled,
        valueRange = range,
        activeColor = MaterialTheme.colorScheme.primary,
        thumbColor = MaterialTheme.colorScheme.primary,
        glow = true,
        trackModifier =
            Modifier.drawWithContent {
                drawContent()
                // Chapters are gaps in the bar, in the colour behind it.
                if (durationMs > 0L && chapters.size > 1) {
                    val width = 2.dp.toPx()
                    chapters.forEach { chapter ->
                        if (chapter.startPosition in 1 until durationMs) {
                            val x = chapter.startPosition.toFloat() / durationMs * size.width
                            drawRect(
                                color = gap,
                                topLeft = Offset(x - width / 2f, 0f),
                                size = Size(width, size.height),
                            )
                        }
                    }
                }
            },
    )

    val times = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = formatTime(value.toLong()),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = times,
        )
        Text(
            text = if (durationMs > 0L) formatTime(durationMs) else "",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = times,
        )
    }
}

@Composable
private fun Transport(state: CastUiState, enabled: Boolean, actions: CastPlayerActions) {
    val player = state.player
    val isPlaying = player.status == CastPlaybackStatus.PLAYING
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = actions.onPrevious,
            // Previous also restarts the item, so it works without a previous one once the
            // item is under way.
            enabled =
                enabled &&
                    (player.hasPreviousItem || player.currentPosition > RestartThresholdMillis),
        ) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_skip_back),
                contentDescription = stringResource(PlayerR.string.player_controls_previous),
            )
        }
        IconButton(onClick = { actions.onSeekBy(REWIND_MILLIS) }, enabled = enabled) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_rewind),
                contentDescription = stringResource(PlayerR.string.player_controls_rewind),
            )
        }
        // The player's main control is the one icon on a `primary` fill (VISION §7).
        FilledIconButton(
            onClick = actions.onTogglePlay,
            modifier = Modifier.size(64.dp),
            enabled = enabled,
            colors =
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
        ) {
            if (enabled && player.status == CastPlaybackStatus.BUFFERING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 3.dp,
                )
            } else {
                Icon(
                    painter =
                        painterResource(
                            if (isPlaying) CoreR.drawable.ic_pause else CoreR.drawable.ic_play
                        ),
                    contentDescription =
                        stringResource(
                            if (isPlaying) CoreR.string.ce_cast_pause else CoreR.string.play
                        ),
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        IconButton(onClick = { actions.onSeekBy(FORWARD_MILLIS) }, enabled = enabled) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_fast_forward),
                contentDescription = stringResource(PlayerR.string.player_controls_fast_forward),
            )
        }
        IconButton(onClick = actions.onNext, enabled = enabled && player.hasNextItem) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_skip_forward),
                contentDescription = stringResource(PlayerR.string.player_controls_next_episode),
            )
        }
    }
}

@Composable
private fun Volume(volume: Float, isMuted: Boolean, onVolume: (Float) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging) dragValue else if (isMuted) 0f else volume.coerceIn(0f, 1f)
    val description = stringResource(CoreR.string.ce_cast_volume)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter =
                painterResource(
                    when {
                        shown <= 0f -> CoreR.drawable.ic_volume_0
                        shown < 0.5f -> CoreR.drawable.ic_volume_50
                        else -> CoreR.drawable.ic_volume
                    }
                ),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // A level, not progress: the bar takes the neutral roles, cyan stays with playback.
        ThinSlider(
            value = shown,
            onValueChange = {
                dragging = true
                dragValue = it
                onVolume(it)
            },
            onValueChangeFinished = { dragging = false },
            modifier = Modifier.weight(1f).semantics { contentDescription = description },
            activeColor = MaterialTheme.colorScheme.onSurfaceVariant,
            thumbColor = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The player's bar: a 4 dp track and a round 16 dp thumb, for the position and the volume. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    activeColor: Color,
    thumbColor: Color,
    modifier: Modifier = Modifier,
    trackModifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    glow: Boolean = false,
) {
    val colors =
        SliderDefaults.colors(
            activeTrackColor = activeColor,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    val glowOn = glow && enabled && glowEnabled(MaterialTheme.colorScheme.surface)
    val glowColor = MaterialTheme.colorScheme.primary.copy(alpha = EphyraColor.glow.alpha)
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        colors = colors,
        thumb = {
            Box(
                modifier =
                    Modifier.size(16.dp)
                        .ephyraGlow(
                            shape = CircleShape,
                            color = glowColor,
                            blur = 10.dp,
                            enabled = glowOn,
                        )
                        .background(
                            if (enabled) thumbColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            CircleShape,
                        )
            )
        },
        track = { sliderState ->
            SliderDefaults.Track(
                sliderState = sliderState,
                modifier = trackModifier.height(4.dp),
                enabled = enabled,
                colors = colors,
                drawStopIndicator = null,
                thumbTrackGapSize = 0.dp,
            )
        },
    )
}

private const val RestartThresholdMillis = 5_000L

/** `M:SS`, or `H:MM:SS` from an hour on. */
internal fun formatTime(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0L) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

private val previewActions =
    CastPlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

@Preview(showBackground = true, widthDp = 412, heightDp = 860)
@Preview(showBackground = true, widthDp = 412, heightDp = 860, fontScale = 2f)
@Composable
private fun CastPlayerPreview() {
    FindroidTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            CastPlayerContent(CastPreviews.playing, previewActions, sideBySide = false, closeButton = false)
        }
    }
}

@Preview(showBackground = true, widthDp = 900, heightDp = 400)
@Composable
private fun CastPlayerLandscapePreview() {
    FindroidTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            CastPlayerContent(CastPreviews.playing, previewActions, sideBySide = true, closeButton = false)
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun CastPlayerFailedPreview() {
    FindroidTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            CastPlayerContent(CastPreviews.failed, previewActions, sideBySide = false, closeButton = true)
        }
    }
}
