package dev.jdtech.jellyfin.player.local.domain

import kotlin.math.ceil

/**
 * CE (#272): when the "Up next" card shows at the end of an episode, and when its countdown runs
 * out.
 *
 * Pure: no Android, no player.
 * [PlayerViewModel][dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel] calls [update] a
 * few times a second with the player's position and acts on the [UpNextDecision].
 *
 * - The card's window opens [countdownMs] before the end of the item, or at the start of an outro
 *   that runs to the end ([update]'s `outroStartMs`) when that comes earlier.
 * - The countdown is [countdownMs] of playback from where the card first showed, never past the end
 *   of the item. It follows the position, so it stands still while paused, and seeking back inside
 *   the window starts it again from there. Seeking back out of the window hides the card.
 * - When the countdown ends before the item does (an outro), [update] returns
 *   [UpNextDecision.Advance] once. When it ends with the item, the player's own end of item does
 *   the advance, so nothing is returned for it.
 * - Without autoplay the card shows without a countdown and never advances.
 * - [cancel] hides the card for the rest of the item.
 */
class UpNextCountdown(private val countdownMs: Long = DEFAULT_COUNTDOWN_MS) {
    private var itemId: String? = null
    private var shownAtMs: Long? = null
    private var cancelled = false
    private var advanced = false

    /** True when the card was cancelled for [itemId], so its end must not start the next item. */
    fun isCancelled(itemId: String?): Boolean = itemId != null && itemId == this.itemId && cancelled

    /** The card was dismissed for [itemId]: it stays hidden until another item plays. */
    fun cancel(itemId: String?) {
        forItem(itemId)
        cancelled = true
    }

    /**
     * What to show at [positionMs] of [itemId], which runs [durationMs] (0 or less when unknown).
     * [eligible] is false when no card can show at all (not an episode, no next item, the sleep
     * timer or the still-watching prompt takes the end of the item). [speed] is the playback speed,
     * so the seconds shown are real ones.
     */
    fun update(
        itemId: String?,
        positionMs: Long,
        durationMs: Long,
        outroStartMs: Long?,
        eligible: Boolean,
        autoPlay: Boolean,
        speed: Float = 1f,
    ): UpNextDecision {
        forItem(itemId)
        if (!eligible || itemId == null || durationMs <= 0 || cancelled || advanced) {
            shownAtMs = null
            return UpNextDecision.Hidden
        }
        if (positionMs < windowStartMs(durationMs, outroStartMs)) {
            shownAtMs = null
            return UpNextDecision.Hidden
        }
        val shownAt = shownAtMs?.takeIf { positionMs >= it } ?: positionMs
        shownAtMs = shownAt
        if (!autoPlay) return UpNextDecision.Show(secondsLeft = null, fractionLeft = null)

        val rate = speed.coerceAtLeast(MIN_SPEED)
        val endMs = minOf(shownAt + (countdownMs * rate).toLong(), durationMs)
        if (positionMs >= endMs && endMs < durationMs - END_TOLERANCE_MS) {
            advanced = true
            return UpNextDecision.Advance
        }
        val leftMs = ((endMs - positionMs).coerceAtLeast(0L) / rate).toLong()
        return UpNextDecision.Show(
            secondsLeft = ceil(leftMs / 1000.0).toInt(),
            fractionLeft = (leftMs.toFloat() / countdownMs).coerceIn(0f, 1f),
        )
    }

    /** Where the card's window opens: the countdown before the end, or an earlier outro. */
    fun windowStartMs(durationMs: Long, outroStartMs: Long?): Long {
        val beforeEnd = durationMs - countdownMs
        val outro = outroStartMs?.takeIf { it in 0 until durationMs } ?: Long.MAX_VALUE
        return minOf(beforeEnd, outro).coerceAtLeast(0L)
    }

    private fun forItem(itemId: String?) {
        if (itemId == this.itemId) return
        this.itemId = itemId
        shownAtMs = null
        cancelled = false
        advanced = false
    }

    companion object {
        /** How long the countdown runs, and how long before the end the card shows by default. */
        const val DEFAULT_COUNTDOWN_MS = 15_000L

        /** A countdown ending this close to the end of the item leaves the advance to the end. */
        const val END_TOLERANCE_MS = 1_000L

        private const val MIN_SPEED = 0.1f
    }
}

/** What the "Up next" card does at the current position. */
sealed interface UpNextDecision {
    data object Hidden : UpNextDecision

    /**
     * Show the card. [secondsLeft] is the countdown, rounded up, and [fractionLeft] the share of it
     * left (1 to 0); both are null without autoplay.
     */
    data class Show(val secondsLeft: Int?, val fractionLeft: Float?) : UpNextDecision

    /** The countdown ran out before the item ended: start the next item now. */
    data object Advance : UpNextDecision
}
