package dev.jdtech.jellyfin.ce.presentation.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #464: the seek bar's thumbnail covered the title, the skip button and the "Up next" card. They
 * now give way while a thumbnail shows; the thumbnail itself stays above the bar, inside its edges.
 */
class TvTrickplayThumbnailTest {

    @Test
    fun `a pending seek with a thumbnail shows the preview`() {
        assertTrue(seekPreviewShown(pendingSeek = true, hasFrame = true))
    }

    @Test
    fun `an item without trickplay keeps its title while seeking`() {
        assertFalse(seekPreviewShown(pendingSeek = true, hasFrame = false))
    }

    @Test
    fun `no pending seek shows no preview, also with a thumbnail still loaded`() {
        assertFalse(seekPreviewShown(pendingSeek = false, hasFrame = true))
        assertFalse(seekPreviewShown(pendingSeek = false, hasFrame = false))
    }

    @Test
    fun `the thumbnail is centred on the position`() {
        assertEquals(380, aboveAtX(width = 1000, elementWidth = 240, fraction = 0.5f))
    }

    @Test
    fun `the thumbnail stays inside the bar at its start and its end`() {
        assertEquals(0, aboveAtX(width = 1000, elementWidth = 240, fraction = 0f))
        assertEquals(0, aboveAtX(width = 1000, elementWidth = 240, fraction = 0.05f))
        assertEquals(760, aboveAtX(width = 1000, elementWidth = 240, fraction = 0.95f))
        assertEquals(760, aboveAtX(width = 1000, elementWidth = 240, fraction = 1f))
    }

    @Test
    fun `a position outside the item is clamped`() {
        assertEquals(0, aboveAtX(width = 1000, elementWidth = 240, fraction = -1f))
        assertEquals(760, aboveAtX(width = 1000, elementWidth = 240, fraction = 2f))
    }

    @Test
    fun `a thumbnail wider than the bar starts at its left edge`() {
        assertEquals(0, aboveAtX(width = 200, elementWidth = 240, fraction = 0.5f))
    }
}
