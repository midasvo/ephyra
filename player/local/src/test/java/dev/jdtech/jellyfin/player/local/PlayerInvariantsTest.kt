package dev.jdtech.jellyfin.player.local

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Source-level invariants for the player that a compile cannot catch and a device test is easy to
 * skip. Each one names the regression it guards. Gradle runs unit tests from the module directory,
 * so the sources are read relative to it.
 */
class PlayerInvariantsTest {

    private fun source(path: String): String {
        val file = File("src/main/java/dev/jdtech/jellyfin/player/local/$path")
        assertTrue(
            file.isFile,
            message = "missing ${file.absolutePath}; run from the player/local module",
        )
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
            viewModel.contains("setVideoChangeFrameRateStrategy"),
            message = "PlayerViewModel must not call setVideoChangeFrameRateStrategy (#338)",
        )
        assertFalse(
            viewModel.contains("VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF"),
            message = "PlayerViewModel must not use VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF (#338)",
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
        assertTrue(
            setMediaItems != null,
            message = "MPVPlayer.setMediaItems(items, index, position) not found",
        )
        assertTrue(
            setMediaItems!!.value.contains("itemTransitions.playlistReplaced("),
            message = "MPVPlayer.setMediaItems must call itemTransitions.playlistReplaced (#453)",
        )
    }

    @Test
    fun `mpv hints the surface with the video frame rate on every device`() {
        // mpv has no frame rate hint of its own (nothing in its Android sources calls
        // Surface.setFrameRate), so MPVPlayer sends one: seamless-only by default, like ExoPlayer,
        // with fixed-source compatibility so the OS picks an exact match or a multiple (#338).
        val mpvPlayer = source("mpv/MPVPlayer.kt")

        assertTrue(
            mpvPlayer.contains("Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS"),
            message = "MPVPlayer must request seamless switches when the TV setting is off (#338)",
        )
        assertTrue(
            mpvPlayer.contains("Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE"),
            message =
                "MPVPlayer must pass FRAME_RATE_COMPATIBILITY_FIXED_SOURCE like ExoPlayer (#338)",
        )
        assertFalse(
            Regex("""if \(!matchRefreshRate\s*\|\|""").containsMatchIn(mpvPlayer),
            message = "MPVPlayer must not skip the hint when the TV setting is off (#338)",
        )
    }

