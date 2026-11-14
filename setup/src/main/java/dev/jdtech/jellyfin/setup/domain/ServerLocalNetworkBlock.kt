package dev.jdtech.jellyfin.setup.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the server the app is signed in on is out of reach because local network access is
 * denied, and the name the system settings list the permission under.
 */
data class LocalNetworkBlockState(val blocked: Boolean = false, val settingName: String? = null)

/**
 * CE (#571): the local network permission of Android 17, seen from the server in use. With access
 * revoked after sign-in every request to a server on the local network times out, and the screens
 * could only say "Error loading data". They ask here, so the error can name the permission.
 *
 * [serverAddress] is the address in use now, or null when there is none.
 */
class ServerLocalNetworkBlock(
    private val access: LocalNetworkAccess,
    private val serverAddress: () -> String?,
    private val resolve: suspend (String) -> List<ByteArray> = ::resolveHost,
) {
    private val _state = MutableStateFlow(LocalNetworkBlockState())
    val state: StateFlow<LocalNetworkBlockState> = _state.asStateFlow()

    /** Reads the permission and the address again. Returns whether the server is blocked. */
    suspend fun refresh(): Boolean {
        val address = serverAddress()?.takeIf { it.isNotBlank() }
        val blocked = address != null && access.blocks(address, resolve)
        val name = if (blocked) _state.value.settingName ?: access.systemSettingName() else null
        _state.value = LocalNetworkBlockState(blocked = blocked, settingName = name)
        return blocked
    }
}
