package dev.jdtech.jellyfin.setup.presentation.addserver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.DiscoveredServer
import dev.jdtech.jellyfin.models.ExceptionUiText
import dev.jdtech.jellyfin.models.ExceptionUiTexts
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.domain.LocalNetworkAccess
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import dev.jdtech.jellyfin.setup.domain.blocks
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class AddServerViewModel
@Inject
constructor(
    private val repository: SetupRepository,
    private val appPreferences: AppPreferences,
    private val localNetworkAccess: LocalNetworkAccess,
) : ViewModel() {
    private val _state = MutableStateFlow(AddServerState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<AddServerEvent>()
    val events = eventsChannel.receiveAsFlow()

    fun discoverServers() {
        // CE (#376): without local network access the search cannot send anything, and the SDK
        // ends it without a word. The screen says so instead.
        if (updateLocalNetworkDenied()) return
        viewModelScope.launch {
            val discoveredServers = mutableListOf<DiscoveredServer>()
            val serversDiscovery = repository.discoverServers()
            serversDiscovery.collect { serverDiscoveryInfo ->
                discoveredServers.add(
                    DiscoveredServer(
                        serverDiscoveryInfo.id,
                        serverDiscoveryInfo.name,
                        serverDiscoveryInfo.address,
                    )
                )
                _state.emit(_state.value.copy(discoveredServers = discoveredServers))
            }
        }
    }

    /** CE (#376): reads the permission into the state. Returns whether access is denied. */
    private fun updateLocalNetworkDenied(): Boolean {
        val denied = localNetworkAccess.isDenied()
        _state.update { it.copy(localNetworkDenied = denied) }
        if (denied && _state.value.localNetworkSettingName == null) {
            viewModelScope.launch {
                val name = localNetworkAccess.systemSettingName()
                _state.update { it.copy(localNetworkSettingName = name) }
            }
        }
        return denied
    }

    /**
     * CE (#376): the screen is back in front, perhaps from the permission dialog or the system
     * settings. Searches once access has returned; a search that already ran is not repeated.
     */
    private fun recheckLocalNetwork() {
        val wasDenied = _state.value.localNetworkDenied
        if (!updateLocalNetworkDenied() && wasDenied) {
            // The error that blamed the permission no longer holds.
            _state.update { state ->
                if (state.error?.any { it.isLocalNetworkError() } == true) {
                    state.copy(error = null)
                } else {
                    state
                }
            }
            discoverServers()
        }
    }

    private fun connectToServer(address: String) {
        viewModelScope.launch {
            _state.emit(_state.value.copy(isLoading = true, error = null))

            try {
                val server = repository.addServer(address)
                appPreferences.setValue(appPreferences.currentServer, server.id)
                _state.emit(_state.value.copy(isLoading = false, error = null))
                eventsChannel.send(AddServerEvent.Success)
            } catch (e: CancellationException) { throw e } catch (e: ExceptionUiText) {
                // CE (#376): "Server not found" for an address on the local network, while access
                // to it is denied, names the permission.
                val uiText =
                    if (e.uiText.isServerNotFound() && localNetworkAccess.blocks(address)) {
                        updateLocalNetworkDenied()
                        UiText.StringResource(SetupR.string.ce_add_server_error_local_network)
                    } else {
                        e.uiText
                    }
                _state.emit(_state.value.copy(isLoading = false, error = listOf(uiText)))
            } catch (e: ExceptionUiTexts) {
                _state.emit(_state.value.copy(isLoading = false, error = e.uiTexts))
            } catch (e: Exception) {
                _state.emit(
                    _state.value.copy(
                        isLoading = false,
                        error =
                            listOf(
                                if (e.message != null) UiText.DynamicString(e.message!!)
                                else UiText.StringResource(CoreR.string.unknown_error)
                            ),
                    )
                )
            }
        }
    }

    private fun UiText.isServerNotFound(): Boolean =
        this is UiText.StringResource && resId == SetupR.string.add_server_error_not_found

    private fun UiText.isLocalNetworkError(): Boolean =
        this is UiText.StringResource &&
            resId == SetupR.string.ce_add_server_error_local_network

    fun onAction(action: AddServerAction) {
        when (action) {
            is AddServerAction.OnConnectClick -> {
                // CE (#404): the TV screen passes the field as typed; spaces around the address
                // made the SDK reject it with "Server not found".
                connectToServer(address = action.address.trim())
            }
            is AddServerAction.OnAddressChange -> {
                if (_state.value.error != null) _state.update { it.copy(error = null) }
            }
            is AddServerAction.OnResume -> recheckLocalNetwork()
            else -> Unit
        }
    }
}
