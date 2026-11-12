package dev.jdtech.jellyfin.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.profilelock.ProfileLock
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.NetworkConnectivity
import dev.jdtech.jellyfin.utils.UiOfflineDecision
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel
@Inject
constructor(
    private val appPreferences: AppPreferences,
    private val database: ServerDatabaseDao,
    private val networkConnectivity: NetworkConnectivity,
    private val profileLock: ProfileLock,
    private val uiOfflineDecision: UiOfflineDecision,
) : ViewModel() {
    private val _state = MutableStateFlow(MainState())
    val state = _state.asStateFlow()

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState = _uiState.asStateFlow()

    sealed class UiState {
        data class Normal(val server: Server?, val user: User?) : UiState()

        data object Loading : UiState()
    }

    init {
        check()
    }

    override fun onCleared() {
        uiOfflineDecision.clear(this)
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
                        isOfflineMode = offlineMode || !networkConnectivity.isOnline(),
                        showFavoritesTab = showFavoritesTab,
                        askUserOnStart = askUserOnStart,
                        profileLocked = profileLocked,
                    )
                }
                .collect { mainState ->
                    // CE (#427): screens created from now on get the repository this UI shows.
                    uiOfflineDecision.set(this@MainViewModel, mainState.isOfflineMode)
                    _state.emit(mainState)
                }
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
