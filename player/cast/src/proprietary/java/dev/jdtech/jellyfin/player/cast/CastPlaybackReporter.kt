package dev.jdtech.jellyfin.player.cast

import dev.jdtech.jellyfin.player.cast.models.CastPlaybackStatus
import java.util.UUID
import kotlin.math.abs

/** A report the server is owed. */
internal sealed interface CastReport {
    data class Start(val itemId: UUID) : CastReport

    data class Progress(val itemId: UUID, val positionMs: Long, val isPaused: Boolean) : CastReport

    data class Stop(val itemId: UUID, val positionMs: Long, val playedPercentage: Int?) : CastReport
}

/** What the receiver and the controller say at one moment. */
internal data class CastReceiverState(
    val status: CastPlaybackStatus,
    /** The Jellyfin item the receiver has loaded, `null` when it has none. */
    val receiverItemId: UUID?,
    /** The item the controller holds as the current one. */
    val currentItemId: UUID?,
    val positionMs: Long,
    /** A load is on its way to the receiver, which still shows what it played before. */
    val loadPending: Boolean = false,
)

/**
 * Decides which playback reports the server gets for what the receiver does. It sends nothing
 * itself: every call returns the reports to send, in order.
 *
 * An item gets one start, when the receiver plays it and it is the current item, and one stop, when
 * the receiver ends or leaves it or the controller says so. In between, a progress report goes out
 * only when there is something to tell:
 * - playing became paused or the other way round. Buffering is neither: it keeps the last flag.
 * - the position jumped: it is more than [SEEK_TOLERANCE_MS] away from where the clock puts it
 *   since the last time the receiver was heard from.
 * - on a [tick], when not paused and [intervalMs] has passed since the last report of any kind (the
 *   start included). A tick that comes sooner is dropped, so a report for a change of state pushes
 *   the next interval report back.
 *
 * The receiver sends many status updates that change none of this, and they lead to no report.
 */
