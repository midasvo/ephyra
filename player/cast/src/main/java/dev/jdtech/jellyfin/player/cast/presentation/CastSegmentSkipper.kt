package dev.jdtech.jellyfin.player.cast.presentation

import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.Segment
import dev.jdtech.jellyfin.player.cast.CastPositions
import dev.jdtech.jellyfin.player.local.domain.SegmentDecision
import dev.jdtech.jellyfin.player.local.domain.SegmentTracker
import dev.jdtech.jellyfin.player.local.domain.SkipTarget

/**
 * CE (#851): the local player's segment rule ([SegmentTracker]) applied to the positions a Cast
 * receiver reports for one item, [itemId]. One instance per item: a new item gets a new one.
 *
 * The rule is the same as on the device: a segment set to skip is skipped once per entry, and again
 * after the user leaves it and comes back; one set to ask shows the button; an ignored one (and one
 * of a type without a setting, such as `UNKNOWN`) shows nothing. Seeking back inside a skipped
 * segment does not skip it again. Segments that arrive while the position is already inside one are
 * handled as entering it.
 *
 * Only positions the receiver reported for [itemId] count, so Cast adds these guards:
 * - **Another item's position:** a report for another item (the queue moved on and the controller
 *   has not caught up yet), or no item (a load on its way, an item that ended), is no step.
 * - **Resume, after every load:** until the receiver is at the position the item was loaded at
 *   ([resumePositionMs] for the first load, [expectLoad] for each load after it, such as an audio
 *   track switch), or plays on from where it is, a report is the stale position from before the
 *   load, and no step. A user seek, or the seek target the controller shows at once, does not end
 *   this wait.
 * - **The end of the item:** the receiver is at position 0 for a moment when an item runs out
 *   ([CastPositions.ranOut], as the playback reports read it). That is no entry.
 * - **Late reports after a skip:** the controller shows the target at once, but the receiver can
 *   still report a position from before the seek. For [SETTLE_MS] after a skip, a report inside
 *   that segment is dropped before the tracker sees it. A user seek drops the guard of the segment
 *   it lands in only ([onUserSeek]).
 *
 * Differences from the local player, on purpose:
 * - The button shows for the whole segment of an "ask" type: the local player shows it after its
 *   time only while the controls are visible, and the Cast controls are always on screen.
 * - No picture-in-picture: with auto-skip set to "only in picture-in-picture", Cast shows the
 *   button.
 * - A skip seeks to the segment's end, also for an outro that closes the item; one that runs to the
 *   end (or past it) lands [END_MARGIN_MS] before the end, so the receiver reports a position there
 *   (the item counts as played), ends the item and moves its queue on by itself, once.
 *
 * Pure: no Android, no Cast SDK. [now] is a monotonic clock in milliseconds.
 */
