package dev.jdtech.jellyfin.film.presentation

import dev.jdtech.jellyfin.api.JellyfinApi

/**
 * CE (#775): when a list that falls back to the downloads (Favorites, Media, search) loads again
 * because the server went away or came back.
 *
 * The screen hands every change of state to [onServerAway]; the view model calls [begin] when a
 * load starts. A change loads again only when the list on screen was loaded with the other state,
 * and a load that is running already for the current state is not started twice, which is what
 * keeps this, [ReloadPolicy][dev.jdtech.jellyfin.repository.ReloadPolicy] and the retry of the
 * error page from loading the same list twice.
 *
 * CE (#779): whether a list holds the downloads only is what the repository answered
 * ([dev.jdtech.jellyfin.repository.ItemsResult.fromStore]), not decided here.
 */
class ServerAwayReload {
    private var away: Boolean? = null
    private var loadedFor: Boolean? = null
    private var loaded = false
    private var storeRetried = false

    /** Marks the start of a load, with the state the screen last reported. */
    fun begin() {
        loadedFor = away
        loaded = true
    }

    /** Whether a load that is still running was started with the state of now. */
    fun runningIsCurrent(): Boolean = loaded && away != null && loadedFor == away

    /**
     * Records [away] and says whether the list needs a load for it. Not on the first report: the
     * screen's own first load runs with it. Not before anything loaded either.
     */
    fun onServerAway(away: Boolean): Boolean {
        val before = this.away
        this.away = away
        if (before != away) storeRetried = false
        return before != null && loaded && loadedFor != away
    }

    /**
     * CE (#779): whether a load that Room answered gets one more try after [STORE_RETRY_DELAY_MS].
     * Room answers an unanswered request before the server is known to be away. Mostly the probe
     * that request started then says unreachable, and [onServerAway] reloads; but when the probe
     * reached the server nothing changes, and the stored list would stay as if it were the
     * server's. One try per state, so a server that keeps failing requests is not asked in a loop.
     */
    fun retriesAfterStore(fromStore: Boolean): Boolean {
        if (!fromStore) {
            storeRetried = false
            return false
        }
        if (away == true || storeRetried) return false
        storeRetried = true
        return true
    }

    companion object {
        /**
         * After the probe that the unanswered request started has had its time: by then the server
         * is known to be away (and the list reloaded for it), or it answered.
         */
        const val STORE_RETRY_DELAY_MS = JellyfinApi.PROBE_TIMEOUT_MS + 1_000L
    }
}
