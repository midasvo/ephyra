package dev.jdtech.jellyfin.player.local.mpv

import dev.jdtech.jellyfin.player.local.mpv.MpvLoadState.End
import dev.jdtech.jellyfin.player.local.mpv.MpvLoadState.Logged
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #446: a stream that failed to open left the mpv player black and BUFFERING without an error
 * when mpv delivered END_FILE before its error lines. Both orders were seen on the emulators
 * with the same refused connection (about one in five opens had END_FILE first).
 *
 * #576: one failed open was reported two to four times, because mpv goes on to the next playlist
 * entries before it is stopped.
 */
class MpvLoadStateTest {

    private val state = MpvLoadState().apply { startFile() }

    /** mpv is idle: it is on no playlist entry. */
    private val idle: () -> Int? = { -1 }

    private fun on(index: Int): () -> Int? = { index }

    @Test
    fun `END_FILE before the error line holds the failure for the line`() {
        assertEquals(End.HOLD, state.endFile(idle))

        assertEquals(
            Logged.COMPLETES_FAILURE,
            state.errorLogged("ffmpeg: tcp: Connection to tcp://demo.jellyfin.org:443 failed"),
        )
        assertEquals(
            "ffmpeg: tcp: Connection to tcp://demo.jellyfin.org:443 failed",
            state.failure.message,
        )
        // The lines after it are only logged, and the timer has nothing left to report.
        assertEquals(
            Logged.AFTER_FAILURE,
            state.errorLogged("stream: Failed to open https://demo.jellyfin.org/..."),
        )
        assertFalse(state.holdTimedOut())
        assertEquals(End.NOT_A_FAILURE, state.endFile(idle))
    }

    @Test
    fun `a held failure without any error line is reported when the timer runs out`() {
        assertEquals(End.HOLD, state.endFile(idle))

        assertTrue(state.holdTimedOut())
        assertNull(state.failure.message)
        assertEquals(emptyList<String>(), state.failure.errors)
        // Once: a second timer, or a line that comes late after all, reports nothing.
        assertFalse(state.holdTimedOut())
        assertEquals(Logged.AFTER_FAILURE, state.errorLogged("stream: Failed to open ..."))
    }

    @Test
    fun `a new load during the hold drops the held failure`() {
        assertEquals(End.HOLD, state.endFile(idle))

        // Next, a new playlist, Play, or leaving the player (stop).
        state.newLoad(index = 1)

        assertFalse(state.holdTimedOut())
        // The old file's line belongs to nothing any more.
        assertEquals(Logged.KEPT, state.errorLogged("stream: Failed to open ..."))
        state.startFile()
        state.fileLoaded()
        assertEquals(End.NOT_A_FAILURE, state.endFile(idle))
    }

    @Test
    fun `the error lines before END_FILE are the failure`() {
        assertEquals(
            Logged.KEPT,
            state.errorLogged("ffmpeg: tcp: Connection to tcp://10.0.2.2:8096 failed: refused"),
        )
        assertEquals(Logged.KEPT, state.errorLogged("stream: Failed to open http://10.0.2.2/..."))

        assertEquals(End.FAILED, state.endFile(idle))
        // The last line is the message; the earlier ones say why.
        assertEquals("stream: Failed to open http://10.0.2.2/...", state.failure.message)
        assertEquals(
            listOf(
                "ffmpeg: tcp: Connection to tcp://10.0.2.2:8096 failed: refused",
                "stream: Failed to open http://10.0.2.2/...",
            ),
            state.failure.errors,
        )
    }

    @Test
    fun `a file that loaded and then ended is no load failure`() {
        state.fileLoaded()
        state.errorLogged("ffmpeg: tcp: ffurl_read returned 0xffffff92")

        assertEquals(End.NOT_A_FAILURE, state.endFile(idle))
    }

    @Test
    fun `an END_FILE this player caused is no failure`() {
        // The #445 stop.
        state.expectEndFile()
        state.errorLogged("stream: Failed to open https://demo.jellyfin.org/...")

        assertEquals(End.NOT_A_FAILURE, state.endFile(idle))
    }

