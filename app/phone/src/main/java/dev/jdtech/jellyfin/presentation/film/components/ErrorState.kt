package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.localnetwork.localNetworkBlockFor
import dev.jdtech.jellyfin.presentation.components.ErrorDialog
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.utils.localNetworkDeniedText
import dev.jdtech.jellyfin.setup.R as SetupR

/** Stands in for a screen's content when it failed to load. */
@Composable
fun ErrorState(error: Exception, onRetryClick: () -> Unit, modifier: Modifier = Modifier) {
    var showErrorDialog by rememberSaveable { mutableStateOf(false) }
    // CE (#571): a server out of reach because local network access is denied says so, offers to
    // allow it, and loads again by itself once access is back.
    val block = localNetworkBlockFor(error = error, onUnblocked = onRetryClick)

    EmptyState(
        icon = CoreR.drawable.ic_alert_circle,
        title = stringResource(CoreR.string.error_loading_data),
        modifier = modifier,
        body =
            if (block != null) {
                localNetworkDeniedText(block)
            } else {
                stringResource(CoreR.string.error_loading_data_body)
            },
        iconContainerColor = MaterialTheme.colorScheme.errorContainer,
        iconColor = MaterialTheme.colorScheme.onErrorContainer,
        specks = false,
        actions = {
            if (block != null) {
                block.request?.let { request ->
                    FilledTonalButton(onClick = request) {
                        Text(stringResource(SetupR.string.ce_local_network_allow))
                    }
                }
                TextButton(onClick = onRetryClick) { Text(stringResource(CoreR.string.retry)) }
            } else {
                FilledTonalButton(onClick = onRetryClick) {
                    Text(stringResource(CoreR.string.retry))
                }
                TextButton(onClick = { showErrorDialog = true }) {
                    Text(stringResource(CoreR.string.show_stacktrace))
                }
            }
        },
    )

    if (showErrorDialog && block == null) {
        ErrorDialog(
            exception = error,
            onDismissRequest = { showErrorDialog = false },
            onRetry = onRetryClick,
        )
    }
}

/** What a detail screen shows until its item is there: a spinner, or [error] if loading failed. */
@Composable
fun BoxScope.LoadingOrError(error: Exception?, onRetryClick: () -> Unit) {
    if (error != null) {
        ErrorState(error = error, onRetryClick = onRetryClick, modifier = Modifier.fillMaxSize())
    } else {
        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
    }
}

@Preview(showBackground = true)
@Composable
private fun ErrorStatePreview() {
    FindroidTheme { ErrorState(error = Exception("Failed to load data"), onRetryClick = {}) }
}
