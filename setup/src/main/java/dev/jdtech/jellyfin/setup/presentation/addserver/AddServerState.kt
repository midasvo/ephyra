package dev.jdtech.jellyfin.setup.presentation.addserver

import dev.jdtech.jellyfin.models.DiscoveredServer
import dev.jdtech.jellyfin.models.UiText

data class AddServerState(
    val isLoading: Boolean = false,
    val discoveredServers: List<DiscoveredServer> = emptyList(),
    val error: Collection<UiText>? = null,
    // CE (#376): local network access is denied (Android 17), so nothing can be discovered.
    val localNetworkDenied: Boolean = false,
    // The name the system settings list that permission under, once the system has told.
    val localNetworkSettingName: String? = null,
)
