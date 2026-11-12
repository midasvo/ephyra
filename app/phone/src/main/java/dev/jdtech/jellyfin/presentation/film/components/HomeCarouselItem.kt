package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.res.stringResource
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.film.presentation.home.HomeAction
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.keyboardFocusRing
import dev.jdtech.jellyfin.utils.copyOnLongClick

private val scrimColorStops =
    arrayOf(
        0.0f to Color.Black.copy(alpha = 0.1f),
        0.5f to Color.Black.copy(alpha = 0.5f),
        1f to Color.Black.copy(alpha = 0.6f),
    )

@Composable
fun HomeCarouselItem(
    item: FindroidItem,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .aspectRatio(1.77f)
                .keyboardFocusRing(MaterialTheme.shapes.large)
                .clip(MaterialTheme.shapes.large)
                .copyOnLongClick(
                    text = item.name,
                    onClick = { onAction(HomeAction.OnItemClick(item)) }
                )
    ) {
        // CE (#433): the poster stands in for a missing backdrop. With neither, the card is plain
        // and its text takes the theme's colours instead of white over a scrim.
        val artwork = rememberArtworkState(item, ArtworkPlacement.Carousel)
        if (artwork.hasArtwork) {
            ArtworkBackdrop(
                state = artwork,
                contentDescription =
                    stringResource(CoreR.string.image_description_backdrop, item.name),
                modifier = Modifier.fillMaxSize(),
            )
            Spacer(
                modifier =
                    Modifier.fillMaxSize().drawWithCache {
                        val brush = Brush.verticalGradient(colorStops = scrimColorStops)
                        onDrawBehind { drawRect(brush = brush) }
                    }
            )
        } else {
            Spacer(
                modifier =
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
            modifier =
                Modifier.padding(
                        horizontal = MaterialTheme.spacings.default,
                        vertical = MaterialTheme.spacings.medium,
                    )
                    .align(Alignment.BottomStart),
        ) {
            val genres =
                when (item) {
                    is FindroidMovie -> item.genres
                    is FindroidShow -> item.genres
                    else -> emptyList()
                }
            Text(
                text = genres.joinToString(),
                color =
                    if (artwork.hasArtwork) Color.LightGray
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = item.name,
                modifier = Modifier,
                color = if (artwork.hasArtwork) Color.White else MaterialTheme.colorScheme.onSurface,
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
@Preview(showBackground = true)
private fun HomeCarouselItemPreview() {
    FindroidTheme { HomeCarouselItem(item = dummyMovie, onAction = {}) }
}
