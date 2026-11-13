package dev.jdtech.jellyfin.player.cast

import dev.jdtech.jellyfin.player.cast.models.CastAvailability
import dev.jdtech.jellyfin.player.cast.models.CastConnectionState
import dev.jdtech.jellyfin.player.cast.models.Device
import kotlinx.coroutines.flow.StateFlow

interface CastSessionManager {
    /** Read again whenever it is shown: it follows the settings and the current server. */
    val availability: CastAvailability

    val isSupported: Boolean
        get() = availability == CastAvailability.AVAILABLE

    val connectionState: StateFlow<CastConnectionState>
    val availableDevices: StateFlow<List<Device>>
    val connectedDevice: StateFlow<Device?>

    fun init()

    /**
     * Keeps the device list up to date. An [activeScan] searches the network and costs battery, so
     * it is for while the device list is on screen.
     */
    fun updateDiscovery(activeScan: Boolean = false)

    fun connect(device: Device)

    fun disconnect()

    fun release()
}
