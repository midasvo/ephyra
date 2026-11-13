package dev.jdtech.jellyfin.presentation.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.jdtech.jellyfin.core.presentation.localnetwork.LocalLocalNetworkBlock
import dev.jdtech.jellyfin.core.presentation.localnetwork.UnblockRetry
import dev.jdtech.jellyfin.utils.LoadErrorText

/**
 * CE (#281): what the screens know about the server while the app runs.
 *
 * [unreachable] shows the banner and the "Downloads" action of the error page. Only
 * [confirmedUnreachable] takes away what needs the server (downloading, trailers): one failed probe
 * can be a slow server, and the buttons must not flash for that. [hasDownloads] says whether there
 * is anything in Downloads to point at. [check] asks again without waiting for the answer.
 * [openDownloads] opens the Downloads tab; null where there is no such tab to go to (setup, the
 * Downloads tab itself).
 */
@Immutable
data class ServerStatusUi(
    val unreachable: Boolean = false,
    val confirmedUnreachable: Boolean = false,
    val hasDownloads: Boolean = true,
    val check: () -> Unit = {},
    val openDownloads: (() -> Unit)? = null,
)

/** Reachable unless provided otherwise, so previews and tests need not provide it. */
val LocalServerStatus = compositionLocalOf { ServerStatusUi() }

/**
 * Whether a screen shows the unreachable banner. Not while Android's local network permission is
 * what keeps the server away: then the error card of #571 names the permission and asks for it.
 */
@Composable
fun showsServerBanner(): Boolean =
    LocalServerStatus.current.unreachable && !LocalLocalNetworkBlock.current.blocked

/**
 * Whether [error] is the server being unreachable, as the banner already says: a connection error
 * while the banner shows.
 */
@Composable
fun isServerUnreachable(error: Throwable?): Boolean {
    val connectionError = remember(error) { error != null && LoadErrorText.isConnectionError(error) }
    return connectionError && showsServerBanner()
}

/**
 * Loads again, once, when the server answers again while [error] is on screen because it did not.
 * The rule is [UnblockRetry]'s: only the step from "explained by the server being away" to "the
 * server is back" retries, so a retry that fails for another reason shows that error and stops.
 */
@Composable
fun RetryWhenServerReturns(error: Throwable?, onRetry: () -> Unit) {
    val unreachable = LocalServerStatus.current.unreachable
    val connectionError = remember(error) { error != null && LoadErrorText.isConnectionError(error) }
    val explained = connectionError && unreachable
    val retry = remember { UnblockRetry() }
    val currentOnRetry by rememberUpdatedState(onRetry)
    val stillFailed by rememberUpdatedState(connectionError)
    LaunchedEffect(explained, unreachable) {
        // Not when the error is gone already: the user's own retry was what reached the server.
        if (retry.shouldRetry(explained, unreachable) && stillFailed) currentOnRetry()
    }
}
