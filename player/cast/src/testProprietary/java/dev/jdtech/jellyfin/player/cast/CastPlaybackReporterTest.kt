package dev.jdtech.jellyfin.player.cast

import dev.jdtech.jellyfin.player.cast.models.CastPlaybackStatus
import dev.jdtech.jellyfin.player.cast.models.CastPlaybackStatus.BUFFERING
import dev.jdtech.jellyfin.player.cast.models.CastPlaybackStatus.ENDED
import dev.jdtech.jellyfin.player.cast.models.CastPlaybackStatus.ERROR
import dev.jdtech.jellyfin.player.cast.models.CastPlaybackStatus.IDLE
import dev.jdtech.jellyfin.player.cast.models.CastPlaybackStatus.PAUSED
import dev.jdtech.jellyfin.player.cast.models.CastPlaybackStatus.PLAYING
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CastPlaybackReporter]: which playback reports the server gets for what the
 * receiver does (#626). The receiver sends bursts of status updates that change nothing, and each
 * of them used to send a progress report.
 */
class CastPlaybackReporterTest {

    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()
    private val none = emptyList<CastReport>()

    /** The clock, and where an item that plays at normal speed from 0 is at that time. */
    private var now = 0L
    private val reporter = CastPlaybackReporter(clock = { now })

    private fun state(
        status: CastPlaybackStatus,
        positionMs: Long = 0L,
        receiver: UUID? = a,
        current: UUID? = a,
        loadPending: Boolean = false,
    ) = CastReceiverState(status, receiver, current, positionMs, loadPending)

    private fun status(status: CastPlaybackStatus, positionMs: Long = 0L) =
        reporter.statusChanged(state(status, positionMs))

    /** Item [a] plays from 0 at time 0 and its start is reported. */
    private fun startA() {
        assertEquals(listOf(CastReport.Start(a)), status(PLAYING))
    }

    @Test
    fun `an item starts once, when the receiver plays it`() {
        assertEquals(none, status(BUFFERING))
        assertFalse(reporter.isReporting)

        assertEquals(listOf(CastReport.Start(a)), status(PLAYING))
        assertTrue(reporter.isReporting)
    }

    @Test
    fun `ten status updates that change nothing send one report`() {
        val reports =
            (0 until 10).flatMap {
                now = it * 50L
                status(PLAYING, positionMs = now)
            }

        assertEquals(listOf(CastReport.Start(a)), reports)
    }

    @Test
    fun `an item that is loaded paused starts when it plays`() {
        assertEquals(none, status(PAUSED, positionMs = 60_000L))
        assertEquals(none, reporter.tick(state(PAUSED, positionMs = 60_000L)))

        assertEquals(listOf(CastReport.Start(a)), status(PLAYING, positionMs = 60_000L))
    }

    @Test
    fun `nothing starts while the receiver and the current item disagree`() {
        assertEquals(none, reporter.statusChanged(state(PLAYING, receiver = b, current = a)))
        assertEquals(none, reporter.statusChanged(state(PLAYING, receiver = a, current = null)))
        assertEquals(none, reporter.statusChanged(state(PLAYING, receiver = null, current = a)))
        assertFalse(reporter.isReporting)
    }

    @Test
    fun `the start waits for the current item to catch up with the receiver`() {
        assertEquals(none, reporter.statusChanged(state(PLAYING, receiver = a, current = null)))

        assertEquals(
            listOf(CastReport.Start(a)),
            reporter.currentItemChanged(state(PLAYING, receiver = a, current = a)),
        )
        // And the other way round: the item is current before the receiver plays it.
        assertEquals(none, reporter.currentItemChanged(state(PLAYING, receiver = a, current = a)))
    }

    @Test
    fun `a current item the receiver does not play yet is not started`() {
        assertEquals(none, reporter.currentItemChanged(state(BUFFERING)))
        assertEquals(none, reporter.currentItemChanged(state(PAUSED)))
        assertEquals(none, reporter.currentItemChanged(state(IDLE)))
        assertFalse(reporter.isReporting)
    }

    @Test
    fun `a pause sends one report that says paused`() {
        startA()
        now = 4_000L

        assertEquals(
            listOf(CastReport.Progress(a, 4_000L, isPaused = true)),
            status(PAUSED, 4_000L),
        )
        now = 4_100L
        assertEquals(none, status(PAUSED, 4_000L))
        now = 60_000L
        assertEquals(none, status(PAUSED, 4_000L))
    }

    @Test
    fun `a resume sends one report that says playing`() {
        startA()
        now = 4_000L
        status(PAUSED, 4_000L)
        now = 30_000L

        assertEquals(
            listOf(CastReport.Progress(a, 4_000L, isPaused = false)),
            status(PLAYING, 4_000L),
        )
        now = 30_200L
        assertEquals(none, status(PLAYING, 4_200L))
    }

    @Test
    fun `buffering is neither paused nor playing`() {
        startA()
        now = 3_000L
        assertEquals(none, status(BUFFERING, 3_000L))
        now = 9_000L
        // Six seconds of buffering: the position stood still, and that is no seek.
        assertEquals(none, status(BUFFERING, 3_000L))
        assertEquals(none, status(PLAYING, 3_000L))

        now = 12_000L
        status(PAUSED, 6_000L)
        assertEquals(none, status(BUFFERING, 6_000L))
        assertEquals(
            listOf(CastReport.Progress(a, 6_000L, isPaused = false)),
            status(PLAYING, 6_000L),
        )
    }

    @Test
    fun `a seek while playing sends one report`() {
        startA()
        now = 5_000L

        // The receiver buffers at the new position and then plays on.
        assertEquals(
            listOf(CastReport.Progress(a, 600_000L, isPaused = false)),
            status(BUFFERING, 600_000L),
        )
        now = 5_400L
        assertEquals(none, status(BUFFERING, 600_000L))
        assertEquals(none, status(PLAYING, 600_000L))
        now = 6_400L
        assertEquals(none, status(PLAYING, 601_000L))
    }

    @Test
    fun `a seek back sends one report`() {
        startA()
        now = 60_000L

        assertEquals(
            listOf(CastReport.Progress(a, 50_000L, isPaused = false)),
            status(PLAYING, 50_000L),
        )
    }

    @Test
    fun `a seek while paused sends one report that says paused`() {
        startA()
        now = 5_000L
        status(PAUSED, 5_000L)
        now = 20_000L

        assertEquals(
            listOf(CastReport.Progress(a, 90_000L, isPaused = true)),
            status(PAUSED, 90_000L),
        )
        assertEquals(none, status(PAUSED, 90_000L))
    }

    @Test
    fun `a position that is where the clock puts it is no seek`() {
        startA()
        now = 7_000L
        // A second and a half off is the receiver's own slack.
        assertEquals(none, status(PLAYING, 8_500L))
        now = 9_000L
        assertEquals(none, status(PLAYING, 9_000L))

        // More than two seconds off is a jump.
        now = 9_500L
        assertEquals(
            listOf(CastReport.Progress(a, 12_000L, isPaused = false)),
            status(PLAYING, 12_000L),
        )
    }

    @Test
    fun `a tick sends a report once the interval has passed`() {
        startA()

        now = 10_000L
        assertEquals(
            listOf(CastReport.Progress(a, 10_000L, isPaused = false)),
            reporter.tick(state(PLAYING, 10_000L)),
        )
        now = 20_000L
        assertEquals(
            listOf(CastReport.Progress(a, 20_000L, isPaused = false)),
            reporter.tick(state(PLAYING, 20_000L)),
        )
    }

    @Test
    fun `a tick that comes before the interval has passed is dropped`() {
        startA()
        now = 1_000L
        // The timer fires at once when it is started.
        assertEquals(none, reporter.tick(state(PLAYING, 1_000L)))

        now = 8_000L
        status(PAUSED, 8_000L)
        now = 9_000L
        status(PLAYING, 8_000L)

        // One second after the report of the resume: the interval counts from that report.
        now = 10_000L
        assertEquals(none, reporter.tick(state(PLAYING, 9_000L)))
        now = 18_000L
        assertEquals(none, reporter.tick(state(PLAYING, 17_000L)))
        now = 19_000L
        assertEquals(
            listOf(CastReport.Progress(a, 18_000L, isPaused = false)),
            reporter.tick(state(PLAYING, 18_000L)),
        )
    }

    @Test
    fun `a timer that fires a little early still counts`() {
        startA()

        now = 9_600L
        assertEquals(
            listOf(CastReport.Progress(a, 9_600L, isPaused = false)),
            reporter.tick(state(PLAYING, 9_600L)),
        )
    }

    @Test
    fun `a tick while buffering keeps the reports coming`() {
        startA()
        now = 2_000L
        status(BUFFERING, 2_000L)

        now = 10_000L
        assertEquals(
            listOf(CastReport.Progress(a, 2_000L, isPaused = false)),
            reporter.tick(state(BUFFERING, 2_000L)),
        )
    }

    @Test
    fun `a tick while paused sends nothing`() {
        startA()
        now = 2_000L
        status(PAUSED, 2_000L)

        now = 60_000L
        assertEquals(none, reporter.tick(state(PAUSED, 2_000L)))
    }

    @Test
    fun `a tick for another item than the one that reports sends nothing`() {
        startA()
        now = 10_000L

        assertEquals(none, reporter.tick(state(PLAYING, 0L, receiver = b, current = a)))
        assertEquals(none, reporter.tick(state(PLAYING, 0L, receiver = b, current = b)))
        assertEquals(none, CastPlaybackReporter(clock = { now }).tick(state(PLAYING, 10_000L)))
    }

    @Test
    fun `an item that ends stops once, with where it was`() {
        startA()
        reporter.positionChanged(a, positionMs = 1_190_000L, durationMs = 1_200_000L)

        assertEquals(listOf(CastReport.Stop(a, 1_190_000L, playedPercentage = 99)), status(ENDED))
        assertFalse(reporter.isReporting)
        assertEquals(none, status(ENDED))
        assertEquals(none, status(IDLE))
        assertEquals(none, reporter.stop())
    }

    @Test
    fun `an error stops the item`() {
        startA()
        reporter.positionChanged(a, positionMs = 300_000L, durationMs = 1_200_000L)

        assertEquals(listOf(CastReport.Stop(a, 300_000L, playedPercentage = 25)), status(ERROR))
    }

    @Test
    fun `idle with an item is between two loads, idle without one is the end`() {
        startA()

        assertEquals(none, reporter.statusChanged(state(IDLE, receiver = a)))
        assertTrue(reporter.isReporting)
        assertEquals(
            listOf(CastReport.Stop(a, 0L, playedPercentage = null)),
            reporter.statusChanged(state(IDLE, receiver = null)),
        )
    }

    @Test
    fun `a stop names the last position the receiver gave`() {
        startA()
        reporter.positionChanged(a, positionMs = 30_000L, durationMs = 120_000L)
        // The position of another item is not this one's.
        reporter.positionChanged(b, positionMs = 99_000L, durationMs = 100_000L)
        assertEquals(listOf(CastReport.Stop(a, 30_000L, playedPercentage = 25)), reporter.stop())
    }

    @Test
    fun `a stop names the position of the last report when that is the latest`() {
        startA()
        reporter.positionChanged(a, positionMs = 30_000L, durationMs = 120_000L)
        now = 31_000L
        status(PAUSED, 60_000L)

        assertEquals(listOf(CastReport.Stop(a, 60_000L, playedPercentage = 50)), reporter.stop())
    }

    @Test
    fun `a stop without a known duration has no percentage`() {
        startA()
        reporter.positionChanged(a, positionMs = 30_000L, durationMs = 0L)

        assertEquals(listOf(CastReport.Stop(a, 30_000L, playedPercentage = null)), reporter.stop())
    }

    @Test
    fun `a duration is kept until the playlist is forgotten`() {
        reporter.durationChanged(a, 120_000L)
        // A receiver that has not got the duration yet gives 0, and that says nothing.
        reporter.durationChanged(a, 0L)
        startA()
        reporter.positionChanged(a, positionMs = 60_000L, durationMs = -1L)
        assertEquals(listOf(CastReport.Stop(a, 60_000L, playedPercentage = 50)), reporter.stop())

        reporter.forget()
        now = 100_000L
        assertEquals(listOf(CastReport.Start(a)), status(PLAYING, 60_000L))
        assertEquals(listOf(CastReport.Stop(a, 60_000L, playedPercentage = null)), reporter.stop())
    }

    @Test
    fun `a position past the end is a hundred percent`() {
        startA()
        reporter.positionChanged(a, positionMs = 130_000L, durationMs = 120_000L)

        assertEquals(listOf(CastReport.Stop(a, 130_000L, playedPercentage = 100)), reporter.stop())
    }

    @Test
    fun `the receiver moving on stops the old item and starts the new one`() {
        startA()
        reporter.positionChanged(a, positionMs = 1_200_000L, durationMs = 1_200_000L)
        now = 1_200_000L

        // The queue changes first, then the status, and the current item follows last.
        assertEquals(none, reporter.queueChanged(a))
        assertEquals(
            listOf(CastReport.Stop(a, 1_200_000L, playedPercentage = 100)),
            reporter.queueChanged(b),
        )
        assertEquals(none, reporter.queueChanged(b))
        assertEquals(none, reporter.statusChanged(state(PLAYING, receiver = b, current = a)))
        assertEquals(none, reporter.tick(state(PLAYING, receiver = b, current = a)))
        assertEquals(
            listOf(CastReport.Start(b)),
            reporter.currentItemChanged(state(PLAYING, receiver = b, current = b)),
        )
        assertEquals(none, reporter.statusChanged(state(PLAYING, receiver = b, current = b)))
    }

    @Test
    fun `a new item that plays before the queue said so stops the old one first`() {
        startA()
        reporter.positionChanged(a, positionMs = 600_000L, durationMs = 1_200_000L)
        now = 600_000L

        assertEquals(
            listOf(CastReport.Stop(a, 600_000L, playedPercentage = 50), CastReport.Start(b)),
            reporter.statusChanged(state(PLAYING, receiver = b, current = b)),
        )
        assertEquals(none, reporter.queueChanged(b))
    }

    @Test
    fun `the new item's reports start from its own position`() {
        startA()
        reporter.positionChanged(a, positionMs = 600_000L, durationMs = 1_200_000L)
        now = 600_000L
        reporter.statusChanged(state(PLAYING, receiver = b, current = b))

        // No seek: item b started at 0 just now.
        now = 601_000L
        assertEquals(
            none,
            reporter.statusChanged(state(PLAYING, 1_000L, receiver = b, current = b)),
        )
        assertEquals(listOf(CastReport.Stop(b, 1_000L, playedPercentage = null)), reporter.stop())
    }

    @Test
    fun `an audio switch stops the item and starts it again when the new stream plays`() {
        startA()
        reporter.positionChanged(a, positionMs = 40_000L, durationMs = 120_000L)
        now = 40_000L

        // While the new stream is asked for, the old one plays on and still reports.
        assertEquals(none, reporter.statusChanged(state(PLAYING, 40_000L, loadPending = true)))
        now = 50_000L
        assertEquals(
            listOf(CastReport.Progress(a, 50_000L, isPaused = false)),
            reporter.tick(state(PLAYING, 50_000L, loadPending = true)),
        )

        // The stream changes, and with it the play session the reports name.
        assertEquals(listOf(CastReport.Stop(a, 50_000L, playedPercentage = 41)), reporter.stop())
        // The receiver has not got the load yet and still plays the old stream.
        now = 50_100L
        assertEquals(none, reporter.statusChanged(state(PLAYING, 50_100L, loadPending = true)))
        assertEquals(none, reporter.statusChanged(state(BUFFERING, 50_000L, loadPending = true)))

        now = 52_000L
        assertEquals(
            listOf(CastReport.Start(a)),
            reporter.currentItemChanged(state(PLAYING, 50_000L)),
        )
        assertEquals(none, reporter.statusChanged(state(PLAYING, 50_000L)))
    }

    @Test
    fun `the item that was playing does not start again while another one loads`() {
        // The user starts item b while a plays: a is stopped, and until the load reaches the
        // receiver it still says it plays a.
        startA()
        reporter.positionChanged(a, positionMs = 40_000L, durationMs = 120_000L)
        assertEquals(listOf(CastReport.Stop(a, 40_000L, playedPercentage = 33)), reporter.stop())

        now = 300L
        assertEquals(none, reporter.statusChanged(state(PLAYING, 40_300L, loadPending = true)))
        assertEquals(none, reporter.currentItemChanged(state(PLAYING, 40_300L, loadPending = true)))
        assertEquals(none, reporter.queueChanged(b))

        now = 2_000L
        assertEquals(
            listOf(CastReport.Start(b)),
            reporter.currentItemChanged(state(PLAYING, receiver = b, current = b)),
        )
    }

    @Test
    fun `a disconnect stops the item once`() {
        startA()
        reporter.positionChanged(a, positionMs = 12_000L, durationMs = 120_000L)

        assertEquals(listOf(CastReport.Stop(a, 12_000L, playedPercentage = 10)), reporter.stop())
        assertEquals(none, reporter.stop())
        assertEquals(none, reporter.queueChanged(null))
    }

    @Test
    fun `an item that is played again starts again`() {
        startA()
        status(ENDED)

        now = 5_000L
        assertEquals(listOf(CastReport.Start(a)), status(PLAYING))
    }

    @Test
    fun `a negative position is reported as the start of the item`() {
        assertEquals(listOf(CastReport.Start(a)), status(PLAYING, positionMs = -1L))
        now = 1_000L

        assertEquals(listOf(CastReport.Progress(a, 0L, isPaused = true)), status(PAUSED, -1L))
    }

    @Test
    fun `an item that runs out stops at its end, not at the 0 the receiver shows on the way`() {
        // Seen on a receiver: at the end of an episode a status update carries position 0 for
        // the old item, and then the queue moves on.
        startA()
        reporter.durationChanged(a, 438_000L)
        now = 438_000L
        reporter.positionChanged(a, 438_000L, 438_000L)

        assertEquals(none, status(PLAYING, 0L))
        assertEquals(listOf(CastReport.Stop(a, 438_000L, 100)), reporter.queueChanged(b))
    }
}
