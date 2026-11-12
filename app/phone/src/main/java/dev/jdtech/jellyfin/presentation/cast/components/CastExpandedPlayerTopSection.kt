package dev.jdtech.jellyfin.presentation.cast.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.player.cast.presentation.CastPlayerViewModel

/**
 * Top artwork and header section of expanded Google Cast player.
 */
@Composable
fun PlayerTopSection(
    uiState: CastPlayerViewModel.UiState,
    isScrubbing: Boolean,
    scrubPosition: Float,
    onClose: () -> Unit,
    onDeviceClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val deviceName = uiState.connectedDevice?.name
    val itemTitle =
        remember(uiState.currentItemTitle) {
            val titleInfo = uiState.currentItemTitle
            if (titleInfo.seriesName != null) {
                "${titleInfo.seriesName} - ${titleInfo.title}"
            } else {
                titleInfo.title
            }
        }

    val computedAspectRatio = uiState.defaultAspectRatio
    val safeAspectRatio =
        if (computedAspectRatio.isFinite() && computedAspectRatio > 0f) {
            computedAspectRatio
        } else {
            16f / 10f
        }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CastExpandedPlayerHeader(
            deviceName = deviceName,
            onClose = onClose,
            onDeviceClick = onDeviceClick,
        )

        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            CastPlayerArtwork(
                artworkUri = uiState.currentItemArtwork,
                itemTitle = itemTitle,
                aspectRatio = safeAspectRatio,
                modifier = Modifier.fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun CastPlayerArtwork(
    artworkUri: Uri?,
    itemTitle: String,
    aspectRatio: Float,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier =
            modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .aspectRatio(aspectRatio)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (artworkUri != null) {
            val contentDescription =
                if (itemTitle.isNotBlank()) {
                    stringResource(CoreR.string.image_description_poster, itemTitle)
                } else {
                    stringResource(CoreR.string.series_poster)
                }

            AsyncImage(
                model = artworkUri,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                painter = painterResource(CoreR.drawable.ic_cast),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
