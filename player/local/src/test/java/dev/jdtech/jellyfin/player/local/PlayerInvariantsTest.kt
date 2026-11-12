package dev.jdtech.jellyfin.player.local

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level invariants for the player that a compile cannot catch and a device test is easy to
 * skip. Each one names the regression it guards. Gradle runs unit tests from the module directory,
 * so the sources are read relative to it.
 */
class PlayerInvariantsTest {

    private fun source(path: String): String {
        val file = File("src/main/java/dev/jdtech/jellyfin/player/local/$path")
        assertTrue("missing ${file.absolutePath}; run from the player/local module", file.isFile)
        return file.readText()
    }

    @Test
    fun `ExoPlayer keeps its default frame rate strategy`() {
        // #338: forcing VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF on phones (the TV-only "match
        // refresh rate" setting defaults to off) removed the surface frame rate hint, so 120Hz
        // panels stayed at 120Hz for the whole playback. ExoPlayer's default, ONLY_IF_SEAMLESS,
        // is what upstream Findroid runs with and what lets the OS drop to 48Hz for 24fps.
        val viewModel = source("presentation/PlayerViewModel.kt")

        assertFalse(
            "PlayerViewModel must not call setVideoChangeFrameRateStrategy (#338)",
            viewModel.contains("setVideoChangeFrameRateStrategy"),
        )
        assertFalse(
            "PlayerViewModel must not use VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF (#338)",
            viewModel.contains("VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF"),
        )
    }

    @Test
    fun `mpv reports a replaced playlist from setMediaItems`() {
        // #453: setMediaItems swapped the playlist without a timeline change or transition, so
        // the media session crashed on the state change prepare() sends, and a jump from the first
        // item kept the old item's title and server reports. MpvItemTransitionsTest covers the
        // events; this checks MPVPlayer still sends them.
        val mpvPlayer = source("mpv/MPVPlayer.kt")
        val setMediaItems =
            Regex(
                    """override fun setMediaItems\(\s*mediaItems: MutableList<MediaItem>,\s*""" +
                        """startWindowIndex: Int,[\s\S]*?\n    }"""
                )
                .find(mpvPlayer)
        assertTrue("MPVPlayer.setMediaItems(items, index, position) not found", setMediaItems != null)
        assertTrue(
            "MPVPlayer.setMediaItems must call itemTransitions.playlistReplaced (#453)",
            setMediaItems!!.value.contains("itemTransitions.playlistReplaced("),
        )
    }

    @Test
    fun `mpv hints the surface with the video frame rate on every device`() {
        // mpv has no frame rate hint of its own (nothing in its Android sources calls
        // Surface.setFrameRate), so MPVPlayer sends one: seamless-only by default, like ExoPlayer,
        // with fixed-source compatibility so the OS picks an exact match or a multiple (#338).
        val mpvPlayer = source("mpv/MPVPlayer.kt")

        assertTrue(
            "MPVPlayer must request seamless switches when the TV setting is off (#338)",
            mpvPlayer.contains("Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS"),
        )
        assertTrue(
            "MPVPlayer must pass FRAME_RATE_COMPATIBILITY_FIXED_SOURCE like ExoPlayer (#338)",
            mpvPlayer.contains("Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE"),
        )
        assertFalse(
            "MPVPlayer must not skip the hint when the TV setting is off (#338)",
            Regex("""if \(!matchRefreshRate\s*\|\|""").containsMatchIn(mpvPlayer),
        )
    }

    @Test
    fun `mpv asks whether the stream broke before it ends or advances`() {
        // #445: a lost connection reaches eof-reached like a real end, so the item was reported
        // finished at its full length and the next episode started. MpvStreamBreakTest covers the
        // decision; this checks the eof-reached handler still asks before ENDED or advancing.
        val mpvPlayer = source("mpv/MPVPlayer.kt")
        val handler = Regex(""""eof-reached" -> \{[\s\S]*?\n {16}\}""").find(mpvPlayer)
        assertTrue("MPVPlayer's eof-reached handler not found", handler != null)
        val body = handler!!.value
        val check = body.indexOf("streamBreak.atEndOfFile(")
        assertTrue("eof-reached must call streamBreak.atEndOfFile (#445)", check >= 0)
        listOf("STATE_ENDED", "prepareMediaItem(", "END_OF_MEDIA_ITEM").forEach {
            assertTrue("eof-reached must check the stream before $it (#445)", check < body.indexOf(it))
        }
        assertTrue(
            "MPVPlayer must feed the log observer's errors to streamBreak (#445)",
            mpvPlayer.contains("streamBreak.onErrorLog("),
        )
    }

