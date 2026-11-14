package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.Layout
import dev.jdtech.jellyfin.presentation.utils.LocalServerStatus
import dev.jdtech.jellyfin.presentation.utils.RetryWhenServerReturns
import dev.jdtech.jellyfin.presentation.utils.isServerUnreachable
import dev.jdtech.jellyfin.presentation.utils.showsServerBanner

/** From this width the banner is one row: text, then the buttons. */
private val OneRowMinWidth = 560.dp

/**
 * CE (#281): the server does not answer. A state, not a failed action, so it is not red: a neutral
 * card under the screen's header that blocks nothing. With downloads on the device it says they
 * still play and leads to them; without any it only says so and offers to try again.
 */
@Composable
fun ServerUnreachableCard(
    hasDownloads: Boolean,
    onRetryClick: () -> Unit,
    onDownloadsClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val downloads = onDownloadsClick.takeIf { hasDownloads }

    Surface(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BoxWithConstraints {
            // One row when there is room, or when Retry is the only button.
            val oneRow = maxWidth >= OneRowMinWidth || downloads == null
            if (oneRow) {
                Row(
                    modifier =
                        Modifier.padding(
                            start = MaterialTheme.spacings.medium,
                            top = MaterialTheme.spacings.small,
                            end = MaterialTheme.spacings.small,
                            bottom = MaterialTheme.spacings.small,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Message(showDownloadsLine = downloads != null, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(MaterialTheme.spacings.small))
                    Buttons(onRetryClick = onRetryClick, onDownloadsClick = downloads)
                }
            } else {
                Column(
                    modifier =
                        Modifier.padding(
                            start = MaterialTheme.spacings.medium,
                            top = MaterialTheme.spacings.medium,
                            end = MaterialTheme.spacings.small,
                            bottom = MaterialTheme.spacings.small,
                        )
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Message(showDownloadsLine = true, modifier = Modifier.weight(1f))
                    }
                    Row(modifier = Modifier.align(Alignment.End)) {
                        Buttons(onRetryClick = onRetryClick, onDownloadsClick = downloads)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.Message(showDownloadsLine: Boolean, modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(CoreR.drawable.ic_server_off),
        contentDescription = null,
        modifier = Modifier.size(24.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.width(MaterialTheme.spacings.medium))
    Column(modifier = modifier) {
        Text(
            text = stringResource(CoreR.string.download_error_server_unreachable),
            style = MaterialTheme.typography.titleSmall,
        )
        if (showDownloadsLine) {
            Text(
                text = stringResource(CoreR.string.ce_server_unreachable_downloads),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Buttons(onRetryClick: () -> Unit, onDownloadsClick: (() -> Unit)?) {
    TextButton(onClick = onRetryClick) { Text(stringResource(CoreR.string.retry)) }
    if (onDownloadsClick != null) {
        Spacer(Modifier.width(MaterialTheme.spacings.small))
        FilledTonalButton(onClick = onDownloadsClick) {
            Text(stringResource(CoreR.string.title_download))
        }
    }
}

/**
 * Whether [ServerOrErrorCard] shows anything for [error], so a screen that lays the card over its
 * content can make room for it.
 */
@Composable
fun showsServerOrErrorCard(error: Throwable?): Boolean = error != null || showsServerBanner()

/**
 * The card under a screen's header: the unreachable banner while the server does not answer, the
 * error card for any other failed load ([error]), nothing otherwise. The banner stands in for the
 * error card when the error is the server not answering, so one thing says it. Both load again with
 * [onRetryClick], and by themselves when the server is back.
 *
 * [modifier] carries the screen's padding. The width is set here: the error card fills it, the
 * banner stops at the width of a text column (docs/tablet/4-design.md).
 */
@Composable
fun ServerOrErrorCard(
    error: Throwable?,
    onShowStacktrace: () -> Unit,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = LocalServerStatus.current
    RetryWhenServerReturns(error = error, onRetry = onRetryClick)

    if (showsServerBanner() && (error == null || isServerUnreachable(error))) {
        ServerUnreachableCard(
            hasDownloads = status.hasDownloads,
            onRetryClick = {
                status.check()
                onRetryClick()
            },
            onDownloadsClick = status.openDownloads,
            modifier = modifier.widthIn(max = Layout.textMaxWidth).fillMaxWidth(),
        )
    } else if (error != null) {
        ErrorCard(
            onShowStacktrace = onShowStacktrace,
            onRetryClick = onRetryClick,
            modifier = modifier.fillMaxWidth(),
            error = error,
        )
    }
}

@Preview
@Composable
private fun ServerUnreachableCardPreview() {
    FindroidTheme {
        ServerUnreachableCard(hasDownloads = true, onRetryClick = {}, onDownloadsClick = {})
    }
}

@Preview
@Composable
private fun ServerUnreachableCardNoDownloadsPreview() {
    FindroidTheme {
        ServerUnreachableCard(hasDownloads = false, onRetryClick = {}, onDownloadsClick = {})
    }
}