    @Test
    fun `mpv asks whether the stream broke before it ends or advances`() {
        // #445: a lost connection reaches eof-reached like a real end, so the item was reported
        // finished at its full length and the next episode started. MpvStreamBreakTest covers the
        // decision; this checks the eof-reached handler still asks before ENDED or advancing.
        val mpvPlayer = source("mpv/MPVPlayer.kt")
        val handler = Regex(""""eof-reached" -> \{[\s\S]*?\n {16}\}""").find(mpvPlayer)
        assertTrue(handler != null, message = "MPVPlayer's eof-reached handler not found")
        val body = handler!!.value
        val check = body.indexOf("streamBreak.atEndOfFile(")
        assertTrue(check >= 0, message = "eof-reached must call streamBreak.atEndOfFile (#445)")
        listOf("STATE_ENDED", "prepareMediaItem(", "END_OF_MEDIA_ITEM").forEach {
            assertTrue(
                check < body.indexOf(it),
                message = "eof-reached must check the stream before $it (#445)",
            )
        }
        assertTrue(
            mpvPlayer.contains("streamBreak.onErrorLog("),
            message = "MPVPlayer must feed the log observer's errors to streamBreak (#445)",
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
        assertTrue(detach != null, message = "MPVPlayer.detachVideoSurface not found")
        val body = detach!!.value
        val deselect = body.indexOf("""selectTrack(MPVTrackType.VIDEO, "no")""")
        val voNull = body.indexOf("""setOptionString("vo", "null")""")
        assertTrue(deselect >= 0, message = "detachVideoSurface must deselect video (#460)")
        assertTrue(
            deselect < voNull,
            message = "detachVideoSurface must deselect video before vo=null (#460)",
        )
        val attach =
            Regex("""override fun surfaceCreated\(holder: SurfaceHolder\) \{[\s\S]*?\n {12}\}""")
                .find(mpvPlayer)
        assertTrue(attach != null, message = "MPVPlayer's surfaceCreated not found")
        assertTrue(
            attach!!.value.contains("videoTrackSelection(trackSelectionParameters)"),
            message = "surfaceCreated must select video again from the parameters (#460)",
        )
    }

    @Test
    fun `mpv is not called on the main thread after init`() {
        // #460: libmpv calls wait for mpv's core. With the core stuck on a wedged decoder or a
        // dead connection, onPause (setPlayWhenReady), surfaceChanged and leaving the player
        // blocked the main thread into ANRs. After init every call goes through MpvCallQueue.
        val mpvPlayer = source("mpv/MPVPlayer.kt")
        val firstInit = Regex("""\n    init \{[\s\S]*?\n    \}""").find(mpvPlayer)
        val callsClass =
            Regex("""private inner class MpvCalls \{[\s\S]*?\n    \}""").find(mpvPlayer)
        assertTrue(firstInit != null, message = "MPVPlayer's init block not found")
        assertTrue(callsClass != null, message = "MPVPlayer.MpvCalls not found")
        val rest = mpvPlayer.replace(firstInit!!.value, "").replace(callsClass!!.value, "")
        val direct =
            Regex("""mpvLib\.(\w+)""").findAll(rest).map { it.groupValues[1] }.toSet() -
                setOf("removeObserver", "removeLogObserver", "destroy")
        assertTrue(
            direct.isEmpty(),
            message = "MPVPlayer calls mpvLib directly after init: $direct (#460)",
        )
        assertTrue(
            rest.contains("calls.close { mpvLib.destroy() }"),
            message = "release() must destroy mpv on the mpv thread (#460)",
        )
        assertFalse(
            rest.replace("calls.close { mpvLib.destroy() }", "").contains("mpvLib.destroy()"),
            message = "release() must not destroy mpv on the main thread (#460)",
        )
    }

    @Test
    fun `mpv sets a network timeout`() {
        // #460: with mpv's default of 60 s a stalled read blocked recovery for over a minute
        // after the network came back. 10 s used up ffmpeg's reconnects during a longer stall.
        val mpvPlayer = source("mpv/MPVPlayer.kt")
        val timeout = Regex("""setOptionString\("network-timeout", "(\d+)"\)""").find(mpvPlayer)
        assertTrue(timeout != null, message = "MPVPlayer must set network-timeout (#460)")
        val seconds = timeout!!.groupValues[1].toInt()
        assertTrue(
            seconds in 20..45,
            message = "network-timeout must stay between 20 and 45 s (#460), is $seconds",
        )
    }

    @Test
    fun `mpv keeps the network on ffmpeg`() {
        // #654: mpv 0.42 reads http(s) through libcurl by default. That backend ignores
        // network-timeout and logs its errors under "curl", so the stall handling (#460) and the
        // stream-break and connection-error detection (#445, #576) would stop working.
        val mpvPlayer = source("mpv/MPVPlayer.kt")
        assertTrue(
            mpvPlayer.contains("""setOptionString("curl-enabled", "no")"""),
            message = "MPVPlayer must set curl-enabled=no until curl is handled (#654)",
        )
    }

    @Test
    fun `mpv applies AV1 film grain on the CPU`() {
        // #652, #655: libplacebo's grain shader flipped the picture on OpenGL ES before 7.387.0,
        // and on Mali it fails to compile in every version tested, which drops the grain silently
        // (measured on a Dimensity 1200: pixel noise 0.5 with the shader, 14.3 with dav1d).
        val mpvPlayer = source("mpv/MPVPlayer.kt")
        assertTrue(
            mpvPlayer.contains("""setOptionString("vd-lavc-film-grain", "cpu")"""),
            message = "MPVPlayer must set vd-lavc-film-grain=cpu (#652, #655)",
        )
    }

    @Test
    fun `mpv reports a failed load at END_FILE without requiring an error line`() {
        // #446: libmpv can deliver END_FILE before the error lines that explain it. Requiring an
        // error line first left the player black and BUFFERING without an error.
        val mpvPlayer = source("mpv/MPVPlayer.kt")
        val endFile =
            Regex("""MpvEvent\.MPV_EVENT_END_FILE -> \{[\s\S]*?\n {16}\}""").find(mpvPlayer)
        assertTrue(endFile != null, message = "MPVPlayer's END_FILE branch not found")
        assertTrue(
            endFile!!.value.contains("loadState.endFile {"),
            message = "END_FILE must ask MpvLoadState whether the load failed (#446)",
        )
        // #576: the report may wait a moment for the line, but never without a timer that
        // reports it anyway.
        assertTrue(
            endFile.value.contains(
                "handler.postDelayed(loadErrorHoldTimeout, MpvLoadState.HOLD_MS)"
            ) && mpvPlayer.contains("if (!released && loadState.holdTimedOut()) onLoadError("),
            message =
                "A failure held for its error line must be reported when the timer runs out (#576)",
        )
        assertFalse(
            endFile.value.contains("error != null"),
            message = "END_FILE must not require an error line before reporting (#446)",
        )
    }

    @Test
    fun `mpv's END_FILE reason reaches the load state before END_FILE`() {
        // #786: libmpv calls eventEndFile just before event(END_FILE). Only through the same
        // post() as event() does the reason arrive before the END_FILE branch reads it.
        val mpvPlayer = source("mpv/MPVPlayer.kt")
        val endFileReason =
            Regex("""override fun eventEndFile\(reason: Int, error: Int\) \{[\s\S]*?\n {4}\}""")
                .find(mpvPlayer)
        assertTrue(endFileReason != null, message = "MPVPlayer must override eventEndFile (#786)")
        assertTrue(
            endFileReason!!.value.contains("post {") &&
                endFileReason.value.contains("loadState.endFileReason(reason, error)"),
            message = "eventEndFile must post the reason to MpvLoadState like event() (#786)",
        )
        // And event() must still handle END_FILE inside that same post(), after the reason.
        val event =
            Regex("""override fun event\(eventId: Int\) \{\s*post \{[\s\S]*?\n {4}\}""")
                .find(mpvPlayer)
        assertTrue(
            event != null && event.value.contains("MpvEvent.MPV_EVENT_END_FILE -> {"),
            message = "event() must handle END_FILE inside post(), as eventEndFile does (#786)",
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
        assertTrue(transition != null, message = "PlayerViewModel.onMediaItemTransition not found")
        assertTrue(
            Regex("""startGate\s*\.onTransition\(""").containsMatchIn(transition!!.value) &&
                !Regex("""\n\s*sendPlaybackStart\(item\)""").containsMatchIn(transition.value),
            message = "onMediaItemTransition must hand the start to startGate (#601)",
        )
        val ready = Regex("""ExoPlayer\.STATE_READY -> \{[\s\S]*?\n {12}}""").find(viewModel)
        assertTrue(ready != null, message = "PlayerViewModel's STATE_READY branch not found")
        assertTrue(
            ready!!.value.contains("startGate.onReady()"),
            message = "STATE_READY must send the start the gate holds (#601)",
        )
        // #674: updatePlaybackProgress sends its report through reportProgress.
        val progress =
            Regex("""private fun reportProgress\([^)]*\) \{[\s\S]*?\n    }""").find(viewModel)
        assertTrue(progress != null, message = "PlayerViewModel.reportProgress not found")
        assertTrue(
            progress!!.value.contains("if (playing?.startJob == null) return@launch"),
            message = "No progress report before the start report (#601)",
        )
        // #674: a report that waited for the lock must not follow its item's stop.
        val locked = progress.value.substringAfter("progressReportLock.withLock {", "")
        assertTrue(
            locked.contains("playing?.startJob !== startJob"),
            message = "reportProgress must check the item again once it holds the lock (#674)",
        )
    }

    @Test
    fun `a pause or a resume is reported at once`() {
        // #674: pause and resume reached the server only at the next 5 s tick, and a pause in the
        // background (the background loop stops when playback does) never did.
        val viewModel = source("presentation/PlayerViewModel.kt")
        val callback =
            Regex("""override fun onPlayWhenReadyChanged\([\s\S]*?\n    }""").find(viewModel)
        assertTrue(callback != null, message = "PlayerViewModel.onPlayWhenReadyChanged not found")
        // In the branch that is not the end of an item, not only in the sleep timer's path there.
        assertTrue(
            Regex("""\n {8}} else \{[\s\S]*?\n {12}updatePlaybackProgress\(\)\n {8}}""")
                .containsMatchIn(callback!!.value),
            message =
                "onPlayWhenReadyChanged must call updatePlaybackProgress() when not at the end (#674)",
        )
        // A resume that buffered first was reported as paused: correct it once it plays.
        val isPlaying =
            Regex("""override fun onIsPlayingChanged\([\s\S]*?\n    }""").find(viewModel)
        assertTrue(isPlaying != null, message = "PlayerViewModel.onIsPlayingChanged not found")
        assertTrue(
            isPlaying!!.value.contains("if (isPlaying) reportPlayingIfReportedPaused()"),
            message =
                "onIsPlayingChanged must report a start of playback the server has as paused (#674)",
        )
    }

    @Test
    fun `the TV player reports progress`() {
        // #458: the TV player only reported start and stop, never progress, so a crash or a
        // force-stop resumed from the previous stop. app/tv's own tests are not part of the root
        // testDebugUnitTest task, so this checks its source from here.
        val file =
            File(
                "../../app/tv/src/main/java/dev/jdtech/jellyfin/ce/presentation/player/TvPlayerScreen.kt"
            )
        assertTrue(file.isFile, message = "missing ${file.absolutePath}")
        val screen = file.readText()
        assertTrue(
            Regex("""TvProgressReports\(\s*send = viewModel::updatePlaybackProgress""")
                .containsMatchIn(screen),
            message = "TvPlayerScreen must send progress through TvProgressReports (#458)",
        )
        assertTrue(
            screen.contains("repeatOnLifecycle(Lifecycle.State.STARTED)"),
            message = "TvPlayerScreen must report only while started (#458)",
        )
    }

    @Test
    fun `the TV player takes the key-up of an OK that showed the controls`() {
        // #459: the OK-up reached the Play/Pause button, focused by then, and toggled playback.
        // app/tv's own tests (TvPlayerKeyHandlerTest) are not part of the root task.
        val file =
            File(
                "../../app/tv/src/main/java/dev/jdtech/jellyfin/ce/presentation/player/TvPlayerKeyHandler.kt"
            )
        assertTrue(file.isFile, message = "missing ${file.absolutePath}")
        val handler = file.readText()
        val onKeyUp =
            Regex("""private fun onKeyUp\(keyCode: Int\): Boolean \{[\s\S]*?\n    \}""")
                .find(handler)
        assertTrue(onKeyUp != null, message = "TvPlayerKeyHandler.onKeyUp not found")
        assertTrue(
            onKeyUp!!.value.contains("okDown == keyCode"),
            message = "onKeyUp must take the up of the OK whose down showed the controls (#459)",
        )
    }

    @Test
    fun `the TV player plays again after Home if it was playing`() {
        // #462: ON_PAUSE paused the player and ON_RESUME only resumed the view, so after Home and
        // back the player sat paused with nothing on screen. app/tv's own tests
        // (TvResumeOnReturnTest) are not part of the root task.
        val file =
            File(
                "../../app/tv/src/main/java/dev/jdtech/jellyfin/ce/presentation/player/TvPlayerScreen.kt"
            )
        assertTrue(file.isFile, message = "missing ${file.absolutePath}")
        val screen = file.readText()
        assertTrue(
            screen.contains("resumeOnReturn.onPause(player.playWhenReady)"),
            message = "TvPlayerScreen must decide the return on ON_PAUSE (#462)",
        )
        assertTrue(
            screen.contains("TvResumeOnReturn.Action.PLAY -> player.play()"),
            message = "TvPlayerScreen must play again on ON_RESUME when it was playing (#462)",
        )
    }

    @Test
    fun `mpv adds external subtitles without blocking the main thread`() {
        // #617: START_FILE runs on the main thread, and a plain sub-add waits there until the
        // subtitle is downloaded. With the async prefix mpv downloads it on its own thread, cancels
        // it when the file ends and waits for it in destroy(), so no thread of ours can race
        // destroy(). The list stays after running, so a reload after an error (#474) adds them.
        val mpvPlayer = source("mpv/MPVPlayer.kt")
        assertTrue(
            Regex(""""async",\s*(/\* command= \*/\s*)?"sub-add"""").containsMatchIn(mpvPlayer),
            message = "the sub-add commands must carry mpv's async prefix (#617)",
        )
        assertFalse(
            mpvPlayer.contains("Executors.") || mpvPlayer.contains("Thread("),
            message = "MPVPlayer must not call mpv from a thread of its own (#617)",
        )
        val startFile =
            Regex("""MpvEvent\.MPV_EVENT_START_FILE -> \{[\s\S]*?\n {16}\}""").find(mpvPlayer)
        assertTrue(startFile != null, message = "MPVPlayer's START_FILE branch not found")
        assertFalse(
            startFile!!.value.contains("initialCommands.clear()"),
            message =
                "START_FILE must not clear initialCommands, or a reload after an error loses them (#474)",
        )
    }

    @Test
    fun `an item change sets up its neighbours before it waits for the previous stop`() {
        // #657: onMediaItemTransition waited for the previous item's stop report before it set up
        // the neighbour loading. With a slow server and Next pressed quickly, the player had left
        // the item by then, so its neighbours were never loaded, and the late coroutine set the
        // queue's current item back to the item the player had left. Only the start report has to
        // wait for the stop; NeighbourLoaderTest covers where the neighbours go.
        val viewModel = source("presentation/PlayerViewModel.kt")
        val transition =
            Regex("""override fun onMediaItemTransition\([\s\S]*?\n    }""").find(viewModel)
        assertTrue(transition != null, message = "PlayerViewModel.onMediaItemTransition not found")
        val body = transition!!.value
        val stopWait = body.indexOf("stopJob?.join()")
        assertTrue(
            stopWait >= 0,
            message = "onMediaItemTransition no longer waits for the stop report",
        )
        for (setup in
            listOf(
                "playlistManager.setCurrentMediaItemIndex(item.itemId)",
                "deferredWork = item",
                "runDeferredWork()",
            )) {
            val at = body.indexOf(setup)
            assertTrue(at >= 0, message = "onMediaItemTransition must call $setup (#657)")
            assertTrue(at < stopWait, message = "$setup must come before stopJob?.join() (#657)")
        }
    }
}
