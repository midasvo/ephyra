package dev.jdtech.jellyfin.utils

import dev.jdtech.jellyfin.settings.domain.AppPreferences

/** Reports whether the device currently has network connectivity. */
interface NetworkConnectivity {
    /** True when there is an active network capable of reaching the internet. */
    fun isOnline(): Boolean

    /**
     * True when the active network is metered (i.e. lacks
     * [android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED]).
     * Returns false when network capabilities cannot be determined.
     */
    fun isMetered(): Boolean

    /**
     * True when the active network is roaming (i.e. lacks
     * [android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING]).
     * Requires API 28+ which matches minSdk.
     * Returns false when network capabilities cannot be determined.
     */
    fun isRoaming(): Boolean
}

/**
 * Which repository to use. CE (#281): the Offline Mode setting alone decides, as it alone decides
 * what the UI shows (MainViewModel), so the two always agree. A lost connection, or a start
 * without one, keeps the online repository: its errors reach the screen, and the reachability
 * banner points at Downloads. Nothing swaps the repository under a running UI (#427).
 */
fun useOfflineRepository(appPreferences: AppPreferences): Boolean =
    appPreferences.getValue(appPreferences.offlineMode)

/**
 * The Offline Mode setting, or no network at all. Since #281 only for work that should not start
 * without a connection (Smart Downloads); the UI and the repository follow the setting alone.
 */
fun isOfflineModeActive(
    appPreferences: AppPreferences,
    networkConnectivity: NetworkConnectivity,
): Boolean {
    return appPreferences.getValue(appPreferences.offlineMode) || !networkConnectivity.isOnline()
}
