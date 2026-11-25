package dev.jdtech.jellyfin.settings.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class VideoQualitiesTest {
    @Test
    fun `labels name bitrate only and keep stored values unchanged`() {
        val expected =
            listOf(
                0 to null,
                120_000_000 to "120 Mbps",
                80_000_000 to "80 Mbps",
                40_000_000 to "40 Mbps",
                20_000_000 to "20 Mbps",
                10_000_000 to "10 Mbps",
                8_000_000 to "8 Mbps",
                6_000_000 to "6 Mbps",
                4_000_000 to "4 Mbps",
                3_000_000 to "3 Mbps",
                1_500_000 to "1.5 Mbps",
                720_000 to "720 kbps",
            )

        assertEquals(expected.map { it.first }, VideoQualities.bitrates)
        for ((bitrate, label) in expected) {
            assertEquals(label, VideoQualities.label(bitrate))
            if (label != null) {
                assertFalse(label.contains("4K"))
                assertFalse(label.contains("1080p"))
                assertFalse(label.contains("720p"))
                assertFalse(label.contains("480p"))
                assertFalse(label.contains("360p"))
            }
        }
        assertNull(VideoQualities.label(0))
    }
}