class CastSegmentSkipper(
    val itemId: ItemId,
    private val tracker: SegmentTracker,
    private val now: () -> Long,
    private val resumePositionMs: Long = 0L,
) {
    /** The segments skipped in the last [SETTLE_MS], with when. */
    private val recentSkips = mutableMapOf<Segment, Long>()

    /** Where the last load started the item. */
    private var loadPositionMs = resumePositionMs

    /** The receiver is where the last load started the item. */
    private var settled = resumePositionMs <= CastPositions.SEEK_TOLERANCE_MS

    /** The last position reported for [itemId], or the user's last seek target. */
    private var lastPositionMs: Long? = null

    /** The latest positive duration seen for [itemId], kept across brief 0-duration blips. */
    private var knownDurationMs = 0L

    /**
     * The item was loaded again (the same item: an audio track switch, a second play), starting at
     * [positionMs]. Until the receiver is there, its reports are stale, as after the first load.
     */
    fun expectLoad(positionMs: Long) {
        loadPositionMs = positionMs
        settled = positionMs <= CastPositions.SEEK_TOLERANCE_MS
        lastPositionMs = null
    }

    /** The item's segments, when they have loaded. */
    fun setSegments(segments: List<Segment>) {
        tracker.setSegments(segments)
        recentSkips.clear()
    }

    /**
     * The user moved the position to [targetMs]. A report inside a skipped segment that contains
     * the target is then not a late one; the guards of the other segments stay.
     */
    fun onUserSeek(targetMs: Long) {
        recentSkips.keys.removeAll { targetMs in it }
        // Until the receiver is where the load started the item, the next report can still be the
        // stale one: the seek does not end that wait.
        if (!settled) return
        lastPositionMs = targetMs
    }

    /**
     * What to do at [positionMs], which the receiver reported for [positionItemId], in an item of
     * [durationMs] (0 when unknown). [fromSeek]: the position is the target of a seek the app sent,
     * shown before the receiver reported it; it does not end the wait for a load's position.
     */
    fun update(
        positionItemId: ItemId?,
        positionMs: Long,
        durationMs: Long,
        fromSeek: Boolean = false,
    ): CastSegmentStep {
        if (positionItemId != itemId) return CastSegmentStep.None
        if (fromSeek && !settled) return CastSegmentStep.None
        if (durationMs > 0L) knownDurationMs = durationMs
        val effectiveDurationMs = if (durationMs > 0L) durationMs else knownDurationMs
        val previous = lastPositionMs
        if (previous != null && CastPositions.ranOut(previous, positionMs, effectiveDurationMs)) {
            return CastSegmentStep.None
        }
        lastPositionMs = positionMs
        if (!settled) {
            val atResume = positionMs >= loadPositionMs - CastPositions.SEEK_TOLERANCE_MS
            val playsOn = previous != null && positionMs - previous in 1..PLAYS_ON_MS
            if (!atResume && !playsOn) return CastSegmentStep.None
            settled = true
        }

        val nowMs = now()
        recentSkips.values.removeAll { nowMs - it >= SETTLE_MS }
        // A report from before a seek landed. It does not reach the tracker, so it cannot count
        // as an entry, and a real one after it is still skipped.
        if (recentSkips.keys.any { positionMs in it }) {
            // Going back, it is older than the position heard before: keep that one, so the
            // moment at 0 after a skip to the end is still read as the end.
            if (previous != null && positionMs < previous) lastPositionMs = previous
            return CastSegmentStep.None
        }

        val decision =
            tracker.update(
                positionMs = positionMs,
                durationMs = durationMs,
                hasNextItem = false,
                inPictureInPicture = false,
            )
        return when (decision) {
            SegmentDecision.None -> CastSegmentStep.None
            is SegmentDecision.Ask -> CastSegmentStep.ShowButton(decision.segment)
            is SegmentDecision.AutoSkip -> {
                val segment = decision.segment
                val end = (decision.target as? SkipTarget.SeekTo)?.positionMs ?: segment.endTicks
                val target =
                    if (effectiveDurationMs > 0L) {
                        end.coerceAtMost(effectiveDurationMs - END_MARGIN_MS)
                    } else {
                        end
                    }
                if (target <= positionMs) {
                    CastSegmentStep.None
                } else {
                    recentSkips[segment] = nowMs
                    // Where the receiver goes, even if it ends the item before reporting it.
                    lastPositionMs = target
                    CastSegmentStep.SeekTo(segment, target)
                }
            }
        }
    }

    private operator fun Segment.contains(positionMs: Long) =
        positionMs >= startTicks && positionMs < endTicks

    companion object {
        /** How long after a skip a report inside the skipped segment counts as a late one. */
        const val SETTLE_MS = 5_000L

        /** A skip to the end of the item lands this far before it. */
        const val END_MARGIN_MS = 2_000L

        /** Two reports this far apart or less, forwards, are playback going on. */
        const val PLAYS_ON_MS = 3_000L
    }
}

/** What Cast should do about the segment at the receiver's position. */
sealed interface CastSegmentStep {
    /** Not in a segment, or in one that is ignored or was just skipped. */
    data object None : CastSegmentStep

    /** Show the skip button for [segment]. */
    data class ShowButton(val segment: Segment) : CastSegmentStep

    /** Seek the receiver to [positionMs], past [segment]. */
    data class SeekTo(val segment: Segment, val positionMs: Long) : CastSegmentStep
}
