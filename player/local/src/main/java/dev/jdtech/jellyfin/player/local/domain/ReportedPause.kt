package dev.jdtech.jellyfin.player.local.domain

/**
 * CE (#674): the pause state the server was last told in a progress report.
 *
 * A pause or a resume is reported when playWhenReady changes. A resume that has to buffer first is
 * reported as paused then, because the player is not playing yet. When playback starts, a report is
 * needed only if the last one said paused: a stall and its recovery then send nothing.
 */
class ReportedPause {
    private var lastPaused: Boolean? = null

    fun onReported(isPaused: Boolean) {
        lastPaused = isPaused
    }

    /** Whether playback that just started has to be reported: the server was last told paused. */
    fun needsReportWhenPlaying(): Boolean = lastPaused == true
}
