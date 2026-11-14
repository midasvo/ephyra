package dev.jdtech.jellyfin.player.local.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportedPauseTest {

    @Test
    fun `nothing reported yet needs no report when playing`() {
        assertFalse(ReportedPause().needsReportWhenPlaying())
    }

    @Test
    fun `a resume reported while still buffering is corrected when it plays`() {
        val state = ReportedPause()
        state.onReported(isPaused = false)
        state.onReported(isPaused = true) // pause
        state.onReported(isPaused = true) // resume, not playing yet
        assertTrue(state.needsReportWhenPlaying())
    }

    @Test
    fun `a stall after a playing report sends nothing`() {
        val state = ReportedPause()
        state.onReported(isPaused = false)
        assertFalse(state.needsReportWhenPlaying())
    }

    @Test
    fun `the correction itself ends the need`() {
        val state = ReportedPause()
        state.onReported(isPaused = true)
        state.onReported(isPaused = false)
        assertFalse(state.needsReportWhenPlaying())
    }
}
