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
    fun `mpv deselects video before it drops the video output`() {
        // #460: vo=null with video still selected made mpv re-create the hardware decoder for a
        // surface that was gone; on the emulator that codec never answered and the main thread
        // hung behind it (ANR on leaving the player). surfaceCreated must select video again.
        val mpvPlayer = source("mpv/MPVPlayer.kt")
        val detach =
            Regex("""private fun detachVideoSurface\(\) \{[\s\S]*?\n    \}""").find(mpvPlayer)
        assertTrue("MPVPlayer.detachVideoSurface not found", detach != null)
        val body = detach!!.value
        val deselect = body.indexOf("""selectTrack(MPVTrackType.VIDEO, "no")""")
        val voNull = body.indexOf("""setOptionString("vo", "null")""")
        assertTrue("detachVideoSurface must deselect video (#460)", deselect >= 0)
        assertTrue("detachVideoSurface must deselect video before vo=null (#460)", deselect < voNull)
        val attach =
            Regex("""override fun surfaceCreated\(holder: SurfaceHolder\) \{[\s\S]*?\n {12}\}""")
                .find(mpvPlayer)
        assertTrue("MPVPlayer's surfaceCreated not found", attach != null)
        assertTrue(
            "surfaceCreated must select video again from the parameters (#460)",
            attach!!.value.contains("videoTrackSelection(trackSelectionParameters)"),
        )
    }
}
