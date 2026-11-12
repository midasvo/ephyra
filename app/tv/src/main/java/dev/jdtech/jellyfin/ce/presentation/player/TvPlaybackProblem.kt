package dev.jdtech.jellyfin.ce.presentation.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.media3.common.Player
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import dev.jdtech.jellyfin.player.local.R as PlayerR
import dev.jdtech.jellyfin.presentation.theme.spacings
import kotlinx.coroutines.delay

/** CE (#463): what stopped playback, as the TV player's dialog tells it. */
internal enum class PlaybackProblem {
    /**
     * The connection was lost. The player loads again by itself when it is back (#450), and
     * "Try again" does the same at once.
     */
    WAITING_FOR_NETWORK,

    /** The player reported an error on an item it has loaded. "Try again" loads that item again. */
    ERROR,

    /** The item to start with could not be fetched. "Try again" starts over. */
    START_FAILED,
}

/**
 * CE (#463): the problem the TV player shows, or null while there is none.
 *
 * The wait for the network comes from the ViewModel's state, not from the error event: a retry that
 * fails while waiting sends no new event, and the wait ends by itself when playback resumes. While
 * the player waits it is not an error yet, whatever error came before.
 *
 * @param waitingForNetwork `PlayerViewModel.UiState.waitingForNetwork`
 * @param hasError an error event came that was not the start of a wait, and playback has not
 *   resumed or been retried since
 * @param nothingLoaded that error left the player without an item
 */
internal fun playbackProblem(
    waitingForNetwork: Boolean,
    hasError: Boolean,
    nothingLoaded: Boolean,
): PlaybackProblem? =
    when {
        waitingForNetwork -> PlaybackProblem.WAITING_FOR_NETWORK
        !hasError -> null
        nothingLoaded -> PlaybackProblem.START_FAILED
        else -> PlaybackProblem.ERROR
    }

/**
 * CE (#463): what "Try again" and the Play button do to a player that stopped: a player that is
 * idle after an error has to load again first, at the position it stopped at, and `play()` alone
 * leaves it idle. An idle player without an error is still starting and is left to it.
 */
internal fun retryPlayback(player: Player) {
    if (player.playbackState == Player.STATE_IDLE && player.playerError != null) player.prepare()
    player.play()
}

/**
 * CE (#131, #463): playback stopped. A message in plain words with "Try again" (which has focus,
 * also each time the dialog comes back after a retry failed) and "Exit player". Back exits too.
 * The player's own error text is not shown: it is in the log, with the error code
 * (`PlayerViewModel.onPlayerError`).
 *
 * @param retrying the player is loading again: a spinner next to the buttons says the press on
 *   "Try again" did something, while the dialog stays up until playback resumes.
 */
@Composable
internal fun TvPlaybackProblemDialog(
    problem: PlaybackProblem,
    retrying: Boolean,
    onRetry: () -> Unit,
    onExit: () -> Unit,
) {
    Dialog(onDismissRequest = onExit) {
        Surface(
            modifier = Modifier.width(560.dp),
            shape = RoundedCornerShape(12.dp),
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                modifier = Modifier.padding(MaterialTheme.spacings.large),
            ) {
                Text(
                    text =
                        stringResource(
                            if (problem == PlaybackProblem.WAITING_FOR_NETWORK) {
                                PlayerR.string.ce_player_connection_lost
                            } else {
                                PlayerR.string.player_error_title
                            }
                        ),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val retryFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    repeat(10) {
                        if (retryFocus.requestFocus()) return@LaunchedEffect
                        delay(16)
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = onRetry, modifier = Modifier.focusRequester(retryFocus)) {
                        Text(text = stringResource(PlayerR.string.ce_player_try_again))
                    }
                    Button(onClick = onExit) {
                        Text(text = stringResource(PlayerR.string.player_controls_exit))
                    }
                    if (retrying) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}