    @Test
    fun `the next file starts clean`() {
        state.fileLoaded()
        state.errorLogged("lavf: ...treating it as fatal error.")
        state.endFile(idle)
        state.startFile()

        // The line of the file before is gone: this one fails without a line of its own.
        assertEquals(End.HOLD, state.endFile(idle))
    }

    @Test
    fun `the entries mpv tries after a failed open are not reported (#576)`() {
        // Seen with the server gone and two more episodes queued: mpv had failed on all three
        // before the first END_FILE was handled, and each was reported.
        state.errorLogged("stream: Failed to open episode 2")
        assertEquals(End.FAILED, state.endFile(on(2)))

        state.startFile()
        assertEquals(End.NOT_A_FAILURE, state.endFile(on(3)))
        state.startFile()
        assertEquals(End.NOT_A_FAILURE, state.endFile(idle))
        // Their error lines came after, and do not replace the first failure's.
        assertEquals(Logged.AFTER_FAILURE, state.errorLogged("stream: Failed to open episode 3"))
        assertEquals("stream: Failed to open episode 2", state.failure.message)
    }

    @Test
    fun `a following entry that does open does not undo the failure`() {
        state.errorLogged("lavf: avformat_open_input() failed")
        assertEquals(End.FAILED, state.endFile(on(1)))

        // mpv opened the next entry before the stop got there; the stop ends it.
        state.startFile()
        state.fileLoaded()
        state.expectEndFile()
        assertEquals(End.NOT_A_FAILURE, state.endFile(idle))
        assertEquals("lavf: avformat_open_input() failed", state.failure.message)
    }

    @Test
    fun `a failure held for its line is not reported again for the next entries`() {
        assertEquals(End.HOLD, state.endFile(on(1)))
        state.startFile()
        assertEquals(End.NOT_A_FAILURE, state.endFile(idle))

        assertEquals(Logged.COMPLETES_FAILURE, state.errorLogged("ffmpeg: tcp: refused"))
        assertEquals(listOf("ffmpeg: tcp: refused"), state.failure.errors)
    }

    @Test
    fun `Play after a failure reports the next failure`() {
        state.errorLogged("stream: Failed to open episode 2")
        assertEquals(End.FAILED, state.endFile(idle))

        state.newLoad(index = 1)
        state.startFile()
        state.errorLogged("stream: Failed to open episode 2, again")

        assertEquals(End.FAILED, state.endFile(idle))
        assertEquals(listOf("stream: Failed to open episode 2, again"), state.failure.errors)
    }

    @Test
    fun `ends that arrive after the retry while mpv is on the wanted entry are older files`() {
        // The retry after a failure can come before mpv's events for the entries it tried in
        // between: their START_FILE clears what the retry set, and their END_FILE is no failure
        // of the entry mpv is opening now.
        state.errorLogged("stream: Failed to open episode 2")
        assertEquals(End.FAILED, state.endFile(idle))

        state.newLoad(index = 1)
        state.startFile()
        assertEquals(End.NOT_A_FAILURE, state.endFile(on(1)))

        // The retry itself: it fails, and mpv has left the entry by the time its end is handled.
        state.startFile()
        assertEquals(End.HOLD, state.endFile(on(2)))
    }

    @Test
    fun `the first entry of a new playlist aborted for another entry is no failure`() {
        // prepare() loads the playlist, which makes mpv open entry 0, and then plays entry 1: the
        // START_FILE of entry 0 is handled after that and clears what prepare() set.
        state.newLoad(index = null)
        state.newLoad(index = 1)

        state.startFile()
        assertEquals(End.NOT_A_FAILURE, state.endFile(on(1)))

        state.startFile()
        state.fileLoaded()
        assertEquals(End.NOT_A_FAILURE, state.endFile(idle))
    }

    @Test
    fun `without a wanted entry an end before FILE_LOADED is a failure wherever mpv is`() {
        state.newLoad(index = null)
        state.startFile()

        assertEquals(End.HOLD, state.endFile(on(0)))
    }

    @Test
    fun `mpv is not asked where it is when the end is no failure anyway`() {
        state.fileLoaded()

        assertEquals(End.NOT_A_FAILURE, state.endFile { error("not needed") })
    }
}