    @Test
    fun `mpv sets a network timeout`() {
        // #460: with mpv's default of 60 s a stalled read blocked recovery for over a minute
        // after the network came back. 10 s used up ffmpeg's reconnects during a longer stall.
        val mpvPlayer = source("mpv/MPVPlayer.kt")
        val timeout =
            Regex("""setOptionString\("network-timeout", "(\d+)"\)""").find(mpvPlayer)
        assertTrue("MPVPlayer must set network-timeout (#460)", timeout != null)
        val seconds = timeout!!.groupValues[1].toInt()
        assertTrue("network-timeout must stay between 20 and 45 s (#460), is $seconds", seconds in 20..45)
    }

    @Test
    fun `mpv reports a failed load at END_FILE without requiring an error line`() {
        // #446: libmpv can deliver END_FILE before the error lines that explain it. Requiring an
        // error line first left the player black and BUFFERING without an error.
        val mpvPlayer = source("mpv/MPVPlayer.kt")
        val endFile =
            Regex("""MpvEvent\.MPV_EVENT_END_FILE -> \{[\s\S]*?\n {16}\}""").find(mpvPlayer)
        assertTrue("MPVPlayer's END_FILE branch not found", endFile != null)
        assertTrue(
            "END_FILE must ask MpvLoadState whether the load failed (#446)",
            endFile!!.value.contains("loadState.endFile {"),
        )
        // #576: the report may wait a moment for the line, but never without a timer that
        // reports it anyway.
        assertTrue(
            "A failure held for its error line must be reported when the timer runs out (#576)",
            endFile.value.contains(
                "handler.postDelayed(loadErrorHoldTimeout, MpvLoadState.HOLD_MS)"
            ) && mpvPlayer.contains("if (!released && loadState.holdTimedOut()) onLoadError("),
        )
        assertFalse(
            "END_FILE must not require an error line before reporting (#446)",
            endFile.value.contains("error != null"),
        )
    }

    @Test
    fun `the TV player reports progress`() {
        // #458: the TV player only reported start and stop, never progress, so a crash or a
        // force-stop resumed from the previous stop. app/tv's own tests are not part of the root
        // testDebugUnitTest task, so this checks its source from here.
        val file = File("../../app/tv/src/main/java/dev/jdtech/jellyfin/ce/presentation/player/TvPlayerScreen.kt")
        assertTrue("missing ${file.absolutePath}", file.isFile)
        val screen = file.readText()
        assertTrue(
            "TvPlayerScreen must send progress through TvProgressReports (#458)",
            screen.contains("TvProgressReports(send = viewModel::updatePlaybackProgress"),
        )
        assertTrue(
            "TvPlayerScreen must report only while started (#458)",
            screen.contains("repeatOnLifecycle(Lifecycle.State.STARTED)"),
        )
    }

    @Test
    fun `the TV player takes the key-up of an OK that showed the controls`() {
        // #459: the OK-up reached the Play/Pause button, focused by then, and toggled playback.
        // app/tv's own tests (TvPlayerKeyHandlerTest) are not part of the root task.
        val file = File("../../app/tv/src/main/java/dev/jdtech/jellyfin/ce/presentation/player/TvPlayerKeyHandler.kt")
        assertTrue("missing ${file.absolutePath}", file.isFile)
        val handler = file.readText()
        val onKeyUp = Regex("""private fun onKeyUp\(keyCode: Int\): Boolean \{[\s\S]*?\n    \}""").find(handler)
        assertTrue("TvPlayerKeyHandler.onKeyUp not found", onKeyUp != null)
        assertTrue(
            "onKeyUp must take the up of the OK whose down showed the controls (#459)",
            onKeyUp!!.value.contains("okDown == keyCode"),
        )
    }
}
