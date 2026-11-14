package dev.jdtech.jellyfin.player.local.mpv

import dev.jdtech.jellyfin.player.local.mpv.MpvStreamBreak.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #445: on mpv a lost connection reached `eof-reached` like a real end, so the item was reported
 * finished (often at its full length) and the next episode started. The sequences below are the
 * ones mpv logged on the emulators (Night of the Living Dead, 5717211 ms, on the demo server). When
 * in doubt the answer must be [Outcome.Finished], the behaviour before the fix.
 */
class MpvStreamBreakTest {

    private val duration = 5_717_211L
    private val streamBreak = MpvStreamBreak()

    /** The wall clock, in milliseconds. Playback below takes as long as the content. */
    private var now = 1_000_000L

    /** Plays from 0 to [toMs] with the demuxer [aheadMs] ahead, as mpv reports it. */
    private fun play(toMs: Long, aheadMs: Long = 400_000L, fromMs: Long = 0L) {
        var t = fromMs
        while (t <= toMs) {
            streamBreak.onBufferedEnd(minOf(t + aheadMs, duration), now)
            streamBreak.onPosition(t, duration)
            t += 10_000L
            now += 10_000L
        }
    }

    private fun networkErrors() {
        streamBreak.onErrorLog(
            "ffmpeg",
            "tcp: Failed to resolve hostname demo.jellyfin.org: No address associated with hostname",
            now,
        )
    }

    private fun assertBroken(expectedPositionMs: Long, outcome: Outcome) {
        assertTrue("expected a break, got $outcome", outcome is Outcome.Broken)
        assertEquals(expectedPositionMs, (outcome as Outcome.Broken).positionMs)
    }

    @Test
    fun `direct play, seek past the cache with the network off, is a break at the seek target`() {
        play(toMs = 86_719L, aheadMs = 400_000L)
        streamBreak.onSeek(3_888_464L)
        repeat(11) {
            networkErrors()
            streamBreak.onErrorLog("ffmpeg", "Seek failed (to 405432695, size 596645320)", now)
            streamBreak.onErrorLog(
                "ffmpeg/demuxer",
                "mov,mp4,m4a,3gp,3g2,mj2: stream 1, offset 0x182a6977: partial file",
                now,
            )
        }
        streamBreak.onErrorLog("lavf", "...treating it as fatal error.", now)
        // No frame after the failed seek: keep-open jumps to the last frame.
        streamBreak.onPosition(duration, duration)

        assertBroken(3_888_464L, streamBreak.atEndOfFile(duration))
    }

    @Test
    fun `direct play, cache runs out with the network off, is a break where playback got to`() {
        play(toMs = 470_000L, aheadMs = 12_000L)
        networkErrors()
        streamBreak.onErrorLog("lavf", "...treating it as fatal error.", now)
        // The demuxer stopped at 482 s; playback runs on to the end of what it has.
        streamBreak.onPosition(482_000L, duration)

        assertBroken(482_000L, streamBreak.atEndOfFile(duration))
    }

    @Test
    fun `HLS transcode, segments skipped then a normal EOF short of the end, is a break`() {
        play(toMs = 3_900_000L, aheadMs = 23_000L, fromMs = 3_880_000L)
        repeat(20) { networkErrors() } // with "hls: Failed to open segment N" warnings, no fatal
        streamBreak.onPosition(3_923_185L, duration)

        assertBroken(3_923_185L, streamBreak.atEndOfFile(duration))
    }

    @Test
    fun `HLS transcode, cache runs out, the buffered end wobbling after EOF is not new data`() {
        // Recorded on the phone (720 kbps cap): the network went at 4881 s of the cache.
        streamBreak.onSeek(4_858_542L)
        play(toMs = 4_878_974L, aheadMs = 2_435L, fromMs = 4_858_542L)
        streamBreak.onBufferedEnd(4_881_409L, now)
        networkErrors()
        // Packets ffmpeg had already read still come in between the errors.
        streamBreak.onBufferedEnd(4_883_620L, now)
        networkErrors()
        streamBreak.onBufferedEnd(4_884_503L, now)
        repeat(50) { networkErrors() } // and "hls: Segment N failed too many times, skipping"
        streamBreak.onBufferedEnd(4_884_479L, now)
        streamBreak.onPosition(4_881_509L, duration)
        streamBreak.onBufferedEnd(4_884_503L, now)
        streamBreak.onPosition(4_884_446L, duration)

        assertBroken(4_884_446L, streamBreak.atEndOfFile(duration))
    }

    @Test
    fun `HLS transcode, a short drop, the last packets after the errors are not a recovery`() {
        // Recorded on the phone (720 kbps cap, network off for 6 s): the HLS demuxer skipped every
        // remaining segment in that second and mpv then passed on what ffmpeg had already read.
        play(toMs = 4_999_127L, aheadMs = 5_516L, fromMs = 4_973_001L)
        streamBreak.onErrorLog(
            "ffmpeg",
            "tcp: Connection to tcp://demo.jellyfin.org:443 failed",
            now,
        )
        now += 30L
        streamBreak.onBufferedEnd(5_006_593L, now)
        repeat(40) { networkErrors() }
        now += 250L
        streamBreak.onBufferedEnd(5_007_535L, now)
        now += 500L
        streamBreak.onBufferedEnd(5_007_615L, now)
        now += 7_000L
        streamBreak.onPosition(5_007_569L, duration)

        assertBroken(5_007_569L, streamBreak.atEndOfFile(duration))
    }

