package dev.jdtech.jellyfin.core.presentation.localnetwork

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.jdtech.jellyfin.utils.LoadErrorText

/**
 * CE (#571): what the error components know about Android 17's local network permission.
 *
 * [blocked] is true while access is denied and the server in use is on the local network.
 * [settingName] is the name the system settings list the permission under, when the system tells.
 * [refresh] reads both again. [request] asks for the permission (the system dialog, or the app's
 * page in the system settings once the system shows no dialog); null where the screen only says
 * what is wrong, as on TV.
 */
@Immutable
data class LocalNetworkBlock(
    val blocked: Boolean = false,
    val settingName: String? = null,
    val refresh: () -> Unit = {},
    val request: (() -> Unit)? = null,
)

val LocalLocalNetworkBlock = compositionLocalOf { LocalNetworkBlock() }

/**
 * Decides the one retry after access came back. It fires only on the step from "this error is
 * explained by the block" to "the block is gone", and then waits for the next such error: a retry
 * that fails for another reason shows that error and nothing retries again.
 */
class UnblockRetry {
    private var armed = false

    /**
     * [explained] is whether the error on screen is explained by the block, [blocked] whether the
     * server is blocked now. Returns true when the screen should load again.
     */
    fun shouldRetry(explained: Boolean, blocked: Boolean): Boolean {
        if (explained) {
            armed = true
            return false
        }
        if (armed && !blocked) {
            armed = false
            return true
        }
        return false
    }
}

/**
 * The block when it explains [error]: a connection error while the server is blocked. Null for any
 * other error, which keeps its usual text.
 *
 * With [onUnblocked] the screen loads again, once, when access comes back while the error is on
 * screen: the user allowed it in the dialog, or returned from the system settings.
 */
@Composable
fun localNetworkBlockFor(error: Throwable, onUnblocked: (() -> Unit)? = null): LocalNetworkBlock? {
    val block = LocalLocalNetworkBlock.current
    val connectionError = remember(error) { LoadErrorText.isConnectionError(error) }
    // The server may have changed since the last look, and nothing resumed in between.
    LaunchedEffect(error) { if (connectionError) block.refresh() }

    val explained = connectionError && block.blocked
    if (onUnblocked != null) {
        val retry = remember { UnblockRetry() }
        val currentOnUnblocked by rememberUpdatedState(onUnblocked)
        LaunchedEffect(explained, block.blocked) {
            if (retry.shouldRetry(explained, block.blocked)) currentOnUnblocked()
        }
    }
    return block.takeIf { explained }
}
