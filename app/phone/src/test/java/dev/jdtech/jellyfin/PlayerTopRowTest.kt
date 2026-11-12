package dev.jdtech.jellyfin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The player's top row starts at its end when it does not fit, and stays there until moved. */
class PlayerTopRowTest {
    @Test
    fun `a row that fits does not scroll`() {
        assertEquals(0, topRowMaxScroll(contentWidth = 900, viewportWidth = 1000))
        assertEquals(0, topRowMaxScroll(contentWidth = 1000, viewportWidth = 1000))
    }

    @Test
    fun `a row that does not fit scrolls by what is cut off`() {
        assertEquals(170, topRowMaxScroll(contentWidth = 1470, viewportWidth = 1300))
    }

    @Test
    fun `a row that fits follows its end`() {
        assertTrue(topRowFollowsEnd(scrollX = 0, maxScroll = 0))
    }

    @Test
    fun `a row at its end follows it`() {
        assertTrue(topRowFollowsEnd(scrollX = 170, maxScroll = 170))
    }

    @Test
    fun `a row the user scrolled away from its end stays where it is`() {
        assertFalse(topRowFollowsEnd(scrollX = 0, maxScroll = 170))
        assertFalse(topRowFollowsEnd(scrollX = 169, maxScroll = 170))
    }
}
