package dev.jdtech.jellyfin.ce.presentation.player

import android.os.SystemClock

/**
 * CE (#458): the TV player's progress reports to the server. The phone player sends them from its
 * own loop and from `BasePlayerActivity.onPause`; the TV player's `MainActivity` has neither. A
 * pause or a resume is reported at once by `PlayerViewModel`, on phone and TV (#674), not here.
 *
 * [report] skips a report that would say the same thing as the last one (same play state) less than
 * [MIN_GAP_MS] after it, so the loop's report and the one sent on ON_PAUSE do not both reach the
 * server.
 */
internal class TvProgressReports(
    private val send: () -> Unit,
    private val isPlaying: () -> Boolean,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private var lastAtMs: Long? = null
    private var lastPlaying: Boolean? = null

    fun report() {
        val playing = isPlaying()
        val at = now()
        val last = lastAtMs
        if (last != null && playing == lastPlaying && at - last < MIN_GAP_MS) return
        lastAtMs = at
        lastPlaying = playing
        send()
    }

    companion object {
        /** How often the player reports while it is shown, as on the phone. */
        const val INTERVAL_MS = 5_000L
        const val MIN_GAP_MS = 1_000L
    }
}
