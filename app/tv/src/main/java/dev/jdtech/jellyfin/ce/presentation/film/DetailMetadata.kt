package dev.jdtech.jellyfin.ce.presentation.film

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme as MaterialThemePhone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.jdtech.jellyfin.ce.ui.focus.FocusPosition
import dev.jdtech.jellyfin.ce.ui.focus.focusPosition
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.ephyra.EphyraMotion
import dev.jdtech.jellyfin.film.presentation.ExternalRatingsState
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.ratings.ExternalRating
import dev.jdtech.jellyfin.ratings.RatingSource
import dev.jdtech.jellyfin.ui.components.Direction
import dev.jdtech.jellyfin.ui.components.ItemCard
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/*
 * CE (#332): the metadata the server already sends, on the TV movie and show pages. Each
 * composable draws nothing for an empty value. Outside links are left out on TV: a TV has no
 * browser to open them in.
 */

/** The Rotten Tomatoes score, in the colour of the text around it (it isn't the user's claim). */
@Composable
fun TvCriticRating(criticRating: Float?) {
    val rating = criticRating ?: return
    val text = "${rating.toInt()}%"
    val description = stringResource(CoreR.string.item_critic_rating)
    Row(
        modifier =
            Modifier.semantics(mergeDescendants = true) {
                contentDescription = "$description $text"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(id = CoreR.drawable.ic_tomato),
            contentDescription = null,
            tint = LocalContentColor.current,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(MaterialTheme.spacings.extraSmall))
        Text(text = text, style = MaterialTheme.typography.labelMedium)
    }
}

/** What a pending row measures, so the ratings take exactly the room kept for them. */
private val ratingsPlaceholder = listOf(ExternalRating(RatingSource.IMDB, 0f))

/**
 * CE (#331): MDBList's scores under the metadata row, as neutral pills (VISION §3.4). Nothing when
 * [state] is hidden; while pending, an invisible pill keeps the row's height and the scores fade in
 * over it, so nothing below moves. Not focusable: there is nothing to do with a score.
 */
@Composable
fun TvExternalRatings(state: ExternalRatingsState, modifier: Modifier = Modifier) {
    if (state is ExternalRatingsState.Hidden) return
    val ratings = (state as? ExternalRatingsState.Shown)?.ratings
    val alpha by
        animateFloatAsState(
            targetValue = if (ratings != null) 1f else 0f,
            animationSpec = tween(EphyraMotion.DURATION_MEDIUM, easing = EphyraMotion.EasingEnter),
            label = "externalRatingsAlpha",
        )
    val locale = LocalConfiguration.current.locales.get(0)
    Row(
        modifier =
            modifier
                .graphicsLayer { this.alpha = alpha }
                .then(if (ratings == null) Modifier.clearAndSetSemantics {} else Modifier),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        (ratings ?: ratingsPlaceholder).forEach { rating ->
            val value = rating.source.format(rating.value, locale)
            Row(
                modifier =
                    Modifier.background(
                            MaterialThemePhone.colorScheme.surfaceContainerHigh,
                            CircleShape,
                        )
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "${rating.source.label} $value"
                        },
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.extraSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = rating.source.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun TvTagline(tagline: String?) {
    val text = tagline ?: return
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontStyle = FontStyle.Italic,
        modifier = Modifier.padding(bottom = MaterialTheme.spacings.small),
    )
}

/** "Watched 3× · last on 12 Oct 2026", for an item the server saw played to the end. */
@Composable
fun TvWatchHistory(playCount: Int, lastPlayedDate: LocalDateTime?) {
    if (playCount <= 0) return
    val locale = LocalConfiguration.current.locales.get(0)
    val text =
        lastPlayedDate?.let { date ->
            stringResource(
                CoreR.string.item_watch_count_last,
                playCount,
                date.format(
                    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
                ),
            )
        } ?: stringResource(CoreR.string.item_watch_count, playCount)
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White.copy(alpha = .7f),
    )
}

/** A label over its value, as genres and director are shown under the buttons. */
@Composable
fun TvLabeledValue(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = .5f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The genres, director, writers and studios under the buttons, as label/value columns (#413). A
 * column that doesn't fit beside the others starts a new line, and one wider than the page wraps
 * inside it, so a long list never squeezes the column after it. Empty values are left out.
 */
@Composable
fun TvCredits(columns: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    val shown = columns.filter { (_, value) -> value.isNotBlank() }
    if (shown.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.large),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        modifier = modifier,
    ) {
        shown.forEach { (label, value) -> TvLabeledValue(label = label, value = value) }
    }
}

/** One related row of a TV detail page: its focus row, title, card shape and items. */
data class RelatedRow(
    val row: Int,
    @StringRes val title: Int,
    val direction: Direction,
    val items: List<FindroidItem>,
)

/**
 * The LazyColumn index of focus row [row] when it is one of these related rows, which follow each
 * other from [firstIndex]; null for any other row.
 */
fun List<RelatedRow>.relatedLazyIndex(row: Int, firstIndex: Int): Int? =
    indexOfFirst { it.row == row }.takeIf { it >= 0 }?.let { firstIndex + it }

/**
 * A titled row of cards at the bottom of a detail page (extras, collections, "More like this"), as
 * row [row] of [focusPosition] so Back from an item lands on it again. [rowSpec] is the
 * bring-into-view spec of the row, for a page that turns it off for itself (#514).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvItemsRow(
    title: String,
    items: List<FindroidItem>,
    direction: Direction,
    focusPosition: FocusPosition,
    row: Int,
    rowSpec: BringIntoViewSpec,
    onFocused: () -> Unit,
    onClick: (FindroidItem) -> Unit,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = MaterialTheme.spacings.default * 2),
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))
        CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
                contentPadding = PaddingValues(horizontal = MaterialTheme.spacings.default * 2),
                modifier = Modifier.focusRestorer().onFocusChanged { if (it.hasFocus) onFocused() },
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    ItemCard(
                        item = item,
                        direction = direction,
                        onClick = onClick,
                        modifier =
                            Modifier.focusPosition(focusPosition, row, index, item.id.toString()),
                    )
                }
            }
        }
    }
}
