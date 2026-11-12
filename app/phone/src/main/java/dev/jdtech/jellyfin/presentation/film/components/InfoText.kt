package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.FindroidItemPerson
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.utils.copyOnLongClick

const val INFO_GENRES_TAG = "info_genres"
const val INFO_CREDIT_TAG = "info_credit"

/**
 * What an item is and who made it, under its overview.
 *
 * CE (#510): the genres are one quiet line ("Animation · Comedy · Family"), which needs no label.
 * The credits are a small table: muted labels in a column as wide as the longest of them, the
 * names beside them. Before, the four were body lines of "Label: value" that read as a second
 * paragraph next to the overview.
 */
@Composable
fun InfoText(
    genres: List<String>,
    director: FindroidItemPerson?,
    writers: List<FindroidItemPerson>,
    // CE (#332): the studios of a movie, or the networks of a show.
    studios: List<String> = emptyList(),
    studiosAreNetworks: Boolean = false,
) {
    val credits =
        listOfNotNull(
            director?.let { stringResource(CoreR.string.director) to it.name },
            writers
                .takeIf { it.isNotEmpty() }
                ?.let { names ->
                    stringResource(CoreR.string.writers) to names.joinToString { it.name }
                },
            studios
                .takeIf { it.isNotEmpty() }
                ?.let {
                    stringResource(
                        if (studiosAreNetworks) CoreR.string.item_networks
                        else CoreR.string.item_studios
                    ) to it.joinToString()
                },
        )
    if (genres.isEmpty() && credits.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium)) {
        if (genres.isNotEmpty()) {
            val genresLabel = stringResource(CoreR.string.genres)
            Text(
                text = genres.joinToString(" · "),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier.copyOnLongClick(genres.joinToString())
                        // The line has no visible label; a screen reader still says what it is.
                        .semantics { contentDescription = "$genresLabel: ${genres.joinToString()}" }
                        .testTag(INFO_GENRES_TAG),
            )
        }
        if (credits.isNotEmpty()) {
            val labelWidth = rememberLabelColumnWidth(credits.map { it.first })
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
                credits.forEach { (label, value) ->
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .copyOnLongClick(value)
                                // Read as one: "Director, Sacha Goedegebure".
                                .semantics(mergeDescendants = true) {}
                                .testTag(INFO_CREDIT_TAG),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(labelWidth).alignByBaseline(),
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).alignByBaseline(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The width of the widest of [labels] on one line, so the names start on one edge. Capped: a long
 * label in some language wraps in its column instead of squeezing the names out.
 */
@Composable
private fun rememberLabelColumnWidth(labels: List<String>): Dp {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    return remember(labels, style, density) {
        val widest = labels.maxOf { measurer.measure(it, style, maxLines = 1).size.width }
        // One pixel over the measured text, which the layout would otherwise round away.
        with(density) { (widest + 1).toDp() }.coerceAtMost(MaxLabelColumnWidth)
    }
}

private val MaxLabelColumnWidth = 140.dp

@Preview(showBackground = true)
@Composable
private fun InfoTextPreview() {
    fun person(name: String) =
        FindroidItemPerson(
            id = java.util.UUID.randomUUID(),
            name = name,
            type = org.jellyfin.sdk.model.api.PersonKind.DIRECTOR,
            role = "",
            image = dev.jdtech.jellyfin.models.FindroidItemPersonImage(null, null),
        )
    FindroidTheme {
        InfoText(
            genres = listOf("Animation", "Comedy", "Family"),
            director = person("Sacha Goedegebure"),
            writers = listOf(person("Sacha Goedegebure"), person("Ton Roosendaal")),
            studios = listOf("Blender Foundation"),
        )
    }
}
