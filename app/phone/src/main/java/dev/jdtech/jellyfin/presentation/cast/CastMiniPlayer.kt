package dev.jdtech.jellyfin.presentation.cast

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.player.cast.models.CastPlaybackStatus
import dev.jdtech.jellyfin.player.cast.models.CastPlayerState
import dev.jdtech.jellyfin.player.cast.models.Device
import dev.jdtech.jellyfin.player.cast.presentation.CastPlayerViewModel
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.WidthClass
import dev.jdtech.jellyfin.presentation.utils.currentWidthClass
import dev.jdtech.jellyfin.presentation.utils.isCompactHeight
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding

@Composable
fun CastMiniPlayer(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CastPlayerViewModel = hiltViewModel(),
    handleBottomInsets: Boolean = true,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val connectedDevice = uiState.connectedDevice
    val playbackState = uiState.playerState

    if (connectedDevice != null) {
        val onTogglePlayback = remember(viewModel) { { viewModel.togglePlayPause() } }
        CastMiniPlayerLayout(
            connectedDevice = connectedDevice,
            uiState = uiState,
            playbackState = playbackState,
            onTogglePlayback = onTogglePlayback,
            onClick = onClick,
            modifier = modifier,
            handleBottomInsets = handleBottomInsets,
        )
    }
}

@Composable
fun CastMiniPlayerLayout(
    connectedDevice: Device,
    uiState: CastPlayerViewModel.UiState,
    playbackState: CastPlayerState,
    onTogglePlayback: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    handleBottomInsets: Boolean = true,
) {
    // Medium and Expanded windows get a half-width card; the taller thumbnail needs the height
    // too, so a phone in landscape keeps the small one (docs/tablet/4-design.md §1).
    val isMediumScreen = currentWidthClass() != WidthClass.Compact
    val largeThumbnail = isMediumScreen && !isCompactHeight()

    val safePadding = rememberSafePadding()

    val paddingStart = safePadding.start + MaterialTheme.spacings.medium
    val paddingEnd = safePadding.end + MaterialTheme.spacings.medium
    val paddingBottom = (if (handleBottomInsets) safePadding.bottom else 0.dp) + MaterialTheme.spacings.medium

    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        modifier =
            modifier
                .padding(
                    top = MaterialTheme.spacings.medium,
                    start = paddingStart,
                    end = paddingEnd,
                    bottom = paddingBottom,
                )
                .then(
                    if (isMediumScreen) {
                        Modifier.widthIn(max = 1000.dp).fillMaxWidth(0.5f)
                    } else {
                        Modifier.fillMaxWidth()
                    }
                ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Box {
            Row(
                modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
            ) {
                if (uiState.fileLoaded) {
                    MiniPlayerThumbnail(
                        artworkUri = uiState.currentItemArtwork,
                        defaultAspectRatio = uiState.defaultAspectRatio,
                        large = largeThumbnail,
                    )

                    MiniPlayerMediaInfo(
                        titleInfo = uiState.currentItemTitle,
                        deviceName = connectedDevice.name,
                        modifier = Modifier.weight(1f),
                    )

                    val isPlaying = playbackState.status == CastPlaybackStatus.PLAYING
                    MiniPlayerPlayPauseButton(
                        isPlaying = isPlaying,
                        onTogglePlayback = onTogglePlayback,
                    )
                } else {
                    MiniPlayerIdleInfo(
                        deviceName = connectedDevice.name,
                        modifier = Modifier.weight(1f),
                    )

                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_cast),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            MiniPlayerProgressBar(
                status = playbackState.status,
                currentPosition = playbackState.currentPosition,
                duration = playbackState.duration,
                fileLoaded = uiState.fileLoaded,
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun MiniPlayerThumbnail(
    artworkUri: Uri?,
    defaultAspectRatio: Float,
    large: Boolean,
    modifier: Modifier = Modifier,
) {
    // A missing or broken ratio would crash aspectRatio(): fall back to 16:9.
    val aspectRatio =
        if (defaultAspectRatio.isFinite() && defaultAspectRatio > 0f) defaultAspectRatio
        else 16f / 9f
    Box(
        modifier =
            modifier
                .height(if (large) 80.dp else 64.dp)
                .aspectRatio(aspectRatio)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        AsyncImage(
            model = artworkUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun MiniPlayerMediaInfo(
    titleInfo: CastPlayerViewModel.CurrentItemTitle,
    deviceName: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        val seriesName = titleInfo.seriesName
        if (seriesName != null) {
            Text(
                text = seriesName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val episodeText =
                remember(titleInfo.episodeInfo, titleInfo.title) {
                    if (titleInfo.episodeInfo.isNullOrEmpty()) {
                        titleInfo.title
                    } else {
                        "${titleInfo.episodeInfo} - ${titleInfo.title}"
                    }
                }
            Text(
                text = episodeText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                text = titleInfo.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = deviceName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MiniPlayerPlayPauseButton(
    isPlaying: Boolean,
    onTogglePlayback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.size(64.dp),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = onTogglePlayback,
            colors =
                IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            // Buttons are full pills (VISION §5).
            shape = CircleShape,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                painter =
                    if (isPlaying) painterResource(CoreR.drawable.ic_pause)
                    else painterResource(CoreR.drawable.ic_play),
                contentDescription =
                    if (isPlaying) {
                        stringResource(CoreR.string.ce_cast_pause)
                    } else {
                        stringResource(CoreR.string.play)
                    },
            )
        }
    }
}

@Composable
private fun MiniPlayerIdleInfo(
    deviceName: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(start = MaterialTheme.spacings.small)) {
        Text(
            text = deviceName,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(CoreR.string.ce_cast_connected),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MiniPlayerProgressBar(
    status: CastPlaybackStatus,
    currentPosition: Long,
    duration: Long,
    fileLoaded: Boolean,
    modifier: Modifier = Modifier,
) {
    if (status == CastPlaybackStatus.BUFFERING) {
        LinearProgressIndicator(
            modifier = modifier,
            strokeCap = StrokeCap.Butt,
        )
    } else if (fileLoaded) {
        LinearProgressIndicator(
            progress = {
                if (duration > 0) {
                    (currentPosition.toFloat() / duration).coerceIn(0f, 1f)
                } else {
                    0f
                }
            },
            modifier = modifier,
            strokeCap = StrokeCap.Butt,
        )
    }
}