    @Test
    fun `an error after the last data, buffered to the end, is a real end`() {
        play(toMs = duration - 60_000L, aheadMs = 120_000L)
        // The server closes the connection once the last bytes are in.
        streamBreak.onErrorLog("ffmpeg", "https: Error reading HTTP response: I/O error", now)
        streamBreak.onPosition(duration, duration)

        assertEquals(Outcome.Finished, streamBreak.atEndOfFile(duration))
    }

    @Test
    fun `an error within the end margin is a real end`() {
        play(toMs = duration - 30_000L, aheadMs = 20_000L)
        streamBreak.onBufferedEnd(duration - 3_000L, now)
        networkErrors()

        assertEquals(Outcome.Finished, streamBreak.atEndOfFile(duration))
    }

    @Test
    fun `decoder and output errors during normal playback are a real end`() {
        play(toMs = duration - 60_000L, aheadMs = 120_000L)
        streamBreak.onErrorLog(
            "ffmpeg/video",
            "h264_mediacodec: Both surface and native_window are NULL",
            now,
        )
        streamBreak.onErrorLog(
            "vo/gpu-next/libplacebo",
            "libplacebo compiled without LittleCMS 2 support!",
            now,
        )
        streamBreak.onErrorLog("vo/gpu-next/aimagereader", "acquireLatestImage failed: -30001", now)
        streamBreak.onPosition(duration, duration)

        assertEquals(Outcome.Finished, streamBreak.atEndOfFile(duration))
    }

    @Test
    fun `decoder errors with a buffer short of the end are not a break`() {
        // A file whose duration is longer than its content, with no network trouble.
        play(toMs = 4_000_000L, aheadMs = 0L)
        streamBreak.onErrorLog(
            "ffmpeg/video",
            "h264_mediacodec: Both surface and native_window are NULL",
            now,
        )

        assertEquals(Outcome.Finished, streamBreak.atEndOfFile(duration))
    }

    @Test
    fun `a short drop that recovers is a real end later`() {
        play(toMs = 600_000L, aheadMs = 60_000L)
        networkErrors()
        streamBreak.onErrorLog(
            "ffmpeg",
            "tcp: Connection to tcp://demo.jellyfin.org:443 failed",
            now,
        )
        // The connection is back: data arrives again and playback runs to the end.
        play(toMs = duration, aheadMs = 60_000L, fromMs = 610_000L)

        assertEquals(Outcome.Finished, streamBreak.atEndOfFile(duration))
    }

    @Test
    fun `a seek that got data is not a pending seek any more`() {
        play(toMs = 100_000L, aheadMs = 20_000L)
        streamBreak.onSeek(1_000_000L)
        play(toMs = 1_200_000L, aheadMs = 20_000L, fromMs = 1_000_000L)
        networkErrors()
        streamBreak.onPosition(1_220_000L, duration)

        assertBroken(1_220_000L, streamBreak.atEndOfFile(duration))
    }

    @Test
    fun `a seek inside the cache with the network off breaks where playback got to`() {
        play(toMs = 400_000L, aheadMs = 100_000L)
        networkErrors()
        // The demuxer stopped at 500 s; a seek inside that does not move the cache end.
        streamBreak.onSeek(480_000L)
        streamBreak.onPosition(480_000L, duration)
        streamBreak.onPosition(490_000L, duration)
        streamBreak.onPosition(500_000L, duration)

        assertBroken(500_000L, streamBreak.atEndOfFile(duration))
    }

    @Test
    fun `without a duration the end is a real end`() {
        play(toMs = 100_000L, aheadMs = 20_000L)
        networkErrors()

        assertEquals(Outcome.Finished, streamBreak.atEndOfFile(null))
        assertEquals(Outcome.Finished, streamBreak.atEndOfFile(0L))
    }

    @Test
    fun `without a stream error the end is a real end`() {
        play(toMs = 100_000L, aheadMs = 20_000L)

        assertEquals(Outcome.Finished, streamBreak.atEndOfFile(duration))
    }

    @Test
    fun `a new file forgets the last one's errors`() {
        play(toMs = 100_000L, aheadMs = 20_000L)
        networkErrors()
        streamBreak.reset()

        assertEquals(Outcome.Finished, streamBreak.atEndOfFile(duration))
    }

    @Test
    fun `only the stream and demuxer layers count`() {
        val network = "tcp: Failed to resolve hostname demo.jellyfin.org"
        listOf("ffmpeg", "ffmpeg/demuxer", "lavf", "demux", "stream", "stream/http").forEach {
            assertTrue(it, MpvStreamBreak.isStreamError(it, network))
        }
        listOf(
                "ffmpeg/video",
                "ffmpeg/audio",
                "vd",
                "ad",
                "vo/gpu-next",
                "ao/audiotrack",
                "cplayer",
            )
            .forEach { assertTrue(it, !MpvStreamBreak.isStreamError(it, network)) }
        // Recorded on the tablet at a seek near the end of an episode: the hardware decoder.
        assertTrue(
            !MpvStreamBreak.isStreamError(
                "ffmpeg",
                "amediacodec: java.lang.IllegalStateException: releaseOutputBuffer() is valid " +
                    "only at Executing states; currently during flush()",
            )
        )
        assertTrue(!MpvStreamBreak.isStreamError("ffmpeg", " "))
    }
}
