package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.EpisodeDownloadChoice
import dev.jdtech.jellyfin.presentation.components.BaseDialog
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings

/**
 * CE (#636): the first question of a season or show download, asked only when some of its episodes
 * are watched: download only the unwatched ones, or all of them. The quality is asked next, in
 * [DownloadQualityDialog], which this is laid out like. Dismissing it downloads nothing.
 */
@Composable
fun DownloadUnwatchedDialog(
    choice: EpisodeDownloadChoice,
    onSelect: (unwatchedOnly: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    BaseDialog(
        title = stringResource(CoreR.string.ce_download_choice_title),
        onDismiss = onDismiss,
    ) { contentPadding ->
        Text(
            text = stringResource(CoreR.string.ce_download_choice_message),
            modifier = Modifier.padding(contentPadding),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))
        // Every episode left to download is watched: there is nothing unwatched to offer.
        if (choice.unwatchedCount > 0) {
            ChoiceRow(
                text =
                    stringResource(
                        CoreR.string.ce_download_choice_unwatched,
                        choice.unwatchedCount,
                    ),
                onClick = { onSelect(true) },
            )
        }
        ChoiceRow(
            text = stringResource(CoreR.string.ce_download_choice_all, choice.allCount),
            onClick = { onSelect(false) },
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.default))
    }
}

@Composable
private fun ChoiceRow(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(
                    horizontal = MaterialTheme.spacings.default,
                    vertical = MaterialTheme.spacings.medium,
                ),
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Preview
@Composable
private fun DownloadUnwatchedDialogPreview() {
    FindroidTheme {
        DownloadUnwatchedDialog(
            choice = EpisodeDownloadChoice(allCount = 10, unwatchedCount = 7),
            onSelect = {},
            onDismiss = {},
        )
    }
}
