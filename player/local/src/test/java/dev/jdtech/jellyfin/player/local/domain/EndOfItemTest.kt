package dev.jdtech.jellyfin.player.local.domain

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #477: ExoPlayer reported the end of an item a second time, while already paused at it, when the
 * video track was enabled again on the return from the background. The player took it as a new end
 * and prompted "Are you still watching?" again.
 *
 * [Ends] follows the reports the way PlayerViewModel.onPlayWhenReadyChanged does: it keeps the last
 * playWhenReady, starting from true, and counts the ends [isNewEndOfItem] accepts.
 */
class EndOfItemTest {
    private class Ends {
        private var lastPlayWhenReady = true
        var count = 0
            private set

        fun report(playWhenReady: Boolean, reason: Int, state: Int = Player.STATE_READY) {
            if (isNewEndOfItem(lastPlayWhenReady, playWhenReady, reason, state)) count++
            lastPlayWhenReady = playWhenReady
        }

        fun play() = report(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)

        fun pause() = report(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)

        fun end() = report(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
    }

    @Test
    fun `a second end report while paused at the end is not a new end`() {
        val ends = Ends()
        ends.end()
        // The still-watching prompt pauses the player, then the return re-enables the video track.
        ends.pause()
        ends.end()
        assertEquals(1, ends.count)
    }

    @Test
    fun `the first end of a session counts`() {
        val ends = Ends()
        ends.end()
        assertEquals(1, ends.count)
    }

    @Test
    fun `the next item's end counts again`() {
        val ends = Ends()
        ends.play()
        ends.end()
        // Auto-advance or Continue on the prompt: the next item plays.
        ends.play()
        ends.end()
        assertEquals(2, ends.count)
    }

    @Test
    fun `a seek back from the end and a replay count the end again`() {
        val ends = Ends()
        ends.end()
        ends.pause()
        ends.play()
        ends.end()
        assertEquals(2, ends.count)
    }

    @Test
    fun `an end reported while not ready is not an end`() {
        val ends = Ends()
        ends.report(
            false,
            Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM,
            Player.STATE_ENDED,
        )
        assertEquals(0, ends.count)
    }

    @Test
    fun `a pause by the user is not an end`() {
        val ends = Ends()
        ends.play()
        ends.pause()
        assertEquals(0, ends.count)
    }
}
