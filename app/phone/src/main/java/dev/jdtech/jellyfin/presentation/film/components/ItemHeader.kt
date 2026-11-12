package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.Layout
import dev.jdtech.jellyfin.presentation.utils.drawTopScrimOverArt
import dev.jdtech.jellyfin.presentation.utils.parallaxLayoutModifier
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding

@Composable
fun ItemHeader(
    item: FindroidItem,
    scrollState: ScrollState,
    showLogo: Boolean = false,
    artwork: ArtworkState = rememberArtworkState(item, ArtworkPlacement.Header),
    content: @Composable (BoxScope.() -> Unit) = {},
) {
    ItemHeaderBase(
        item = item,
        artwork = artwork,
        showLogo = showLogo,
        backdropImage = {
            ArtworkBackdrop(
                state = artwork,
                modifier =
                    Modifier.fillMaxSize()
                        .parallaxLayoutModifier(scrollState = scrollState, rate = 2),
            )
        },
        content = content,
    )
}

@Composable
fun ItemHeader(
    item: FindroidItem,
    lazyListState: LazyListState,
    showLogo: Boolean = false,
    artwork: ArtworkState = rememberArtworkState(item, ArtworkPlacement.Header),
    content: @Composable (BoxScope.() -> Unit) = {},
) {
    ItemHeaderBase(
        item = item,
        artwork = artwork,
        showLogo = showLogo,
        backdropImage = {
            ArtworkBackdrop(
                state = artwork,
                modifier =
                    Modifier.fillMaxSize()
                        .parallaxLayoutModifier(lazyListState = lazyListState, rate = 2),
            )
        },
        content = content,
    )
}

@Composable
private fun ItemHeaderBase(
    item: FindroidItem,
    artwork: ArtworkState,
    showLogo: Boolean = false,
    backdropImage: @Composable (() -> Unit),
    content: @Composable (BoxScope.() -> Unit) = {},
) {
    val backgroundColor = MaterialTheme.colorScheme.background

    val logoUri =
        when (item) {
            is FindroidEpisode -> item.images.showLogo
            else -> item.images.logo
        }

    // CE (#433): without any artwork the header is only the room below the top bar for the title.
    if (!artwork.hasArtwork) {
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(top = rememberSafePadding().top + Layout.topBarHeight),
            content = content,
        )
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val windowHeight =
            with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
        // Phones keep the fixed 288 dp. Wider windows grow the header towards 16:9 so the
        // backdrop isn't cropped to a thin strip, but never past half the window height.
        val headerHeight = maxOf(288.dp, minOf(maxWidth * 9f / 16f, windowHeight * 0.5f))
        Box(modifier = Modifier.height(headerHeight).clipToBounds()) {
            backdropImage()
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(Color.Black.copy(alpha = 0.1f))
                drawRect(
                    brush =
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, backgroundColor),
                            startY = 0f,
                        )
                )
                drawTopScrimOverArt()
            }
            content()
            if (showLogo) {
                AsyncImage(
                    model = logoUri,
                    contentDescription = null,
                    modifier =
                        Modifier.align(Alignment.BottomCenter)
                            .padding(MaterialTheme.spacings.default)
                            .height(100.dp)
                            .fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}
