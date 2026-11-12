package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme

@Composable
fun PlayedBadge(modifier: Modifier = Modifier) {
    BaseBadge(modifier = modifier, color = MaterialTheme.colorScheme.tertiary) {
        Icon(
            painter = painterResource(CoreR.drawable.ic_check),
            // #441: not "Episode watched indicator", which a movie or a show card said too.
            contentDescription = stringResource(CoreR.string.ce_watched),
            tint = MaterialTheme.colorScheme.onTertiary,
            modifier = Modifier.size(16.dp).align(Alignment.Center),
        )
    }
}

@Composable
@Preview
private fun PlayedBadgePreview() {
    FindroidTheme { PlayedBadge() }
}
