package dev.jdtech.jellyfin

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadPumpService
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.utils.KeyboardShortcuts
import dev.jdtech.jellyfin.presentation.utils.LocalNotificationPermissionRequest
import dev.jdtech.jellyfin.presentation.utils.LocalOfflineMode
import dev.jdtech.jellyfin.presentation.utils.LocalPlaybackStopped
import dev.jdtech.jellyfin.presentation.utils.LocalServerStatus
import dev.jdtech.jellyfin.presentation.utils.ProvideLocalNetworkBlock
import dev.jdtech.jellyfin.presentation.utils.ServerStatusUi
import dev.jdtech.jellyfin.repository.PlaybackEvents
import dev.jdtech.jellyfin.utils.shouldRequestNotificationPermission
import dev.jdtech.jellyfin.viewmodels.MainViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.map

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var playbackEvents: PlaybackEvents

    // CE (#372): registered here rather than in the download quality dialog, which closes on the
    // tap that asks. The download has already started; once allowed, its notification is posted
    // again, because the one posted before the answer was dropped.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) DownloadPumpService.refreshNotification(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val stopped = remember { playbackEvents.stopped.map {} }
            // CE (#281): whether the server answers, for the banner and the error pages.
            val server by viewModel.serverStatus.collectAsStateWithLifecycle()
            val serverStatus =
                remember(server, state.isOfflineMode) {
                    if (state.isOfflineMode) {
                        // Offline Mode shows its own chip and asks nothing of the server.
                        ServerStatusUi()
                    } else {
                        ServerStatusUi(
                            unreachable = server.unreachable,
                            confirmedUnreachable = server.confirmedUnreachable,
                            hasDownloads = server.hasDownloads,
                            check = viewModel::checkServer,
                        )
                    }
                }

            FindroidTheme(dynamicColor = state.isDynamicColors) {
                if (!state.isLoading) {
                    CompositionLocalProvider(
                        LocalOfflineMode provides state.isOfflineMode,
                        LocalServerStatus provides serverStatus,
                        LocalPlaybackStopped provides stopped,
                        LocalNotificationPermissionRequest provides ::requestNotificationPermission,
                    ) {
                        // CE (#571): the error screens know when local network access is what
                        // keeps the server out of reach, and can ask for it.
                        ProvideLocalNetworkBlock {
                            NavigationRoot(
                                hasServers = state.hasServers,
                                hasCurrentServer = state.hasCurrentServer,
                                hasCurrentUser = state.hasCurrentUser,
                                showFavoritesTab = state.showFavoritesTab,
                                askUserOnStart = state.askUserOnStart,
                                profileLocked = state.profileLocked,
                            )
                        }
                    }
                }
            }
        }
    }

    /** CE (#372): asked when the user starts a download, never on start or recreation. */
    private fun requestNotificationPermission() {
        val permission = Manifest.permission.POST_NOTIFICATIONS
        val granted = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        if (shouldRequestNotificationPermission(Build.VERSION.SDK_INT, granted)) {
            notificationPermissionLauncher.launch(permission)
        }
    }

    // CE (R8): shortcuts such as Ctrl+F work whether or not anything has focus.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        KeyboardShortcuts.onKeyEvent(event) || super.dispatchKeyEvent(event)
}
