package dev.jdtech.jellyfin.player.local.domain

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for [playbackStopReport]: which stop an item that is left gets (#461). */
class PlaybackStopReportTest {

    @Test
    fun `a loaded item reports its position and percentage`() {
        assertEquals(
            PlaybackStopReport(positionTicks = 8_639L * 10000, playedPercentage = 0),
            playbackStopReport(positionMs = 8_639L, durationMs = 4_466_294L, startSent = true),
        )
        assertEquals(
            PlaybackStopReport(positionTicks = 2_000_000L * 10000, playedPercentage = 50),
            playbackStopReport(positionMs = 2_000_000L, durationMs = 4_000_000L, startSent = true),
        )
    }

    @Test
    fun `a loaded item reports even if this snapshot did not send the start`() {
        // Unchanged from before #461: a known duration always gets its stop.
        assertEquals(
            PlaybackStopReport(positionTicks = 0L, playedPercentage = 0),
            playbackStopReport(positionMs = 0L, durationMs = 4_466_462L, startSent = false),
        )
    }

    @Test
    fun `an item left while loading after its start reports the start position without a percentage`() {
        assertEquals(
            PlaybackStopReport(positionTicks = 38_461_200_000L, playedPercentage = null),
            playbackStopReport(positionMs = 3_846_120L, durationMs = C.TIME_UNSET, startSent = true),
        )
        assertEquals(
            PlaybackStopReport(positionTicks = 0L, playedPercentage = null),
            playbackStopReport(positionMs = 0L, durationMs = C.TIME_UNSET, startSent = true),
        )
    }

    @Test
    fun `an item left while loading before its start owes no stop`() {
        assertNull(playbackStopReport(positionMs = 0L, durationMs = C.TIME_UNSET, startSent = false))
        assertNull(playbackStopReport(positionMs = 5_000L, durationMs = 0L, startSent = false))
    }

    @Test
    fun `an item left from its closing outro reports the end of the item`() {
        // #616: an outro that starts at 80 %, skipped at once, still counts as watched.
        assertEquals(
            PlaybackStopReport(positionTicks = 1_500_000L * 10000, playedPercentage = 100),
            playbackStopReport(
                positionMs = 1_200_000L,
                durationMs = 1_500_000L,
                startSent = true,
                finished = true,
            ),
        )
    }

    @Test
    fun `a finished item without a known duration reports as any other`() {
        assertEquals(
            PlaybackStopReport(positionTicks = 30_000L * 10000, playedPercentage = null),
            playbackStopReport(
                positionMs = 30_000L,
                durationMs = C.TIME_UNSET,
                startSent = true,
                finished = true,
            ),
        )
    }
}
