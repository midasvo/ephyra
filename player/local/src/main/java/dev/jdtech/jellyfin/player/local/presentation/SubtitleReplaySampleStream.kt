package dev.jdtech.jellyfin.player.local.presentation

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.source.SampleStream
import java.util.TreeMap

/**
 * CE (#619): a subtitle [SampleStream] that keeps the samples it has delivered, so they can be
 * delivered again.
 *
 * [SubtitleOffsetRenderer] shows a subtitle track [delay][SubtitleOffsetRenderer] later by
 * rendering it at an earlier position. After a seek, or after the delay grows, that position lies
 * before the samples the stream still has: the cue that is due was read before and is gone. [rewind]
 * makes the stream deliver the kept samples from a time on again, up to the next sample of
 * [upstream], before it carries on with [upstream].
 *
 * Only the playback thread uses it.
 */
@OptIn(UnstableApi::class)
class SubtitleReplaySampleStream(val upstream: SampleStream) : SampleStream {

    private class Sample(val timeUs: Long, val flags: Int, val data: ByteArray)

    /** Kept samples by time (in the stream's own time base). */
    private val kept = TreeMap<Long, MutableList<Sample>>()
    private var keptBytes = 0L

    /** Samples to deliver before [upstream]'s next one, in order. */
    private val replay = ArrayDeque<Sample>()
    private var rewindFromUs: Long? = null

    /** A format [upstream] gave while this stream peeked, still owed to the renderer. */
    private var owedFormat: Format? = null

    private val peekFormatHolder = FormatHolder()
    private val peekBuffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED)

    /** Delivers the kept samples from [fromUs] on again, before [upstream]'s next sample. */
    fun rewind(fromUs: Long) {
        replay.clear()
        rewindFromUs = fromUs
    }

    /** True while kept samples wait to be delivered again. */
    fun hasPendingReplay(): Boolean = replay.isNotEmpty()

    override fun isReady(): Boolean =
        replay.isNotEmpty() || owedFormat != null || upstream.isReady()

    override fun maybeThrowError() = upstream.maybeThrowError()

    override fun skipData(positionUs: Long): Int {
        replay.clear()
        rewindFromUs = null
        return upstream.skipData(positionUs)
    }

    override fun readData(
        formatHolder: FormatHolder,
        buffer: DecoderInputBuffer,
        readFlags: Int,
    ): Int {
        if (readFlags and SampleStream.FLAG_REQUIRE_FORMAT != 0 && owedFormat == null) {
            return upstream.readData(formatHolder, buffer, readFlags)
        }
        rewindFromUs?.let { fromUs ->
            if (!fillReplay(fromUs)) return C.RESULT_NOTHING_READ
            rewindFromUs = null
        }
        owedFormat?.let { format ->
            formatHolder.format = format
            if (readFlags and SampleStream.FLAG_PEEK == 0) owedFormat = null
            return C.RESULT_FORMAT_READ
        }
        replay.firstOrNull()?.let { sample ->
            buffer.timeUs = sample.timeUs
            buffer.setFlags(sample.flags)
            if (readFlags and SampleStream.FLAG_OMIT_SAMPLE_DATA == 0) {
                buffer.ensureSpaceForWrite(sample.data.size)
                buffer.data!!.put(sample.data)
            }
            if (readFlags and SampleStream.FLAG_PEEK == 0) replay.removeFirst()
            return C.RESULT_BUFFER_READ
        }
        val result = upstream.readData(formatHolder, buffer, readFlags)
        if (
            result == C.RESULT_BUFFER_READ &&
                !buffer.isEndOfStream &&
                readFlags and (SampleStream.FLAG_PEEK or SampleStream.FLAG_OMIT_SAMPLE_DATA) == 0
        ) {
            keep(buffer)
        }
        return result
    }

    /**
     * Puts the kept samples from [fromUs] up to [upstream]'s next sample in [replay]. False while
     * [upstream] cannot tell its next sample yet.
     */
    private fun fillReplay(fromUs: Long): Boolean {
        peekBuffer.clear()
        val nextUs =
            when (
                upstream.readData(
                    peekFormatHolder,
                    peekBuffer,
                    SampleStream.FLAG_PEEK or SampleStream.FLAG_OMIT_SAMPLE_DATA,
                )
            ) {
                C.RESULT_BUFFER_READ ->
                    if (peekBuffer.isEndOfStream) Long.MAX_VALUE else peekBuffer.timeUs
                C.RESULT_FORMAT_READ -> {
                    // The peek took the format change from upstream: pass it on, and replay
                    // nothing across it.
                    owedFormat = peekFormatHolder.format
                    peekFormatHolder.clear()
                    return true
                }
                else -> return false
            }
        if (nextUs > fromUs) {
            kept.subMap(fromUs, true, nextUs, false).values.forEach { replay.addAll(it) }
        }
        return true
    }

    private fun keep(buffer: DecoderInputBuffer) {
        val data = buffer.data ?: return
        val bytes = ByteArray(data.position())
        data.duplicate().apply { flip() }.get(bytes)
        val samples = kept.getOrPut(buffer.timeUs) { mutableListOf() }
        // A seek back reads the same samples again.
        if (samples.any { it.data.contentEquals(bytes) }) return
        val flags =
            (if (buffer.isKeyFrame) C.BUFFER_FLAG_KEY_FRAME else 0) or
                (if (buffer.isFirstSample) C.BUFFER_FLAG_FIRST_SAMPLE else 0)
        samples.add(Sample(buffer.timeUs, flags, bytes))
        keptBytes += bytes.size
        trim(aroundUs = buffer.timeUs)
    }

    /**
     * Keeps the span around the sample just read, [aroundUs]. Around the newest sample ever read
     * instead, everything read after a long seek back was dropped as soon as it was kept.
     */
    private fun trim(aroundUs: Long) {
        while (kept.isNotEmpty() && kept.firstKey() < aroundUs - KEEP_SPAN_US) {
            kept.pollFirstEntry()?.value?.forEach { keptBytes -= it.data.size }
        }
        while (kept.isNotEmpty() && kept.lastKey() > aroundUs + KEEP_SPAN_US) {
            kept.pollLastEntry()?.value?.forEach { keptBytes -= it.data.size }
        }
        while (kept.isNotEmpty() && keptBytes > MAX_KEPT_BYTES) {
            kept.pollFirstEntry()?.value?.forEach { keptBytes -= it.data.size }
        }
    }

    private companion object {
        /**
         * More than the largest delay (180 s) and the renderer's look back (30 s), so every cue
         * a delay can bring back is kept.
         */
        const val KEEP_SPAN_US = 240_000_000L
        /** Text is small; image subtitles are not, so the bytes are capped too. */
        const val MAX_KEPT_BYTES = 8L * 1024 * 1024
    }
}
