package dev.jdtech.jellyfin.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Emits when the player has reported a stop, and (CE #633) when the sync has sent progress watched
 * offline: either way what the screen on display shows may have changed.
 */
val LocalPlaybackStopped = staticCompositionLocalOf<Flow<Unit>> { emptyFlow() }

/**
 * CE (#775): runs [onReload] on the events of [LocalPlaybackStopped] only, for a screen that loads
 * by itself on entry. The sync emits once it has sent what was changed while the server was away (a
 * favourite set offline), which can land after the screen's own reload for the server's return.
 */
@Composable
fun ReloadAfterSync(onReload: () -> Unit) {
    val currentOnReload by rememberUpdatedState(onReload)
    ObserveAsEvents(LocalPlaybackStopped.current) { currentOnReload() }
}

/**
 * Runs [onReload] when the screen appears, which also does the first load, and when it is resumed
 * and [shouldReload] says it is out of date (#251): a plain back navigation reloads nothing. The
 * player reports its final position after the screen underneath has resumed, so [onReload] runs
 * again then. [shouldReload] stays false while that report is still due, so it doesn't load twice.
 *
 * On TV (CE #660) a screen has no lifecycle of its own, so a resume is the app's: the TV woke from
 * standby or the screensaver with the screen still open.
 *
 * @param loadOnEntry false for a screen that loads by itself when it appears (the TV season page):
 *   then only a later resume runs [onReload]. The first resume is the screen appearing, as a new
 *   observer is brought up to the state the screen joins in.
 */
@Composable
fun ReloadOnResume(shouldReload: () -> Boolean, loadOnEntry: Boolean = true, onReload: () -> Unit) {
    val currentShouldReload by rememberUpdatedState(shouldReload)
    val currentOnReload by rememberUpdatedState(onReload)
    // Not saveable: the screen appears again on each return to it.
    val appeared = remember { AppearedFlag() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val entry = !appeared.value
        appeared.value = true
        if ((loadOnEntry || !entry) && currentShouldReload()) currentOnReload()
    }
    ObserveAsEvents(LocalPlaybackStopped.current) { currentOnReload() }
}

/** Read and written in lifecycle callbacks only, so it is no Compose state. */
private class AppearedFlag(var value: Boolean = false)
