package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.localnetwork.LocalLocalNetworkBlock
import dev.jdtech.jellyfin.core.presentation.localnetwork.LocalNetworkBlock
import dev.jdtech.jellyfin.core.presentation.localnetwork.localNetworkBlockFor
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.setup.R as SetupR
import java.net.SocketTimeoutException

/**
 * The height of an [ErrorCard] before it is measured: its row of icon buttons. A screen that lays
 * the card over its content measures the card and moves the content down by its real height, which
 * is more with a larger font or with the "Allow access" row (#571).
 */
internal val ErrorCardInitialHeight = 48.dp

/**
 * A failed load, above content that is still on screen.
 *
 * CE (#571): with [error] given, a server that is out of reach because local network access is
 * denied says so and offers to allow it; the screen loads again by itself once access is back.
 */
@Composable
fun ErrorCard(
    onShowStacktrace: () -> Unit,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
    error: Throwable? = null,
) {
    val block = error?.let { localNetworkBlockFor(error = it, onUnblocked = onRetryClick) }
    val request = block?.request

    OutlinedCard(
        modifier = modifier,
        colors =
            CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.error),
    ) {
        Column {
            Row {
                Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                Icon(
                    painter = painterResource(CoreR.drawable.ic_alert_circle),
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                Spacer(modifier = Modifier.width(MaterialTheme.spacings.small))
                Text(
                    text =
                        stringResource(
                            if (block != null) {
                                SetupR.string.ce_local_network_off
                            } else {
                                CoreR.string.error_loading_data
                            }
                        ),
                    modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                )
                IconButton(onClick = onShowStacktrace) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_logs),
                        contentDescription = stringResource(CoreR.string.show_stacktrace),
                    )
                }
                IconButton(onClick = onRetryClick) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_rotate_ccw),
                        contentDescription = stringResource(CoreR.string.retry),
                    )
                }
            }
            if (request != null) {
                TextButton(
                    onClick = request,
                    modifier =
                        Modifier.align(Alignment.End)
                            .padding(horizontal = MaterialTheme.spacings.small),
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                ) {
                    Text(text = stringResource(SetupR.string.ce_local_network_allow))
                }
            }
        }
    }
}

@Preview
@Composable
private fun ErrorCardPreview() {
    FindroidTheme { ErrorCard(onShowStacktrace = {}, onRetryClick = {}) }
}

@Preview
@Composable
private fun ErrorCardLocalNetworkPreview() {
    FindroidTheme {
        CompositionLocalProvider(
            LocalLocalNetworkBlock provides LocalNetworkBlock(blocked = true, request = {})
        ) {
            ErrorCard(
                onShowStacktrace = {},
                onRetryClick = {},
                error = SocketTimeoutException(),
            )
        }
    }
}
