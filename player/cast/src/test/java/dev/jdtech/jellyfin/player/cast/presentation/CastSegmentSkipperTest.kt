package dev.jdtech.jellyfin.player.cast.presentation

import dev.jdtech.jellyfin.models.MediaSegmentAction
import dev.jdtech.jellyfin.models.MediaSegmentAction.ASK
import dev.jdtech.jellyfin.models.MediaSegmentAction.IGNORE
import dev.jdtech.jellyfin.models.MediaSegmentAction.SKIP
import dev.jdtech.jellyfin.models.Segment
import dev.jdtech.jellyfin.models.SegmentType
import dev.jdtech.jellyfin.models.SegmentType.INTRO
import dev.jdtech.jellyfin.models.SegmentType.OUTRO
import dev.jdtech.jellyfin.models.SegmentType.RECAP
import dev.jdtech.jellyfin.models.SegmentType.UNKNOWN
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.player.local.domain.SegmentTracker
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit tests for [CastSegmentSkipper]: the local player's segment rule on the positions a Cast
 * receiver reports (#851), including the receiver's late, stale and foreign reports.
 */
class CastSegmentSkipperTest {
    private val item = "0b0e4e6f1c8a4d1f9a3b2c1d0e9f8a7b".toItemId()
    private val other = "1c1f5f7a2d9b4e2a8b4c3d2e1f0a9b8c".toItemId()
    private val duration = 720_000L
    private val recap = Segment(RECAP, startTicks = 0L, endTicks = 8_000L)
    private val intro = Segment(INTRO, startTicks = 10_000L, endTicks = 40_000L)
    private val outro = Segment(OUTRO, startTicks = 700_000L, endTicks = 715_000L)

    private var clockMs = 0L

    private fun skipper(
        actions: Map<SegmentType, MediaSegmentAction> =
            mapOf(INTRO to SKIP, OUTRO to SKIP, RECAP to IGNORE),
        onlyInPip: Boolean = false,
        resumePositionMs: Long = 0L,
        segments: List<Segment> = listOf(recap, intro, outro),
    ) =
        CastSegmentSkipper(
                itemId = item,
                tracker =
                    SegmentTracker(
                        actions = actions,
                        buttonDurationMs = 5_000L,
                        nextEpisodeThresholdMs = 5_000L,
                        autoSkipOnlyInPip = onlyInPip,
                    ),
                now = { clockMs },
                resumePositionMs = resumePositionMs,
            )
            .apply { setSegments(segments) }

    /** Reports [positions] one second apart, as the receiver does, and returns every step. */
    private fun CastSegmentSkipper.play(
        vararg positions: Long,
        durationMs: Long = duration,
    ): List<CastSegmentStep> = positions.map { position ->
        clockMs += 1_000L
        update(item, position, durationMs)
    }

    private fun List<CastSegmentStep>.seeks() = filterIsInstance<CastSegmentStep.SeekTo>()

    private fun seek(segment: Segment, to: Long = segment.endTicks) =
        CastSegmentStep.SeekTo(segment, to)

    @Test
    fun `a segment set to skip is skipped once, to its end`() {
        val steps = skipper().play(8_000L, 9_000L, 10_000L)

        assertEquals(listOf(seek(intro)), steps.seeks())
        assertEquals(seek(intro), steps.last())
    }

    @Test
    fun `entering the segment mid-way skips it too`() {
        assertEquals(listOf(seek(intro)), skipper().play(25_000L))
    }

    @Test
    fun `a segment set to ask shows the button and does not seek`() {
        val steps = skipper(actions = mapOf(INTRO to ASK)).play(10_000L, 11_000L, 39_000L)

        assertEquals(List(3) { CastSegmentStep.ShowButton(intro) }, steps)
    }

    @Test
    fun `an ignored segment shows nothing`() {
        assertEquals(List(3) { CastSegmentStep.None }, skipper().play(0L, 1_000L, 7_000L))
    }

    @Test
    fun `a segment of a type without a setting shows nothing, as in the local player`() {
        val unknown = Segment(UNKNOWN, startTicks = 10_000L, endTicks = 40_000L)
        val steps = skipper(segments = listOf(unknown)).play(10_000L, 11_000L)

        assertEquals(List(2) { CastSegmentStep.None }, steps)
    }

    @Test
    fun `the button goes just before the segment's end, as in the local player`() {
        val steps = skipper(actions = mapOf(INTRO to ASK)).play(39_850L, 39_950L)

        assertEquals(listOf(CastSegmentStep.ShowButton(intro), CastSegmentStep.None), steps)
    }

    @Test
    fun `with auto-skip only in picture-in-picture, Cast shows the button`() {
        val steps = skipper(onlyInPip = true).play(10_000L, 11_000L)

        assertEquals(List(2) { CastSegmentStep.ShowButton(intro) }, steps)
    }

    @Test
    fun `reports still inside the segment after the seek do not seek again`() {
        // The receiver has not done the seek yet: it goes on reporting the intro.
        val steps = skipper().play(10_000L, 11_000L, 12_000L, 13_000L)

        assertEquals(listOf(seek(intro)), steps.seeks())
        assertEquals(List(3) { CastSegmentStep.None }, steps.drop(1))
    }

    @Test
    fun `a late report from before the seek does not seek again`() {
        // The controller shows the target at once; then a report from before the seek arrives.
        val steps = skipper().play(10_000L, 40_000L, 11_000L, 41_000L, 42_000L)

        assertEquals(listOf(seek(intro)), steps.seeks())
    }

    @Test
    fun `a late report does not use up the next entry`() {
        // The late report must not count as an entry: the user's seek back right after it skips.
        val skipper = skipper()
        skipper.play(10_000L, 40_000L, 11_000L)

        skipper.onUserSeek(20_000L)

        assertEquals(listOf(seek(intro)), skipper.play(20_000L))
    }

    @Test
    fun `a user seek elsewhere keeps the guard of the skipped segment`() {
        // Auto-skip, then the user taps +30 s within the guard: a late report from the intro
        // must not undo the user's seek with a second skip.
        val skipper = skipper()
        val steps = skipper.play(10_000L, 40_000L)

        skipper.onUserSeek(70_000L)
        val after = skipper.play(11_000L, 71_000L)

        assertEquals(listOf(seek(intro)), (steps + after).seeks())
    }

    @Test
    fun `skipping the next segment keeps the guard of the one before`() {
        val first = Segment(RECAP, startTicks = 0L, endTicks = 10_000L)
        val skipper = skipper(actions = mapOf(RECAP to SKIP, INTRO to SKIP))
        skipper.setSegments(listOf(first, intro))

        val steps = skipper.play(0L, 10_000L, 40_000L, 5_000L, 41_000L)

        assertEquals(listOf(seek(first), seek(intro)), steps.seeks())
    }

    @Test
    fun `the user seeking back into a skipped segment skips it again, as the local player does`() {
        val skipper = skipper()
        skipper.play(10_000L, 40_000L, 41_000L)

        skipper.onUserSeek(20_000L)

        assertEquals(listOf(seek(intro)), skipper.play(20_000L))
    }

    @Test
    fun `the guard ends after SETTLE_MS`() {
        val skipper = skipper()
        clockMs = 0L
        assertEquals(seek(intro), skipper.update(item, 10_000L, duration))
        skipper.update(item, 40_000L, duration)

        clockMs = CastSegmentSkipper.SETTLE_MS - 1
        assertEquals(CastSegmentStep.None, skipper.update(item, 15_000L, duration))
        skipper.update(item, 41_000L, duration)
        clockMs = CastSegmentSkipper.SETTLE_MS
        assertEquals(seek(intro), skipper.update(item, 15_000L, duration))
    }

    @Test
    fun `seeking back inside a skipped segment does not skip it again`() {
        // The seek did not land (the receiver stays in the intro); the user seeks back in it.
        val skipper = skipper()
        skipper.play(10_000L, 11_000L, 30_000L)

        skipper.onUserSeek(15_000L)

        assertEquals(List(2) { CastSegmentStep.None }, skipper.play(15_000L, 16_000L))
    }

    @Test
    fun `an outro that closes the item is skipped to its end, not to the next item`() {
        assertEquals(listOf(seek(outro)), skipper().play(700_000L))
    }

    @Test
    fun `an outro that runs to or past the end lands just before the end`() {
        val longOutro = Segment(OUTRO, startTicks = 700_000L, endTicks = 730_000L)
        val steps = skipper(segments = listOf(longOutro)).play(700_000L)

        assertEquals(
            listOf(seek(longOutro, to = duration - CastSegmentSkipper.END_MARGIN_MS)),
            steps,
        )
    }

    @Test
    fun `a late report after a skip to the end does not hide the end`() {
        // The skip lands near the end; a report from before it arrives; then the moment at 0.
        val opening = Segment(INTRO, startTicks = 0L, endTicks = 30_000L)
        val longOutro = Segment(OUTRO, startTicks = 700_000L, endTicks = 730_000L)
        val skipper = skipper(segments = listOf(opening, longOutro))
        skipper.onUserSeek(699_000L)

        val steps = skipper.play(700_000L, 718_000L, 701_000L, 0L)

        assertEquals(listOf(seek(longOutro, to = 718_000L)), steps.seeks())
    }

    @Test
    fun `the moment at 0 after a skip to the end counts even without a report there`() {
        val opening = Segment(INTRO, startTicks = 0L, endTicks = 30_000L)
        val longOutro = Segment(OUTRO, startTicks = 700_000L, endTicks = 730_000L)
        val skipper = skipper(segments = listOf(opening, longOutro))
        skipper.onUserSeek(699_000L)

        val steps = skipper.play(700_000L, 0L)

        assertEquals(listOf(seek(longOutro, to = 718_000L)), steps.seeks())
    }

    @Test
    fun `with the duration unknown a skip goes to the segment's end`() {
        val longOutro = Segment(OUTRO, startTicks = 700_000L, endTicks = 730_000L)
        val steps = skipper(segments = listOf(longOutro)).play(700_000L, durationMs = 0L)

        assertEquals(listOf(seek(longOutro)), steps)
    }

    @Test
    fun `a position of another item, or of none, is no step`() {
        val skipper = skipper()

        assertEquals(CastSegmentStep.None, skipper.update(other, 10_000L, duration))
        assertEquals(CastSegmentStep.None, skipper.update(null, 11_000L, duration))
        assertEquals(seek(intro), skipper.update(item, 12_000L, duration))
    }

    @Test
    fun `a resumed item does not skip on the stale position before the resume lands`() {
        val opening = Segment(INTRO, startTicks = 0L, endTicks = 30_000L)
        val skipper = skipper(resumePositionMs = 600_000L, segments = listOf(opening))

        val steps = skipper.play(0L, 0L, 601_000L, 602_000L)

        assertEquals(List(4) { CastSegmentStep.None }, steps)
    }

    @Test
    fun `a user seek does not end the wait for a load's position`() {
        // A reload at 5:00; a stale 0 comes in, the user seeks, another stale 0 comes in.
        val opening = Segment(INTRO, startTicks = 0L, endTicks = 30_000L)
        val skipper = skipper(segments = listOf(opening))
        skipper.play(300_000L)
        skipper.expectLoad(300_000L)

        val before = skipper.play(0L)
        skipper.onUserSeek(310_000L)
        val after = skipper.play(0L, 310_000L)

        assertEquals(List(3) { CastSegmentStep.None }, before + after)
    }

    @Test
    fun `an item resumed inside a segment set to skip skips it once the resume lands`() {
        val steps = skipper(resumePositionMs = 20_000L).play(0L, 20_500L)

        assertEquals(listOf(CastSegmentStep.None, seek(intro)), steps)
    }

    @Test
    fun `a receiver that plays on from the start instead of the resume position counts`() {
        val opening = Segment(INTRO, startTicks = 0L, endTicks = 30_000L)
        val steps =
            skipper(resumePositionMs = 600_000L, segments = listOf(opening)).play(0L, 1_000L)

        assertEquals(listOf(CastSegmentStep.None, seek(opening)), steps)
    }

    @Test
    fun `the moment at 0 when an item runs out is not an entry`() {
        val opening = Segment(INTRO, startTicks = 0L, endTicks = 30_000L)
        val skipper = skipper(segments = listOf(opening))
        skipper.play(0L) // the skip at the start
        skipper.onUserSeek(714_000L)

        val steps = skipper.play(714_000L, 718_000L, 0L, 1_000L)

        assertEquals(List(4) { CastSegmentStep.None }, steps)
    }

    @Test
    fun `the moment at 0 when an item runs out still counts when duration temporarily reads 0`() {
        val opening = Segment(INTRO, startTicks = 0L, endTicks = 30_000L)
        val skipper = skipper(segments = listOf(opening))
        skipper.play(0L)
        clockMs += CastSegmentSkipper.SETTLE_MS
        skipper.onUserSeek(714_000L)
        skipper.update(item, 718_000L, duration)

        assertEquals(CastSegmentStep.None, skipper.update(item, 0L, 0L))
        assertEquals(CastSegmentStep.None, skipper.update(item, 1_000L, 0L))
    }

    @Test
    fun `a new item forgets the skip`() {
        val skipper = skipper()
        skipper.play(10_000L)

        val next = skipper()

        assertEquals(listOf(seek(intro)), next.play(10_000L))
    }
}
