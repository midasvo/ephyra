package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings

@Composable
fun ItemCountBadge(unplayedItemCount: Int, modifier: Modifier = Modifier) {
    // #441: a screen reader says "5 unwatched", not a bare number.
    val description =
        pluralStringResource(CoreR.plurals.ce_unwatched_count, unplayedItemCount, unplayedItemCount)
    BaseBadge(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = unplayedItemCount.toString(),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.labelMedium,
            modifier =
                Modifier.align(Alignment.Center)
                    .padding(horizontal = MaterialTheme.spacings.extraSmall),
        )
    }
}

@Composable
@Preview
private fun ItemCountBadgePreview() {
    FindroidTheme { ItemCountBadge(110) }
}
