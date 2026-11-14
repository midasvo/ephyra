package dev.jdtech.jellyfin.player.local.mpv

import androidx.media3.common.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which video track's frame rate [selectedVideoFrameRate] hints the display with. */
class SelectedVideoFrameRateTest {

    private fun video(frameRate: Float, selected: Boolean) =
        VideoTrackCandidate(selected, frameRate)

    @Test
    fun `the selected video track wins over an attached picture listed after it`() {
        val tracks = listOf(video(23.976f, selected = true), video(1f, selected = false))

        assertEquals(23.976f, selectedVideoFrameRate(tracks))
    }

    @Test
    fun `the selected video track wins over one listed before it`() {
        val tracks = listOf(video(1f, selected = false), video(25f, selected = true))

        assertEquals(25f, selectedVideoFrameRate(tracks))
    }

    @Test
    fun `the first video track is used when none is selected yet`() {
        val tracks = listOf(video(24f, selected = false), video(1f, selected = false))

        assertEquals(24f, selectedVideoFrameRate(tracks))
    }

    @Test
    fun `no video track or an unknown frame rate gives nothing to hint`() {
        assertNull(selectedVideoFrameRate(emptyList()))
        assertNull(
            selectedVideoFrameRate(listOf(video(Format.NO_VALUE.toFloat(), selected = true)))
        )
    }
}
