package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.Layout
import dev.jdtech.jellyfin.presentation.utils.WidthClass
import dev.jdtech.jellyfin.presentation.utils.drawTopScrimOverArt
import dev.jdtech.jellyfin.presentation.utils.parallaxLayoutModifier
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding
import dev.jdtech.jellyfin.utils.copyOnLongClick

/**
 * The backdrop hero of an item page at Medium and Expanded width (docs/tablet/4-design.md §6): a
 * full-width backdrop band, cropped from the top centre, with the poster (or an episode's still)
 * overlapping its bottom edge and the item's text column beside it, below the backdrop on the
 * page background.
 *
 * Nothing but the poster and the top bar is drawn over the artwork, so the text keeps the theme's
 * colours and stays readable over any backdrop (#339).
 */
@Composable
fun ItemHeroHeader(
    item: FindroidItem,
    scrollState: ScrollState,
    widthClass: WidthClass,
    title: @Composable ColumnScope.() -> Unit,
    artwork: ArtworkState = rememberArtworkState(item, ArtworkPlacement.Hero),
    content: @Composable ColumnScope.() -> Unit,
) {
    val safePadding = rememberSafePadding()
    val margin = Layout.margin(widthClass)
    val pageBackground = MaterialTheme.colorScheme.background

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val windowHeight =
            with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
        // The form follows the room this header has (a Season detail pane, a split-screen
        // half), not the window's width class (#429).
        val innerWidth = maxWidth - safePadding.start - safePadding.end - margin * 2
        val stacked = heroStacked(innerWidth, heroPosterWidth(item, widthClass), margin)
        val posterWidth =
            if (stacked) minOf(heroPosterWidth(item, widthClass), innerWidth)
            else heroPosterWidth(item, widthClass)
        // The poster hangs half its height over the backdrop's bottom edge.
        val posterOverlap = heroPosterHeight(item, posterWidth) / 2
        val heroHeight =
            if (artwork.hasArtwork) Layout.heroHeight(maxWidth, windowHeight)
            else collapsedHeroHeight(safePadding.top, posterOverlap)

        if (artwork.hasArtwork) {
            Box(modifier = Modifier.fillMaxWidth().height(heroHeight).clipToBounds()) {
                ArtworkBackdrop(
                    state = artwork,
                    modifier =
                        Modifier.matchParentSize()
                            .parallaxLayoutModifier(scrollState = scrollState, rate = 2),
                    alignment = Alignment.TopCenter,
                )
                Canvas(modifier = Modifier.matchParentSize()) {
                    drawRect(
                        brush =
                            Brush.verticalGradient(
                                0.5f to Color.Transparent,
                                1f to pageBackground,
                            )
                    )
                    drawTopScrimOverArt()
                }
            }
        }
        val headerPadding =
            Modifier.fillMaxWidth()
                .padding(
                    start = safePadding.start + margin,
                    top = heroHeight - posterOverlap,
                    end = safePadding.end + margin,
                )
        if (stacked) {
            // Too narrow for a column beside the poster: the text goes below it.
            Column(modifier = headerPadding) {
                HeroPoster(item = item, posterWidth = posterWidth)
                Column(modifier = Modifier.widthIn(max = Layout.textMaxWidth)) {
                    Spacer(Modifier.height(MaterialTheme.spacings.medium))
                    HeroText(title = title, content = content)
                }
            }
        } else {
            Row(modifier = headerPadding, horizontalArrangement = Arrangement.spacedBy(margin)) {
                HeroPoster(item = item, posterWidth = posterWidth)
                Column(
                    modifier = Modifier.weight(1f, fill = false).widthIn(max = Layout.textMaxWidth)
                ) {
                    Spacer(Modifier.height(posterOverlap + MaterialTheme.spacings.medium))
                    HeroText(title = title, content = content)
                }
            }
        }
    }
}

/**
 * CE (#433): the hero without any artwork is only the top bar's room and the half of the poster
 * that would hang over the backdrop, so the poster starts right below the top bar.
 */
internal fun collapsedHeroHeight(safeTop: Dp, posterOverlap: Dp): Dp =
    safeTop + Layout.topBarHeight + posterOverlap

/** The narrowest text column beside the poster; below it the header stacks (4-design.md §6). */
internal val HeroMinColumnWidth: Dp = 360.dp

/**
 * True when a hero header [innerWidth] wide (inside its margins) has less than
 * [HeroMinColumnWidth] beside a poster [posterWidth] wide and the [gap] after it, so the text
 * column goes below the poster instead of beside it (#429).
 */
internal fun heroStacked(innerWidth: Dp, posterWidth: Dp, gap: Dp): Boolean =
    innerWidth - posterWidth - gap < HeroMinColumnWidth

@Composable
private fun ColumnScope.HeroText(
    title: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        title()
        content()
    }
}

/** Title of the hero: `headlineLarge` at Expanded, `headlineMedium` at Medium. */
@Composable
fun HeroTitle(text: String, widthClass: WidthClass) {
    Text(
        text = text,
        modifier = Modifier.copyOnLongClick(text),
        overflow = TextOverflow.Ellipsis,
        maxLines = 3,
        style = heroTitleStyle(widthClass),
    )
}

/** A secondary line next to the title: an original title or an episode's season and number. */
@Composable
fun HeroSubtitle(text: String, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    Text(
        text = text,
        modifier = Modifier.copyOnLongClick(text),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        style = style,
    )
}

/**
 * The metadata line: plain texts (year, runtime, rating…) followed by the community rating star
 * and (CE, #332) the critic score, built the same way as on the compact screens.
 */
@Composable
fun HeroMetadataLine(texts: List<String>, communityRating: Float?, criticRating: Float? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        verticalAlignment = Alignment.Bottom,
    ) {
        texts.forEach { text ->
            Text(
                text = text,
                modifier = Modifier.copyOnLongClick(text),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        communityRating?.let { rating ->
            val ratingText = "%.1f".format(rating)
            Row(
                modifier = Modifier.copyOnLongClick(ratingText),
                verticalAlignment = Alignment.Bottom,
            ) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_star),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.width(MaterialTheme.spacings.extraSmall))
                Text(text = ratingText, style = MaterialTheme.typography.bodyMedium)
            }
        }
        CriticRating(criticRating)
    }
}

/** Chips in the hero keep their own on-container colour instead of the text colour. */
@Composable
fun HeroChips(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
        content = content,
    )
}

@Composable
private fun heroTitleStyle(widthClass: WidthClass): TextStyle =
    when (widthClass) {
        WidthClass.Expanded -> MaterialTheme.typography.headlineLarge
        else -> MaterialTheme.typography.headlineMedium
    }

/** An episode shows its 16:9 still, 1.6 × the poster width; anything else a 2:3 poster. */
private fun heroPosterWidth(item: FindroidItem, widthClass: WidthClass): Dp =
    Layout.posterInHeader(widthClass) * if (item is FindroidEpisode) 1.6f else 1f

private fun heroPosterHeight(item: FindroidItem, posterWidth: Dp): Dp =
    posterWidth / if (item is FindroidEpisode) 1.77f else 0.66f

@Composable
private fun HeroPoster(item: FindroidItem, posterWidth: Dp) {
    if (item is FindroidEpisode) {
        ItemPoster(
            item = item,
            direction = Direction.HORIZONTAL,
            modifier = Modifier.width(posterWidth).clip(MaterialTheme.shapes.small),
        )
    } else {
        ItemPoster(
            item = item,
            direction = Direction.VERTICAL,
            modifier = Modifier.width(posterWidth).clip(MaterialTheme.shapes.small),
        )
    }
}
