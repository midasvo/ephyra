package dev.jdtech.jellyfin.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.core.reachability.ServerReachability
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.profilelock.ProfileLock
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel
@Inject
constructor(
    private val appPreferences: AppPreferences,
    private val database: ServerDatabaseDao,
    private val profileLock: ProfileLock,
    private val serverReachability: ServerReachability,
) : ViewModel() {
    private val _state = MutableStateFlow(MainState())
    val state = _state.asStateFlow()

    /**
     * CE (#281): what the screens show about the server. Apart from [MainState], so a probe
     * result recomposes the banner and not the navigation.
     */
    val serverStatus: StateFlow<ServerStatus> =
        serverReachability.state
            .mapLatest { reachability ->
                ServerStatus(
                    unreachable = reachability.unreachable,
                    confirmedUnreachable = reachability.confirmedUnreachable,
                    // Only asked when it is shown: the banner points at Downloads when there are any.
                    hasDownloads = !reachability.unreachable || hasDownloads(),
                )
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ServerStatus())

    /** The banner's Retry: asks whether the server is there, without waiting for the answer. */
    fun checkServer() = serverReachability.check()

    private suspend fun hasDownloads(): Boolean =
        try {
            val serverId = appPreferences.getValue(appPreferences.currentServer)
            serverId != null &&
                (database.countMoviesByServerId(serverId) > 0 ||
                    database.countShowsByServerId(serverId) > 0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not knowing must not take the way to Downloads away.
            true
        }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState = _uiState.asStateFlow()

    sealed class UiState {
        data class Normal(val server: Server?, val user: User?) : UiState()

        data object Loading : UiState()
    }

    init {
        check()
    }

    fun loadServerAndUser() {
        viewModelScope.launch {
            val serverId = appPreferences.getValue(appPreferences.currentServer)
            serverId?.let { id ->
                database.getServerWithAddressAndUser(id)?.let { data ->
                    _uiState.emit(UiState.Normal(data.server, data.user))
                }
            }
        }
    }

    /**
     * Continuously derives [MainState] from the underlying preferences and database, so the state
     * (and anything derived from it, e.g. the setup/main navigation condition) reacts to changes as
     * soon as they happen.
     */
    private fun check() {
        viewModelScope.launch {
            _state.emit(MainState(isLoading = true))

            val setupFlags =
                appPreferences.observe(appPreferences.currentServer).flatMapLatest { serverId ->
                    if (serverId == null) {
                        database.observeServersCount().map { serverCount ->
                            SetupFlags(
                                hasServers = serverCount > 0,
                                hasCurrentServer = false,
                                hasCurrentUser = false,
                            )
                        }
                    } else {
                        combine(
                            database.observeServersCount(),
                            database.observeServer(serverId),
                            database.observeServerCurrentUserId(serverId),
                        ) { serverCount, server, currentUserId ->
                            SetupFlags(
                                hasServers = serverCount > 0,
                                hasCurrentServer = server != null,
                                hasCurrentUser = currentUserId != null,
                            )
                        }
                    }
                }

            // Asking who is watching only makes sense when there is a choice: more than one saved
            // user on the current server.
            val askUserOnStart =
                combine(
                    appPreferences.observe(appPreferences.askUserOnStart),
                    appPreferences.observe(appPreferences.currentServer).flatMapLatest { serverId ->
                        if (serverId == null) flowOf(0) else database.observeUsersCount(serverId)
                    },
                ) { enabled, usersCount ->
                    enabled && usersCount > 1
                }

            // CE (#299): the current profile has a PIN that this process has not been given yet.
            val profileLocked =
                appPreferences.observe(appPreferences.currentServer).flatMapLatest { serverId ->
                    if (serverId == null) {
                        flowOf(false)
                    } else {
                        combine(
                            database.observeServerCurrentUser(serverId),
                            profileLock.unlockedUserIds,
                        ) { user, unlockedUserIds ->
                            ProfileLock.isLockedOnStart(user, unlockedUserIds)
                        }
                    }
                }

            combine(
                    setupFlags,
                    appPreferences.observe(appPreferences.dynamicColors),
                    appPreferences.observe(appPreferences.offlineMode),
                    appPreferences.observe(appPreferences.showFavoritesTab),
                    askUserOnStart,
                    profileLocked,
                ) { values ->
                    val flags = values[0] as SetupFlags
                    val dynamicColors = values[1] as Boolean
                    val offlineMode = values[2] as Boolean
                    val showFavoritesTab = values[3] as Boolean
                    val askUserOnStart = values[4] as Boolean
                    val profileLocked = values[5] as Boolean
                    MainState(
                        isLoading = false,
                        isDynamicColors = dynamicColors,
                        hasServers = flags.hasServers,
                        hasCurrentServer = flags.hasCurrentServer,
                        hasCurrentUser = flags.hasCurrentUser,
                        // CE (#281): only the setting. Without a connection the UI stays as
                        // it is and the reachability banner says so.
                        isOfflineMode = offlineMode,
                        showFavoritesTab = showFavoritesTab,
                        askUserOnStart = askUserOnStart,
                        profileLocked = profileLocked,
                    )
                }
                .collect { mainState -> _state.emit(mainState) }
        }
    }

    private data class SetupFlags(
        val hasServers: Boolean,
        val hasCurrentServer: Boolean,
        val hasCurrentUser: Boolean,
    )
}

data class MainState(
    val isLoading: Boolean = true,
    val isDynamicColors: Boolean = true,
    val hasServers: Boolean = false,
    val hasCurrentServer: Boolean = false,
    val hasCurrentUser: Boolean = false,
    val isOfflineMode: Boolean = false,
    val showFavoritesTab: Boolean = true,
    val askUserOnStart: Boolean = false,
    /** CE (#299): the current profile asks for its PIN before Home shows. */
    val profileLocked: Boolean = false,
)

/**
 * CE (#281): the server as the screens see it.
 *
 * [unreachable] shows the banner. [confirmedUnreachable] also leaves out what needs the server
 * (downloading, trailers): not on one failed probe alone, which a slow server can cause.
 * [hasDownloads] is whether pointing at Downloads makes sense.
 */
data class ServerStatus(
    val unreachable: Boolean = false,
    val confirmedUnreachable: Boolean = false,
    val hasDownloads: Boolean = true,
)
