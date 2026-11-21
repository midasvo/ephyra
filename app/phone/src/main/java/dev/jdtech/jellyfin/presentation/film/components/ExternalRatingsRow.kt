package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.presentation.ephyra.EphyraMotion
import dev.jdtech.jellyfin.film.presentation.ExternalRatingsState
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.ratings.ExternalRating
import dev.jdtech.jellyfin.ratings.RatingSource

/** What a pending row measures, so the ratings take exactly the room kept for them. */
private val placeholder = listOf(ExternalRating(RatingSource.IMDB, 0f))

/**
 * CE (#331): MDBList's scores under the metadata line, as neutral pill chips (VISION §3.4: chips
 * are never coloured). Nothing at all when [state] is hidden. While pending, an invisible chip
 * keeps the row's height, and the scores fade in over it when they come, so nothing below moves.
 * One line that scrolls sideways when it doesn't fit, so its height never changes with the scores.
 */
@Composable
fun ExternalRatingsRow(state: ExternalRatingsState, modifier: Modifier = Modifier) {
    if (state is ExternalRatingsState.Hidden) return
    val ratings = (state as? ExternalRatingsState.Shown)?.ratings
    val alpha by
        animateFloatAsState(
            targetValue = if (ratings != null) 1f else 0f,
            animationSpec = tween(EphyraMotion.DURATION_MEDIUM, easing = EphyraMotion.EasingEnter),
            label = "externalRatingsAlpha",
        )
    Row(
        modifier =
            modifier
                .horizontalScroll(rememberScrollState())
                .graphicsLayer { this.alpha = alpha }
                .then(if (ratings == null) Modifier.clearAndSetSemantics {} else Modifier),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        (ratings ?: placeholder).forEach { RatingChip(it) }
    }
}

@Composable
private fun RatingChip(rating: ExternalRating) {
    val locale = LocalConfiguration.current.locales.get(0)
    val value = rating.source.format(rating.value, locale)
    val description = "${rating.source.label} $value"
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.extraSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = rating.source.label, style = MaterialTheme.typography.labelMedium)
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            )
        }
    }
}
