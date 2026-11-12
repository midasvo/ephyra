package dev.jdtech.jellyfin.utils

import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CE (#427): whether the open UI (MainViewModel) shows online or offline mode, so the repository a
 * screen gets matches the UI around it. A screen created after the network dropped otherwise got
 * the offline repository while the UI stayed online, and showed an empty list instead of an error
 * for as long as it lived.
 *
 * Each MainViewModel writes its decision under its own key and clears only its own, so a ViewModel
 * cleared after a newer one decided (the Activity restart of the offline setting) leaves the newer
 * decision alone. A rotation or theme change keeps the ViewModel and so the decision.
 */
@Singleton
class UiOfflineDecision @Inject constructor() {
    private class Decision(val owner: Any, val offline: Boolean)

    @Volatile private var current: Decision? = null

    /** The open UI's decision, or null when no UI has decided (a process started by a worker). */
    val offline: Boolean?
        get() = current?.offline

    @Synchronized
    fun set(owner: Any, offline: Boolean) {
        current = Decision(owner, offline)
    }

    @Synchronized
    fun clear(owner: Any) {
        if (current?.owner === owner) current = null
    }
}

/**
 * Which repository to use: the online one while the UI shows online mode and the offline setting is
 * off, even when the network is gone right now, so a lost connection reaches the screen as an
 * error (#427). Otherwise the live [isOfflineModeActive], as before: the offline setting, a start
 * without network, and the download queue and workers when no UI has decided.
 */
fun useOfflineRepository(
    uiOfflineDecision: UiOfflineDecision,
    appPreferences: AppPreferences,
    networkConnectivity: NetworkConnectivity,
): Boolean {
    val pinnedOnline =
        uiOfflineDecision.offline == false && !appPreferences.getValue(appPreferences.offlineMode)
    return !pinnedOnline && isOfflineModeActive(appPreferences, networkConnectivity)
}
