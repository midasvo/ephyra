package dev.jdtech.jellyfin.ce.presentation.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #458: the TV player never reported progress. It now reports every few seconds, when playback
 * pauses and on ON_PAUSE; the pause seen by the loop and the one on ON_PAUSE must not both go out.
 */
class TvProgressReportsTest {

    private var now = 100_000L
    private var playing = true
    private var sent = 0
    private val reports = TvProgressReports(send = { sent++ }, isPlaying = { playing }, now = { now })

    @Test
    fun `the first report goes out`() {
        reports.report()

        assertEquals(1, sent)
    }

    @Test
    fun `each tick of the loop goes out`() {
        repeat(3) {
            reports.report()
            now += TvProgressReports.INTERVAL_MS
        }

        assertEquals(3, sent)
    }

    @Test
    fun `a pause seen twice in the same moment goes out once`() {
        reports.report()
        now += 2_000L
        playing = false
        reports.report() // the loop sees the pause
        now += 40L
        reports.report() // ON_PAUSE, right after

        assertEquals(2, sent)
    }

    @Test
    fun `a change of play state goes out at once`() {
        reports.report()
        now += 200L
        playing = false

        reports.report()

        assertEquals(2, sent)
    }

    @Test
    fun `the same state after the gap goes out again`() {
        playing = false
        reports.report()
        now += TvProgressReports.MIN_GAP_MS

        reports.report()

        assertEquals(2, sent)
    }
}