internal class CastPlaybackReporter(
    private val clock: () -> Long,
    private val intervalMs: Long = PROGRESS_INTERVAL_MS,
) {
    /** The item whose start was reported and whose stop is still owed, if any. */
    private var reportingItemId: UUID? = null
    /** The last position the receiver gave for [reportingItemId], for its stop report. */
    private var positionMs = 0L
    private var paused = false
    private var lastReportAt = 0L

    /** Where the receiver was when it was last heard from, and whether it was moving. */
    private var heardPositionMs = 0L
    private var heardAt = 0L
    private var heardPlaying = false

    private val durations = HashMap<UUID, Long>()

    val isReporting: Boolean
        get() = reportingItemId != null

    /** The receiver sent a status update. */
    fun statusChanged(state: CastReceiverState): List<CastReport> =
        when (state.status) {
            CastPlaybackStatus.ENDED,
            CastPlaybackStatus.ERROR -> stop()
            // Idle between two loads still has an item; idle with none is the end.
            CastPlaybackStatus.IDLE -> if (state.receiverItemId == null) stop() else emptyList()
            CastPlaybackStatus.PLAYING -> playing(state)
            CastPlaybackStatus.PAUSED,
            CastPlaybackStatus.BUFFERING -> progress(state, tick = false)
        }

    /**
     * The controller's current item caught up with the receiver. The start of an item is reported
     * when the receiver plays it and it is the current item, whichever comes last.
     */
    fun currentItemChanged(state: CastReceiverState): List<CastReport> =
        if (state.status == CastPlaybackStatus.PLAYING) playing(state) else emptyList()

    /**
     * The receiver's queue changed. If it moved on, the item that was playing is done, wherever the
     * reports for the new one stand.
     */
    fun queueChanged(receiverItemId: UUID?): List<CastReport> =
        if (reportingItemId != null && receiverItemId != reportingItemId) stop() else emptyList()

    /** The interval timer fired. */
    fun tick(state: CastReceiverState): List<CastReport> = progress(state, tick = true)

    /** The receiver's position and duration for [itemId], as they come in while it plays. */
    fun positionChanged(itemId: UUID, positionMs: Long, durationMs: Long) {
        durationChanged(itemId, durationMs)
        if (itemId == reportingItemId) this.positionMs = positionMs.coerceAtLeast(0L)
    }

    fun durationChanged(itemId: UUID, durationMs: Long) {
        if (durationMs > 0L) durations[itemId] = durationMs
    }

    /** Ends the reports of the item that has them running, if any. */
    fun stop(): List<CastReport> {
        val itemId = reportingItemId ?: return emptyList()
        reportingItemId = null
        val playedPercentage =
            durations[itemId]?.let {
                (positionMs.toFloat() / it.toFloat() * 100).toInt().coerceIn(0, 100)
            }
        return listOf(CastReport.Stop(itemId, positionMs, playedPercentage))
    }

    /** Forgets the durations: the playlist they belong to is gone. */
    fun forget() {
        durations.clear()
    }

    private fun playing(state: CastReceiverState): List<CastReport> {
        val itemId = state.currentItemId ?: return emptyList()
        // During a queue transition the receiver and the current item disagree for a moment.
        if (itemId != state.receiverItemId) return emptyList()
        if (itemId == reportingItemId) return progress(state, tick = false)
        // What the receiver plays until a load reaches it is what was stopped for that load.
        if (state.loadPending) return emptyList()

        val reports = stop() + CastReport.Start(itemId)
        val now = clock()
        reportingItemId = itemId
        positionMs = state.positionMs.coerceAtLeast(0L)
        paused = false
        lastReportAt = now
        heard(positionMs, now, playing = true)
        return reports
    }

    /**
     * A progress report for the item whose reports are running, when it is also what the receiver
     * plays and the current item, and there is something to tell.
     */
    private fun progress(state: CastReceiverState, tick: Boolean): List<CastReport> {
        val itemId = reportingItemId ?: return emptyList()
        if (itemId != state.currentItemId || itemId != state.receiverItemId) return emptyList()

        val now = clock()
        val position = state.positionMs.coerceAtLeast(0L)
        // An item that runs out is at position 0 for a moment before the receiver moves on. That
        // is not a seek back to the start, and the stop that follows must not say it was.
        val duration = durations[itemId]
        val ranOut =
            duration != null &&
                positionMs >= duration - END_WINDOW_MS &&
                position < SEEK_TOLERANCE_MS
        if (ranOut) return emptyList()
        val isPaused =
            when (state.status) {
                CastPlaybackStatus.PLAYING -> false
                CastPlaybackStatus.PAUSED -> true
                else -> paused
            }
        val expected = heardPositionMs + if (heardPlaying) now - heardAt else 0L
        val seeked = abs(position - expected) > SEEK_TOLERANCE_MS
        val due = tick && !isPaused && now - lastReportAt >= intervalMs - TICK_SLACK_MS

        positionMs = position
        heard(position, now, playing = state.status == CastPlaybackStatus.PLAYING)
        if (isPaused == paused && !seeked && !due) return emptyList()

        paused = isPaused
        lastReportAt = now
        return listOf(CastReport.Progress(itemId, position, isPaused))
    }

    private fun heard(positionMs: Long, at: Long, playing: Boolean) {
        heardPositionMs = positionMs
        heardAt = at
        heardPlaying = playing
    }

    companion object {
        const val PROGRESS_INTERVAL_MS = 10_000L

        /** A position this far from where the clock puts it is a seek. */
        const val SEEK_TOLERANCE_MS = 2_000L

        /** An item this close to its end that jumps to its start has run out. */
        const val END_WINDOW_MS = 10_000L

        /** The interval timer is not exact: a tick this much early still counts. */
        const val TICK_SLACK_MS = 500L
    }
}
