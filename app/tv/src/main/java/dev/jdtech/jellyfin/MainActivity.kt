package dev.jdtech.jellyfin

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.ce.ui.focus.DirectionKeyClock
import dev.jdtech.jellyfin.ce.watchnext.WatchNextLink
import dev.jdtech.jellyfin.ce.watchnext.WatchNextSync
import dev.jdtech.jellyfin.core.presentation.localnetwork.LocalLocalNetworkBlock
import dev.jdtech.jellyfin.core.presentation.localnetwork.LocalNetworkBlock
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.setup.presentation.localnetwork.LocalNetworkBlockViewModel
import dev.jdtech.jellyfin.viewmodels.MainViewModel
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val localNetworkBlockViewModel: LocalNetworkBlockViewModel by viewModels()

    @Inject lateinit var jellyfinApi: JellyfinApi

    @Inject lateinit var watchNextSync: WatchNextSync

    /** A Watch Next program the user selected on the launcher (#138), until it is opened. */
    private var watchNextLink by mutableStateOf<WatchNextLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Not again after a configuration change or process restore: the link was opened then.
        if (savedInstanceState == null) watchNextLink = readWatchNextLink(intent)

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()

            // CE (#571): the error pages know when local network access is what keeps the server
            // out of reach. They only say so: TV has no "Allow access" button.
            val blockState by localNetworkBlockViewModel.state.collectAsStateWithLifecycle()
            val block =
                remember(blockState) {
                    LocalNetworkBlock(
                        blocked = blockState.blocked,
                        settingName = blockState.settingName,
                        refresh = localNetworkBlockViewModel::refresh,
                    )
                }

            FindroidTheme {
                if (!state.isLoading) {
                    CompositionLocalProvider(LocalLocalNetworkBlock provides block) {
                        NavigationRoot(
                            hasServers = state.hasServers,
                            hasCurrentServer = state.hasCurrentServer,
                            hasCurrentUser = state.hasCurrentUser,
                            askUserOnStart = state.askUserOnStart,
                            profileLocked = state.profileLocked,
                            watchNextLink = watchNextLink,
                            onWatchNextLinkOpened = { watchNextLink = null },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Access may have changed in the system settings.
        localNetworkBlockViewModel.refresh()
    }

    // CE: every D-pad key, also one that no focused element sees (cold start, Main's drawer).
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        DirectionKeyClock.onKeyEvent(event)
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readWatchNextLink(intent)?.let { watchNextLink = it }
    }

    override fun onStop() {
        super.onStop()
        // The launcher's row is what the user sees next: bring it up to date with this session.
        watchNextSync.requestSync()
    }

    /** A link for another user than the current one opens the app without playing it. */
    private fun readWatchNextLink(intent: Intent?): WatchNextLink? =
        WatchNextLink.fromIntent(intent)?.takeIf { it.userId == jellyfinApi.userId?.toString() }
}
