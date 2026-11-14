package dev.jdtech.jellyfin.presentation.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.player.cast.models.CastPlaybackStatus
import dev.jdtech.jellyfin.player.cast.presentation.CastNowPlaying
import dev.jdtech.jellyfin.player.cast.presentation.CastUiState
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import org.jellyfin.sdk.model.api.BaseItemKind

const val CAST_MINI_PLAYER_TAG = "cast_mini_player"

/** A sheet's width: the bar does not span a medium or expanded window (4-design.md §1.4). */
private val MiniPlayerMaxWidth = 640.dp

/**
 * CE (#626): the session, docked at the bottom of every screen while a receiver is connected. It
 * takes its own room below the content instead of floating over it. Tapping it opens the player, or
 * the device sheet while nothing plays.
 */
@Composable
fun CastMiniPlayer(
    state: CastUiState,
    onOpenPlayer: () -> Unit,
    onOpenDevices: () -> Unit,
    onTogglePlay: () -> Unit,
    onRetry: () -> Unit,
    onPlayAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val deviceName = state.device?.name.orEmpty()
    val playing = state.nowPlaying
    val finished = state.finished
    val starting = state.isStarting && playing == null
    val idle = playing == null && finished == null && !state.playbackFailed && !starting

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            // Nothing to control yet while idle or starting: the bar leads to the receiver.
            onClick = if (idle || starting) onOpenDevices else onOpenPlayer,
            modifier =
                Modifier.widthIn(max = MiniPlayerMaxWidth)
                    .fillMaxWidth()
                    .testTag(CAST_MINI_PLAYER_TAG),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column {
                Row(
                    modifier =
                        Modifier.heightIn(min = 64.dp)
                            .padding(
                                start = MaterialTheme.spacings.small,
                                top = MaterialTheme.spacings.small,
                                bottom = MaterialTheme.spacings.small,
                                end = MaterialTheme.spacings.extraSmall,
                            ),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        state.playbackFailed -> {
                            LeadingIcon {
                                Icon(
                                    painter = painterResource(CoreR.drawable.ic_alert_circle),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                            Lines(
                                title =
                                    stringResource(
                                        CoreR.string.ce_cast_playback_failed,
                                        deviceName,
                                    ),
                                subtitle = (playing ?: state.failedItem)?.title,
                                modifier =
                                    Modifier.weight(1f).semantics {
                                        liveRegion = LiveRegionMode.Polite
                                    },
                                titleLines = 2,
                            )
                            if (state.canRetry) {
                                IconButton(onClick = onRetry) {
                                    Icon(
                                        painter = painterResource(CoreR.drawable.ic_rotate_ccw),
                                        contentDescription = stringResource(CoreR.string.retry),
                                    )
                                }
                            }
                        }
                        playing != null -> {
                            CastThumbnail(playing, height = 48.dp)
                            if (state.isReconnecting) {
                                Lines(
                                    title = playing.seriesName ?: playing.title,
                                    subtitle =
                                        stringResource(
                                            CoreR.string.ce_cast_reconnecting_to,
                                            deviceName,
                                        ),
                                    modifier = Modifier.weight(1f),
                                )
                                Spinner()
                            } else {
                                CastTitleLines(playing, modifier = Modifier.weight(1f))
                                PlayPause(state.player.status, onTogglePlay)
                            }
                        }
                        starting -> {
                            LeadingIcon { CastIcon(CastButtonState.CONNECTED) }
                            Lines(
                                title =
                                    stringResource(CoreR.string.ce_cast_starting_on, deviceName),
                                subtitle = null,
                                modifier = Modifier.weight(1f),
                                titleLines = 2,
                            )
                            Spinner()
                        }
                        finished != null -> {
                            CastThumbnail(finished, height = 48.dp)
                            Lines(
                                title = finished.seriesName ?: finished.title,
                                subtitle = stringResource(CoreR.string.ce_cast_finished),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = onPlayAgain) {
                                Icon(
                                    painter = painterResource(CoreR.drawable.ic_rotate_ccw),
                                    contentDescription =
                                        stringResource(CoreR.string.ce_cast_play_again),
                                )
                            }
                        }
                        else -> {
                            LeadingIcon { CastIcon(CastButtonState.CONNECTED) }
                            Lines(
                                title =
                                    stringResource(CoreR.string.ce_cast_connected_to, deviceName),
                                subtitle = stringResource(CoreR.string.ce_cast_pick_something),
                                modifier =
                                    Modifier.weight(1f).padding(end = MaterialTheme.spacings.small),
                                titleLines = 2,
                            )
                        }
                    }
                }
                // Playback progress is `primary` (VISION §3.4), on the bar's lower edge.
                val duration = state.player.duration
                if (playing != null && duration > 0L && !state.playbackFailed) {
                    val fraction =
                        (state.player.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
                    Box(
                        modifier =
                            Modifier.fillMaxWidth()
                                .height(2.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    ) {
                        Box(
                            modifier =
                                Modifier.fillMaxWidth(fraction)
                                    .height(2.dp)
                                    .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LeadingIcon(content: @Composable () -> Unit) {
    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Spinner() {
    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun PlayPause(status: CastPlaybackStatus, onTogglePlay: () -> Unit) {
    if (status == CastPlaybackStatus.BUFFERING) {
        Spinner()
        return
    }
    val isPlaying = status == CastPlaybackStatus.PLAYING
    IconButton(onClick = onTogglePlay) {
        Icon(
            painter =
                painterResource(if (isPlaying) CoreR.drawable.ic_pause else CoreR.drawable.ic_play),
            contentDescription =
                stringResource(if (isPlaying) CoreR.string.ce_cast_pause else CoreR.string.play),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Lines(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    titleLines: Int = 1,
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
            maxLines = titleLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrEmpty()) {
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Two lines for what plays: the show and `S03E01 · the episode`, or the film alone. The separator
 * is the one metadata lines use everywhere else.
 */
@Composable
internal fun CastTitleLines(playing: CastNowPlaying, modifier: Modifier = Modifier) {
    val series = playing.seriesName
    if (series != null) {
        Lines(
            title = series,
            subtitle = listOfNotNull(playing.episodeCode, playing.title).joinToString(" · "),
            modifier = modifier,
        )
    } else {
        Lines(title = playing.title, subtitle = null, modifier = modifier, titleLines = 2)
    }
}

/** The item's picture at [height]: 16:9 for an episode, a poster for a film. */
@Composable
internal fun CastThumbnail(playing: CastNowPlaying, height: Dp, modifier: Modifier = Modifier) {
    val ratio = if (playing.kind == BaseItemKind.MOVIE) 2f / 3f else 16f / 9f
    AsyncImage(
        model = playing.artwork,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier =
            modifier
                .height(height)
                .aspectRatio(ratio)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

internal class CastMiniStateProvider : PreviewParameterProvider<CastUiState> {
    override val values = CastPreviews.miniStates.asSequence()
}

@Preview(showBackground = true, widthDp = 380)
@Preview(showBackground = true, widthDp = 380, fontScale = 2f)
@Composable
private fun CastMiniPlayerPreview(
    @PreviewParameter(CastMiniStateProvider::class) state: CastUiState
) {
    FindroidTheme {
        CastMiniPlayer(
            state = state,
            onOpenPlayer = {},
            onOpenDevices = {},
            onTogglePlay = {},
            onRetry = {},
            onPlayAgain = {},
            modifier = Modifier.padding(MaterialTheme.spacings.medium),
        )
    }
}
