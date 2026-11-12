package dev.jdtech.jellyfin.presentation.film.components

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDirection
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.FindroidExternalLink
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.airSchedule
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.utils.copyOnLongClick
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/*
 * CE (#332): the metadata the server already sends that the detail pages used to drop. Every
 * composable here draws nothing for an empty value, so a server without it shows no gap.
 */

/**
 * The Rotten Tomatoes score next to the community rating. Not violet: the star is the user's own
 * claim (VISION §1.3), a critic score is not, so it takes the surrounding text colour.
 */
@Composable
fun CriticRating(criticRating: Float?) {
    val rating = criticRating ?: return
    val text = "${rating.toInt()}%"
    val description = stringResource(CoreR.string.item_critic_rating)
    Row(
        modifier =
            Modifier.copyOnLongClick(text).semantics(mergeDescendants = true) {
                contentDescription = "$description $text"
            },
        verticalAlignment = Alignment.Bottom,
    ) {
        Icon(
            painter = painterResource(CoreR.drawable.ic_tomato),
            contentDescription = null,
            tint = LocalContentColor.current,
        )
        Spacer(Modifier.width(MaterialTheme.spacings.extraSmall))
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The tagline, above the overview. */
@Composable
fun TaglineText(tagline: String?, modifier: Modifier = Modifier) {
    val text = tagline ?: return
    Text(
        text = text,
        modifier = modifier.copyOnLongClick(text),
        // CE (#401): server text sets its own direction, as in OverviewText.
        style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content),
        fontStyle = FontStyle.Italic,
    )
}

/** "Watched 3× · last on 12 Oct 2026", for an item the server saw played to the end. */
@Composable
fun WatchHistoryText(playCount: Int, lastPlayedDate: LocalDateTime?) {
    if (playCount <= 0) return
    val locale = currentLocale()
    val text =
        lastPlayedDate?.let { date ->
            stringResource(
                CoreR.string.item_watch_count_last,
                playCount,
                date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)),
            )
        } ?: stringResource(CoreR.string.item_watch_count, playCount)
    Text(
        text = text,
        modifier = Modifier.copyOnLongClick(text),
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** "Airs Thursday 21:00", only while the show is still running. */
@Composable
fun AirScheduleText(show: FindroidShow) {
    val schedule = show.airSchedule(currentLocale(), stringResource(CoreR.string.item_airs_daily)) ?: return
    val text = stringResource(CoreR.string.item_airs, schedule)
    Text(
        text = text,
        modifier = Modifier.copyOnLongClick(text),
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** "Open on IMDb / TMDB / TVDB / Trakt" as pill chips that open the browser. */
@Composable
fun ExternalLinksRow(links: List<FindroidExternalLink>, onLinkClick: (url: String) -> Unit) {
    if (links.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
    ) {
        links.forEach { link ->
            val description = stringResource(CoreR.string.item_open_on, link.name)
            AssistChip(
                onClick = { onLinkClick(link.url) },
                label = { Text(text = link.name) },
                modifier = Modifier.semantics { contentDescription = description },
                trailingIcon = {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_external_link),
                        contentDescription = null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                    )
                },
                shape = CircleShape,
                colors =
                    AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        trailingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                border = null,
            )
        }
    }
}

/**
 * A titled row of item cards at the bottom of a detail page (extras, collections, "More like
 * this"). Nothing at all when [items] is empty.
 */
@Composable
fun ItemsRow(
    title: String,
    items: List<FindroidItem>,
    direction: Direction,
    onItemClick: (FindroidItem) -> Unit,
    contentPadding: PaddingValues,
) {
    if (items.isEmpty()) return
    Column {
        Text(
            text = title,
            modifier = Modifier.padding(contentPadding),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(MaterialTheme.spacings.small))
        LazyRow(
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
        ) {
            items(items, key = { it.id }) { item ->
                ItemCard(item = item, direction = direction, onClick = onItemClick)
            }
        }
        Spacer(Modifier.height(MaterialTheme.spacings.medium))
    }
}

@Composable
private fun currentLocale(): Locale = LocalConfiguration.current.locales.get(0)
