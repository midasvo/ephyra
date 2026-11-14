package dev.jdtech.jellyfin.player.local.mpv

/**
 * CE (#445): tells a real end of the file from a broken stream when mpv reports `eof-reached`.
 *
 * libmpv turns a read error into an end of file: on direct play the demuxer gives up after its
 * retries ("treating it as fatal error"), and the HLS demuxer of a transcode skips every segment it
 * cannot fetch and then ends normally. Either way `eof-reached` looks the same as a real end, and
 * the JNI layer passes no `end-file` reason. What does differ:
 * - mpv logged an error from the stream or demuxer layer since the last data arrived, and
 * - the demuxer had not buffered up to the end of the file.
 *
 * Both must hold for a break. When in doubt (no such error, no duration, buffered to the end) the
 * answer is [Outcome.Finished], which is what [MPVPlayer] did before.
 *
 * All times are in milliseconds. Not thread-safe: [MPVPlayer] calls it on the main thread.
 */
internal class MpvStreamBreak(
    private val endMarginMs: Long = END_MARGIN_MS,
    private val recoveryMs: Long = RECOVERY_MS,
) {

    sealed interface Outcome {
        data object Finished : Outcome

        /** The stream broke; playback got as far as [positionMs]. */
        data class Broken(val positionMs: Long, val message: String) : Outcome
    }

    /** The last stream or demuxer error, until data arrives well after it. */
    private var streamError: String? = null

    /** When [streamError] was logged, on the clock the caller passes in. */
    private var streamErrorAtMs = 0L

    /**
     * The highest `demuxer-cache-time` (the timestamp of the last packet the demuxer buffered)
     * since the last seek. Only a value above it is new data: after the end of the stream the
     * property wobbles between the audio and video ends (4884503, 4884479, 4884503 on an HLS
     * transcode), and a seek starts another cached range.
     */
    private var bufferedEndMs: Long? = null

    /** `time-pos`. */
    private var positionMs: Long? = null

    /**
     * The target of the last seek this player issued, until data arrives after it or playback moves
     * on from it.
     */
    private var pendingSeekMs: Long? = null

    /** mpv started another file. */
    fun reset() {
        streamError = null
        bufferedEndMs = null
        positionMs = null
        pendingSeekMs = null
    }

    /** An mpv log line at error level or worse, logged at [nowMs]. */
    fun onErrorLog(prefix: String, text: String, nowMs: Long) {
        if (!isStreamError(prefix, text)) return
        streamError = "$prefix: ${text.trim()}"
        streamErrorAtMs = nowMs
    }

    /**
     * `demuxer-cache-time` changed at [nowMs]. A later end than any before means data arrived. It
     * only clears an error when it comes [recoveryMs] after it: for about a second after the
     * connection is gone mpv still passes on packets ffmpeg had read before, and the HLS demuxer
     * skips every remaining segment within that second.
     */
    fun onBufferedEnd(timeMs: Long, nowMs: Long) {
        val highest = bufferedEndMs
        if (highest != null && timeMs <= highest) return
        bufferedEndMs = timeMs
        if (highest == null) return
        pendingSeekMs = null
        if (nowMs - streamErrorAtMs >= recoveryMs) streamError = null
    }

    /** `time-pos` changed, in a file that is [durationMs] long (null or 0 when unknown). */
    fun onPosition(timeMs: Long, durationMs: Long?) {
        positionMs = timeMs
        // Playback moved on from the seek target, so the seek worked (from the cache when the
        // network is gone). mpv's jump to the last frame after a seek that got nothing is not that.
        val target = pendingSeekMs ?: return
        val nearEnd = durationMs != null && durationMs > 0L && timeMs >= durationMs - endMarginMs
        if (timeMs > target && !nearEnd) pendingSeekMs = null
    }

    /** This player asked mpv to seek to [targetMs]. */
    fun onSeek(targetMs: Long) {
        pendingSeekMs = targetMs
        bufferedEndMs = null
    }

    /** mpv reached the end of the file, which is [durationMs] long (null or 0 when unknown). */
    fun atEndOfFile(durationMs: Long?): Outcome {
        val error = streamError ?: return Outcome.Finished
        // Live streams and transcodes without a duration: nothing to compare with.
        if (durationMs == null || durationMs <= 0L) return Outcome.Finished
        val shortOfEnd = durationMs - endMarginMs
        // A seek that never got data: mpv shows no frame and jumps to the last one, so time-pos
        // is the duration by now. The seek target is where playback was meant to be, as ExoPlayer
        // keeps it.
        pendingSeekMs?.let { target ->
            return if (target < shortOfEnd) Outcome.Broken(target, error) else Outcome.Finished
        }
        val buffered = bufferedEndMs ?: positionMs ?: return Outcome.Finished
        if (buffered >= shortOfEnd) return Outcome.Finished
        // A time-pos at the end would be the jump to the last frame, not where playback got to.
        val position = positionMs?.takeIf { it < shortOfEnd } ?: buffered
        return Outcome.Broken(position, error)
    }

    override fun toString(): String =
        "stream error ${streamError != null}, buffered to $bufferedEndMs, position $positionMs, " +
            "pending seek $pendingSeekMs"

    companion object {
        const val END_MARGIN_MS = 5_000L
        const val RECOVERY_MS = 5_000L

        /**
         * The layers that read the file: ffmpeg's protocols (`ffmpeg`: tcp, http, tls), its
         * demuxers (`ffmpeg/demuxer`), mpv's demuxer (`lavf`, `demux`) and stream (`stream`).
         * Decoders (`ffmpeg/video`, `vd`, `ad`) and outputs (`vo/...`, `ao/...`) log errors during
         * normal playback, so they do not count. Nor does the hardware decoder's Java wrapper,
         * which logs under plain `ffmpeg` ("amediacodec: java.lang.IllegalStateException" on a
         * seek), or the empty line that follows it.
         */
        fun isStreamError(prefix: String, text: String): Boolean {
            val message = text.trim()
            if (message.isEmpty() || message.startsWith("amediacodec")) return false
            return prefix == "ffmpeg" ||
                prefix == "ffmpeg/demuxer" ||
                prefix == "lavf" ||
                prefix == "demux" ||
                prefix == "stream" ||
                prefix.startsWith("stream/")
        }
    }
}
