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
    fun `the start report waits for the item to load`() {
        // #601: the start went out at the item transition and progress reports followed, so an
        // item that never opened was counted as played, and one without a runtime marked played.
        // PlaybackStartGateTest covers the decision; this checks PlayerViewModel still uses it.
        val viewModel = source("presentation/PlayerViewModel.kt")
        val transition =
            Regex("""override fun onMediaItemTransition\([\s\S]*?\n    }""").find(viewModel)
        assertTrue("PlayerViewModel.onMediaItemTransition not found", transition != null)
        assertTrue(
            "onMediaItemTransition must hand the start to startGate (#601)",
            Regex("""startGate\s*\.onTransition\(""").containsMatchIn(transition!!.value) &&
                !Regex("""\n\s*sendPlaybackStart\(item\)""").containsMatchIn(transition.value),
        )
        val ready = Regex("""ExoPlayer\.STATE_READY -> \{[\s\S]*?\n {12}}""").find(viewModel)
        assertTrue("PlayerViewModel's STATE_READY branch not found", ready != null)
        assertTrue(
            "STATE_READY must send the start the gate holds (#601)",
            ready!!.value.contains("startGate.onReady()"),
        )
        val progress =
            Regex("""fun updatePlaybackProgress\(\) \{[\s\S]*?\n    }""").find(viewModel)
        assertTrue("PlayerViewModel.updatePlaybackProgress not found", progress != null)
        assertTrue(
            "No progress report before the start report (#601)",
            progress!!.value.contains("if (playing?.startJob == null) return@launch"),
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

    @Test
    fun `the TV player plays again after Home if it was playing`() {
        // #462: ON_PAUSE paused the player and ON_RESUME only resumed the view, so after Home and
        // back the player sat paused with nothing on screen. app/tv's own tests
        // (TvResumeOnReturnTest) are not part of the root task.
        val file = File("../../app/tv/src/main/java/dev/jdtech/jellyfin/ce/presentation/player/TvPlayerScreen.kt")
        assertTrue("missing ${file.absolutePath}", file.isFile)
        val screen = file.readText()
        assertTrue(
            "TvPlayerScreen must decide the return on ON_PAUSE (#462)",
            screen.contains("resumeOnReturn.onPause(player.playWhenReady)"),
        )
        assertTrue(
            "TvPlayerScreen must play again on ON_RESUME when it was playing (#462)",
            screen.contains("TvResumeOnReturn.Action.PLAY -> player.play()"),
        )
    }

    @Test
    fun `mpv loads external subtitles off the main thread`() {
        // #617: sub-add blocks while downloading an external subtitle. MPVPlayer must run them
        // off the main thread, keep the list so a reload after an error still adds them, and not
        // undo the track selection when a subtitle arrives late.
        val mpvPlayer = source("mpv/MPVPlayer.kt")
        val startFile =
            Regex("""MpvEvent\.MPV_EVENT_START_FILE -> \{[\s\S]*?\n {16}\}""").find(mpvPlayer)
        assertTrue("MPVPlayer's START_FILE branch not found", startFile != null)
        assertTrue(
            "START_FILE must submit subtitle loading to subtitleExecutor (#617)",
            startFile!!.value.contains("subtitleExecutor.submit") &&
                startFile.value.contains("loadSubtitles("),
        )
        assertFalse(
            "START_FILE must not run sub-add directly on the main thread (#617)",
            startFile.value.contains("mpvLib.command("),
        )

        // The subtitle list must only be cleared when resetting internal state, not on START_FILE
        // or during load, so error recovery (reloadAfterError) still has and loads them.
        val clearMatches = Regex("""\binitialCommands\.clear\(\)""").findAll(mpvPlayer).toList()
        org.junit.Assert.assertEquals(
            "initialCommands.clear() must only be called in resetInternalState (#617)",
            1,
            clearMatches.size,
        )
        val resetInternalState =
            Regex("""private fun resetInternalState\(\)\s*\{[\s\S]*?\n    \}""").find(mpvPlayer)
        assertTrue("resetInternalState not found", resetInternalState != null)
        assertTrue(
            "resetInternalState must clear initialCommands",
            resetInternalState!!.value.contains("initialCommands.clear()"),
        )

        val loadSubtitles =
            Regex("""private fun loadSubtitles\([\s\S]*?\n    \}""").find(mpvPlayer)
        assertTrue("MPVPlayer.loadSubtitles not found (#617)", loadSubtitles != null)
        assertTrue(
            "loadSubtitles must execute the subtitle commands via mpvLib.command (#617)",
            loadSubtitles!!.value.contains("mpvLib.command(command)"),
        )
        assertTrue(
            "loadSubtitles must evaluate targetSubtitleTrack after command completes (#617)",
            loadSubtitles.value.contains("targetSubtitleTrack(trackSelectionParameters)"),
        )
        assertTrue(
            "loadSubtitles must restore the target track using selectTrack (#617)",
            loadSubtitles.value.contains("selectTrack(MPVTrackType.SUBTITLE, targetSid)"),
        )
    }
}
