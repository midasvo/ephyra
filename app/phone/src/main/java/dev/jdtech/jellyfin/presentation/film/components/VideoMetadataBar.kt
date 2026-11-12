package dev.jdtech.jellyfin.presentation.film.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyVideoMetadata
import dev.jdtech.jellyfin.core.presentation.dummy.dummyVideoMetadataRich
import dev.jdtech.jellyfin.models.AudioCodec
import dev.jdtech.jellyfin.models.DisplayProfile
import dev.jdtech.jellyfin.models.SpatialAudio
import dev.jdtech.jellyfin.models.VideoMetadata
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings

const val MEDIA_INFO_CHIP_TAG = "media_info_chip"

/**
 * The badges that sum up the file. With [onInfoClick] (CE, #510) the row ends in an info chip and
 * the whole row opens the media info sheet: the chip says there is more, the row is the touch
 * target, since a badge alone is too small to aim for. A press shows on the chip alone, wherever
 * on the row it lands: a ripple over the whole row would flash across the gaps between the badges
 * and, on the hero layouts, across the artwork behind them.
 */
@Composable
fun VideoMetadataBar(
    videoMetadata: VideoMetadata,
    is3D: Boolean = false,
    onInfoClick: (() -> Unit)? = null,
) {
    val mediaInfoLabel = stringResource(CoreR.string.ce_media_info)
    val interactionSource = remember { MutableInteractionSource() }
    // CE: wraps instead of overflowing, now that HDR10+ and spatial audio add badges.
    FlowRow(
        modifier =
            if (onInfoClick != null) {
                Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClick = onInfoClick,
                    )
                    .testTag(MEDIA_INFO_CHIP_TAG)
            } else {
                Modifier
            },
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
    ) {
        videoMetadata.resolution.firstOrNull()?.apply { VideoMetadataBarItem(text = this.raw) }
        videoMetadata.videoCodecs.firstOrNull()?.apply { VideoMetadataBarItem(text = this.raw) }
        // CE: every display profile, so Dolby Vision with an HDR10+ fallback shows both.
        videoMetadata.displayProfiles.forEach { profile ->
            when (profile) {
                DisplayProfile.DOLBY_VISION ->
                    VideoMetadataBarItem(text = profile.raw, icon = CoreR.drawable.ic_dolby)
                DisplayProfile.HDR10_PLUS ->
                    VideoMetadataBarItem(
                        text = null,
                        icon = CoreR.drawable.ic_hdr10_plus,
                        contentDescription = profile.raw,
                    )
                else -> VideoMetadataBarItem(text = profile.raw)
            }
        }
        videoMetadata.audioCodecs.firstOrNull()?.apply {
            val icon =
                when (this) {
                    AudioCodec.AC3,
                    AudioCodec.EAC3,
                    AudioCodec.TRUEHD -> CoreR.drawable.ic_dolby
                    else -> null
                }
            VideoMetadataBarItem(text = this.raw, icon = icon)
        }
        videoMetadata.spatialAudio.firstOrNull()?.apply {
            val icon =
                when (this) {
                    SpatialAudio.ATMOS -> CoreR.drawable.ic_dolby
                    SpatialAudio.DTS_X -> null
                }
            VideoMetadataBarItem(text = this.raw, icon = icon)
        }
        videoMetadata.audioChannels.firstOrNull()?.apply { VideoMetadataBarItem(text = this.raw) }
        // CE (#332): the item's video3DFormat, whatever the layout (side by side, MVC…).
        if (is3D) VideoMetadataBarItem(text = "3D")
        if (onInfoClick != null) {
            VideoMetadataBarItem(
                text = null,
                icon = CoreR.drawable.ic_info,
                contentDescription = mediaInfoLabel,
                squareIcon = true,
                interactionSource = interactionSource,
            )
        }
    }
}

/**
 * A metadata chip. [text] may be null for an icon-only badge (a wordmark such as HDR10+); give
 * those a [contentDescription]. An icon-only badge shows its drawable at the size it was drawn,
 * which suits a wordmark; [squareIcon] is for a 24 dp glyph, drawn as high as a line of text.
 * With an [interactionSource] the chip shows that source's presses as its own.
 */
@Composable
fun VideoMetadataBarItem(
    text: String?,
    @DrawableRes icon: Int? = null,
    contentDescription: String? = null,
    squareIcon: Boolean = false,
    interactionSource: InteractionSource? = null,
) {
    Row(
        modifier =
            Modifier.clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(
                    if (interactionSource != null) {
                        Modifier.indication(interactionSource, ripple())
                    } else {
                        Modifier
                    }
                )
                .padding(
                    horizontal = MaterialTheme.spacings.small,
                    vertical = MaterialTheme.spacings.extraSmall,
                ),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            if (text == null) {
                // Icon-only badges keep the height of the text ones.
                val lineHeight =
                    with(LocalDensity.current) {
                        MaterialTheme.typography.labelMedium.lineHeight.toDp()
                    }
                Box(modifier = Modifier.height(lineHeight), contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = contentDescription,
                        modifier = if (squareIcon) Modifier.size(lineHeight) else Modifier,
                    )
                }
            } else {
                Icon(painter = painterResource(icon), contentDescription = contentDescription)
            }
        }
        if (text != null) {
            Text(text = text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
@Preview(showBackground = true)
private fun VideoMetadataBarPreview() {
    FindroidTheme { VideoMetadataBar(videoMetadata = dummyVideoMetadata) }
}

@Composable
@Preview(showBackground = true, widthDp = 320)
private fun VideoMetadataBarRichPreview() {
    FindroidTheme { VideoMetadataBar(videoMetadata = dummyVideoMetadataRich) }
}
