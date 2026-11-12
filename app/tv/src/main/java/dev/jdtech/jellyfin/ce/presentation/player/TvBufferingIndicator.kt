package dev.jdtech.jellyfin.ce.presentation.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.delay

/**
 * CE (#486): whether the TV player shows its buffering indicator: while the player buffers, and
 * while an item has not loaded yet (the first load, the next episode), which includes the wait
 * for the server before the player has anything to buffer. A pending seek (the trickplay
 * thumbnail), the error dialog and the still-watching prompt hide it. It shows while paused too:
 * a seek while paused loads, and the indicator goes once the player is ready.
 */
internal fun bufferingShown(
    playbackState: Int,
    fileLoaded: Boolean,
    hasError: Boolean,
    pendingSeek: Boolean,
    stillWatching: Boolean,
): Boolean =
    !hasError &&
        !pendingSeek &&
        !stillWatching &&
        (playbackState == Player.STATE_BUFFERING || !fileLoaded)

/**
 * CE (#486): a spinner in `primary` on a scrim circle, as the phone player shows. It appears only
 * after [BUFFERING_SHOW_DELAY_MS], so the short buffering of a seek within the cache does not
 * flash it, and goes as soon as [shown] is false.
 */
@Composable
internal fun TvBufferingIndicator(shown: Boolean, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(shown) {
        if (shown) delay(BUFFERING_SHOW_DELAY_MS)
        visible = shown
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(150)),
        modifier = modifier,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(72.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape),
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
        }
    }
}

// A D-pad seek within ExoPlayer's buffer still buffers for up to about a second (0.8–0.95 s on the
// TV emulator), an mpv seek within its cache about 0.2 s; only a wait longer than that shows it.
internal const val BUFFERING_SHOW_DELAY_MS = 1_000L

/**
 * CE (#486): the play/pause button shows Play unless the player means to play. While it buffers it
 * means to play (`isPlaying` is false then), so the button shows Pause and pauses.
 */
internal fun showsPlayButton(playWhenReady: Boolean, playbackState: Int): Boolean =
    !playWhenReady ||
        playbackState == Player.STATE_IDLE ||
        playbackState == Player.STATE_ENDED
