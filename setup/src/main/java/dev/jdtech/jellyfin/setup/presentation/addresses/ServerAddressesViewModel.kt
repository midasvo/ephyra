package dev.jdtech.jellyfin.setup.presentation.addresses

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.domain.LocalNetworkAccess
import dev.jdtech.jellyfin.setup.domain.blocks
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import timber.log.Timber

@HiltViewModel
class ServerAddressesViewModel
@Inject
constructor(
    val application: Application,
    private val database: ServerDatabaseDao,
    private val localNetworkAccess: LocalNetworkAccess,
) : ViewModel() {
    private val _state = MutableStateFlow(ServerAddressesState())
    val state = _state.asStateFlow()

    private var currentServerId: String = ""

    fun loadAddresses(serverId: String) {
        currentServerId = serverId
        viewModelScope.launch {
            try {
                val serverWithAddresses = database.getServerWithAddresses(serverId)
                _state.emit(ServerAddressesState(addresses = serverWithAddresses.addresses))
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    fun deleteAddress(addressId: UUID) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.emit(_state.value.copy(error = null))
            val currentAddress = database.getServerCurrentAddress(currentServerId)
            if (addressId == currentAddress?.id) {
                // CE (#390): say why instead of dropping the removal without a word.
                setError(SetupR.string.ce_server_address_in_use)
                return@launch
            }
            database.deleteServerAddress(addressId)
            loadAddresses(currentServerId)
        }
    }

    fun addAddress(address: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.emit(_state.value.copy(error = null))
            try {
                val jellyfinApi = JellyfinApi(application.applicationContext)

                // Normalise the input the same way SetupRepositoryImpl.addServer does, so a bare
                // host or a missing scheme resolves to a usable address instead of failing.
                val candidates = jellyfinApi.jellyfin.discovery.getAddressCandidates(address)
                val recommended =
                    jellyfinApi.jellyfin.discovery.getRecommendedServers(
                        candidates,
                        RecommendedServerInfoScore.OK,
                    )

                // Only accept an address that actually points at the server we're editing.
                val match =
                    recommended.firstOrNull {
                        it.systemInfo.getOrNull()?.id == currentServerId
                    }
                if (match == null) {
                    // CE (#376): an address on the local network, while access to it is denied,
                    // names the permission.
                    setError(
                        if (localNetworkAccess.blocks(address)) {
                            SetupR.string.ce_add_server_error_local_network
                        } else {
                            SetupR.string.add_server_error_not_found
                        }
                    )
                    return@launch
                }

                // Dedupe against the addresses already stored for this server.
                val existingAddresses =
                    database.getServerWithAddresses(currentServerId).addresses
                if (existingAddresses.any { it.address == match.address }) {
                    // CE (#390): the dialog has closed, so say why nothing was added.
                    setError(SetupR.string.ce_server_address_already_added)
                    return@launch
                }

                val serverAddress = ServerAddress(UUID.randomUUID(), currentServerId, match.address)
                database.insertServerAddress(serverAddress)
                loadAddresses(currentServerId)
            } catch (e: Exception) {
                Timber.e(e)
                _state.emit(
                    _state.value.copy(
                        error =
                            listOf(
                                if (e.message != null) UiText.DynamicString(e.message!!)
                                else UiText.StringResource(CoreR.string.unknown_error)
                            )
                    )
                )
            }
        }
    }

    private suspend fun setError(resId: Int) {
        _state.emit(_state.value.copy(error = listOf(UiText.StringResource(resId))))
    }

    fun onAction(action: ServerAddressesAction) {
        when (action) {
            is ServerAddressesAction.AddAddress -> {
                addAddress(action.address)
            }
            is ServerAddressesAction.DeleteAddress -> {
                deleteAddress(action.addressId)
            }
            else -> Unit
        }
    }
}
