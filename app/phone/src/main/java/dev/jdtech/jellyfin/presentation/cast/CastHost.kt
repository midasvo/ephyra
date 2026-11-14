package dev.jdtech.jellyfin.presentation.cast

import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.ephyra.EphyraMotion
import dev.jdtech.jellyfin.player.cast.models.CastAvailability
import dev.jdtech.jellyfin.player.cast.models.CastConnectionState
import dev.jdtech.jellyfin.player.cast.presentation.CastEvent
import dev.jdtech.jellyfin.player.cast.presentation.CastViewModel
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.Layout
import dev.jdtech.jellyfin.presentation.utils.WidthClass
import dev.jdtech.jellyfin.presentation.utils.currentWidthClass
import dev.jdtech.jellyfin.presentation.utils.isCompactHeight
import dev.jdtech.jellyfin.utils.ObserveAsEvents

/**
 * CE (#626): everything Cast adds around the app's screens. [content] is the navigation display.
 * Around it this puts the mini player (below it, in room of its own) and, at expanded width, the
 * player as a side panel; it owns the device sheet and the player sheet, and tells the screens what
 * their Cast button shows ([LocalCastEntry]) and where Play goes ([LocalCastPlayback]).
 *
 * In a build without Cast it is [content] and nothing else. [route] is the screen on top: what Cast
 * can do is read again whenever it changes, since the settings are one of the screens.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CastHost(
    route: Any?,
    showBottomBar: Boolean,
    isOfflineMode: Boolean,
    content: @Composable () -> Unit,
) {
    // The activity's: every screen and both sheets see one session.
    val owner =
        LocalActivity.current as? ViewModelStoreOwner
            ?: checkNotNull(LocalViewModelStoreOwner.current)
    val viewModel: CastViewModel = hiltViewModel(owner)
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state.availability == CastAvailability.NOT_IN_BUILD) {
        content()
        return
    }

    LaunchedEffect(route) { viewModel.refreshAvailability() }

    var devicesOpen by rememberSaveable { mutableStateOf(false) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    // A sheet opened to pick a receiver closes once one is connected.
    var pickingDevice by remember { mutableStateOf(false) }

    val hasSession = state.isConnected || state.isReconnecting
    val hasSomethingToShow =
        state.nowPlaying != null ||
            state.finished != null ||
            state.playbackFailed ||
            state.isStarting
    LaunchedEffect(hasSession, hasSomethingToShow) {
        if (!hasSession || !hasSomethingToShow) playerOpen = false
    }
    LaunchedEffect(state.isConnected) {
        if (state.isConnected && pickingDevice) devicesOpen = false
        pickingDevice = false
    }

    val context = LocalContext.current
    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            // The surfaces of the session are gone with it, so this is said in passing.
            is CastEvent.Disconnected ->
                Toast.makeText(
                        context,
                        context.getString(CoreR.string.ce_cast_disconnected_from, event.deviceName),
                        Toast.LENGTH_SHORT,
                    )
                    .show()
        }
    }

    val entry =
        remember(state.availability, state.connectionState, state.device?.name) {
            CastEntry(
                state =
                    when {
                        state.availability == CastAvailability.DISABLED -> CastButtonState.HIDDEN
                        state.connectionState == CastConnectionState.CONNECTED ->
                            CastButtonState.CONNECTED
                        state.connectionState == CastConnectionState.CONNECTING ->
                            CastButtonState.CONNECTING
                        else -> CastButtonState.IDLE
                    },
                deviceName = state.device?.name,
                onClick = {
                    pickingDevice = !state.isConnected
                    devicesOpen = true
                },
            )
        }
    val playback = remember(viewModel) { CastPlayback(viewModel::playItem) }
    val actions =
        remember(viewModel) {
            CastPlayerActions(
                onClose = { playerOpen = false },
                onOpenDevices = { devicesOpen = true },
                onTogglePlay = viewModel::togglePlayPause,
                onSeek = viewModel::seekTo,
                onSeekBy = viewModel::seekBy,
                onPrevious = viewModel::previous,
                onNext = viewModel::next,
                onSkipSegment = viewModel::skipSegment,
                onVolume = viewModel::setVolume,
                onAudioTrack = viewModel::setAudioTrack,
                onSubtitleTrack = viewModel::setSubtitleTrack,
                onRetry = viewModel::retry,
                onPlayAgain = viewModel::playAgain,
            )
        }

    // A side panel needs the width and the height; a phone in landscape gets the sheet.
    val widthClass = currentWidthClass()
    val asPanel = widthClass == WidthClass.Expanded && !isCompactHeight()
    // With the keyboard up the bar would sit on top of it, in the middle of the screen.
    val showMiniPlayer = hasSession && !WindowInsets.isImeVisible && !(playerOpen && asPanel)
    val enter = tween<Float>(EphyraMotion.DURATION_MEDIUM, easing = EphyraMotion.EasingEnter)
    val exit = tween<Float>(EphyraMotion.DURATION_SHORT, easing = EphyraMotion.EasingExit)

    CompositionLocalProvider(LocalCastPlayback provides playback, LocalCastEntry provides entry) {
        Row(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Box(modifier = Modifier.weight(1f)) { content() }
                AnimatedVisibility(
                    visible = showMiniPlayer,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(enter),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(exit),
                ) {
                    CastMiniPlayer(
                        state = state,
                        onOpenPlayer = { playerOpen = true },
                        onOpenDevices = { devicesOpen = true },
                        onTogglePlay = viewModel::togglePlayPause,
                        onRetry = viewModel::retry,
                        onPlayAgain = viewModel::playAgain,
                        modifier =
                            Modifier.windowInsetsPadding(
                                    WindowInsets.safeDrawing.only(
                                        // A bottom navigation bar takes the bottom inset; a
                                        // rail, or no navigation at all, leaves it to this bar.
                                        if (showBottomBar && widthClass == WidthClass.Compact) {
                                            WindowInsetsSides.Horizontal
                                        } else {
                                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                                        }
                                    )
                                )
                                .padding(
                                    horizontal = Layout.margin(widthClass),
                                    vertical = MaterialTheme.spacings.small,
                                ),
                    )
                }
            }
            AnimatedVisibility(
                visible = playerOpen && asPanel,
                enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(enter),
                exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(exit),
            ) {
                CastPlayerPanel(state = state, actions = actions)
            }
        }
    }

    if (playerOpen && !asPanel) CastPlayerSheet(state = state, actions = actions)

    if (devicesOpen) {
        DisposableEffect(Unit) {
            viewModel.startDiscovery()
            onDispose { viewModel.stopDiscovery() }
        }
        CastDeviceSheet(
            state = state,
            isOfflineMode = isOfflineMode,
            onConnect = viewModel::connect,
            onDisconnect = {
                viewModel.disconnect()
                devicesOpen = false
            },
            onSearchAgain = viewModel::startDiscovery,
            onOpenPlayer = {
                devicesOpen = false
                playerOpen = true
            },
            onDismissRequest = { devicesOpen = false },
        )
    }
}
