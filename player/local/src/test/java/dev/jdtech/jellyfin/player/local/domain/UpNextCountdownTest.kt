package dev.jdtech.jellyfin.player.local.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [UpNextCountdown] (#272). */
class UpNextCountdownTest {
    private val duration = 600_000L

    private fun UpNextCountdown.at(
        positionMs: Long,
        itemId: String = "a",
        outroStartMs: Long? = null,
        eligible: Boolean = true,
        autoPlay: Boolean = true,
        speed: Float = 1f,
    ) = update(itemId, positionMs, duration, outroStartMs, eligible, autoPlay, speed)

    @Test
    fun `hidden before the last 15 seconds`() {
        val countdown = UpNextCountdown()
        assertEquals(UpNextDecision.Hidden, countdown.at(duration - 15_001L))
    }

    @Test
    fun `counts down the last 15 seconds and leaves the end to the player`() {
        val countdown = UpNextCountdown()
        assertEquals(UpNextDecision.Show(15, 1f), countdown.at(duration - 15_000L))
        assertEquals(UpNextDecision.Show(10, 10f / 15), countdown.at(duration - 10_000L))
        assertEquals(UpNextDecision.Show(1, 0.5f / 15), countdown.at(duration - 500L))
        assertEquals(UpNextDecision.Show(0, 0f), countdown.at(duration))
    }

    @Test
    fun `an outro opens the window and the countdown advances before the end`() {
        val countdown = UpNextCountdown()
        val outro = duration - 90_000L
        assertEquals(UpNextDecision.Hidden, countdown.at(outro - 1L, outroStartMs = outro))
        assertEquals(UpNextDecision.Show(15, 1f), countdown.at(outro, outroStartMs = outro))
        assertEquals(
            UpNextDecision.Show(1, 1f / 15),
            countdown.at(outro + 14_000L, outroStartMs = outro),
        )
        assertEquals(UpNextDecision.Advance, countdown.at(outro + 15_000L, outroStartMs = outro))
        // Once only.
        assertEquals(UpNextDecision.Hidden, countdown.at(outro + 16_000L, outroStartMs = outro))
    }

    @Test
    fun `a short outro at the end does not open the window early`() {
        val countdown = UpNextCountdown()
        assertEquals(duration - 15_000L, countdown.windowStartMs(duration, duration - 5_000L))
    }

    @Test
    fun `the countdown starts where the card first showed`() {
        val countdown = UpNextCountdown()
        val outro = duration - 90_000L
        // Seeked into the middle of a long outro: the full countdown, not an instant advance.
        assertEquals(
            UpNextDecision.Show(15, 1f),
            countdown.at(outro + 40_000L, outroStartMs = outro),
        )
        assertEquals(UpNextDecision.Advance, countdown.at(outro + 55_000L, outroStartMs = outro))
    }

    @Test
    fun `seeking back out of the window hides the card and restarts the countdown`() {
        val countdown = UpNextCountdown()
        countdown.at(duration - 10_000L)
        assertEquals(UpNextDecision.Hidden, countdown.at(duration - 60_000L))
        assertEquals(UpNextDecision.Show(15, 1f), countdown.at(duration - 15_000L))
    }

    @Test
    fun `without autoplay the card shows with no countdown and never advances`() {
        val countdown = UpNextCountdown()
        val outro = duration - 90_000L
        assertEquals(
            UpNextDecision.Show(null, null),
            countdown.at(outro + 30_000L, outroStartMs = outro, autoPlay = false),
        )
    }

    @Test
    fun `nothing when not eligible`() {
        val countdown = UpNextCountdown()
        assertEquals(UpNextDecision.Hidden, countdown.at(duration - 5_000L, eligible = false))
        assertEquals(
            UpNextDecision.Hidden,
            countdown.update("a", 0L, durationMs = 0L, null, eligible = true, autoPlay = true),
        )
    }

    @Test
    fun `cancel hides the card for this item only`() {
        val countdown = UpNextCountdown()
        countdown.at(duration - 10_000L)
        countdown.cancel("a")
        assertTrue(countdown.isCancelled("a"))
        assertEquals(UpNextDecision.Hidden, countdown.at(duration - 5_000L))
        assertEquals(UpNextDecision.Show(5, 5f / 15), countdown.at(duration - 5_000L, itemId = "b"))
        assertFalse(countdown.isCancelled("a"))
        assertFalse(countdown.isCancelled("b"))
    }

    @Test
    fun `the seconds are real ones at a faster speed`() {
        val countdown = UpNextCountdown()
        val outro = duration - 90_000L
        assertEquals(
            UpNextDecision.Show(15, 1f),
            countdown.at(outro, outroStartMs = outro, speed = 2f),
        )
        assertEquals(
            UpNextDecision.Show(10, 10f / 15),
            countdown.at(outro + 10_000L, outroStartMs = outro, speed = 2f),
        )
        assertEquals(
            UpNextDecision.Advance,
            countdown.at(outro + 30_000L, outroStartMs = outro, speed = 2f),
        )
    }
}
