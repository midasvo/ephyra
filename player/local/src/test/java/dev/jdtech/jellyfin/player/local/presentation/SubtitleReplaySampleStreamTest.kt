package dev.jdtech.jellyfin.player.local.presentation

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.source.SampleStream
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [SubtitleReplaySampleStream]: cues delivered again after a rewind (#619). */
class SubtitleReplaySampleStreamTest {

    /** A subtitle stream of one-cue samples at [timesS] seconds, seekable like a sample queue. */
    private class FakeStream(timesS: List<Int>) : SampleStream {
        val samples = timesS.map { it * 1_000_000L }
        var index = 0
        var formatOwed: Format? = null

        /** Moves to the sample at or before [timeS], as a sample queue does for key frames. */
        fun seekTo(timeS: Int) {
            index = samples.indexOfLast { it <= timeS * 1_000_000L }.coerceAtLeast(0)
        }

        override fun isReady() = true

        override fun maybeThrowError() = Unit

        override fun skipData(positionUs: Long) = 0

        override fun readData(holder: FormatHolder, buffer: DecoderInputBuffer, flags: Int): Int {
            formatOwed?.let {
                holder.format = it
                formatOwed = null
                return C.RESULT_FORMAT_READ
            }
            if (index == samples.size) {
                buffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM)
                return C.RESULT_BUFFER_READ
            }
            val timeUs = samples[index]
            buffer.timeUs = timeUs
            buffer.setFlags(C.BUFFER_FLAG_KEY_FRAME)
            if (flags and SampleStream.FLAG_OMIT_SAMPLE_DATA == 0) {
                val data = "cue $timeUs".toByteArray()
                buffer.ensureSpaceForWrite(data.size)
                buffer.data!!.put(data)
            }
            if (flags and SampleStream.FLAG_PEEK == 0) index++
            return C.RESULT_BUFFER_READ
        }
    }

    /** Reads [count] samples the way the text renderer does, and returns their times in seconds. */
    private fun SubtitleReplaySampleStream.read(count: Int): List<Long> =
        (1..count).map {
            val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
            assertEquals(C.RESULT_BUFFER_READ, readData(FormatHolder(), buffer, 0))
            if (buffer.isEndOfStream) -1 else buffer.timeUs / 1_000_000L
        }

    @Test
    fun `without a rewind it passes the samples on`() {
        val stream = SubtitleReplaySampleStream(FakeStream(listOf(0, 5, 10)))
        assertEquals(listOf(0L, 5L, 10L, -1L), stream.read(4))
    }

    @Test
    fun `after a seek back the cues before the seek position come again, once`() {
        val upstream = FakeStream(listOf(0, 5, 10, 15, 20, 25))
        val stream = SubtitleReplaySampleStream(upstream)
        stream.read(5)
        upstream.seekTo(16)
        stream.rewind(fromUs = 2_000_000L)
        assertEquals(listOf(5L, 10L, 15L, 20L, 25L), stream.read(5))
    }

    @Test
    fun `after a larger delay the cues already read come again before the next one`() {
        val upstream = FakeStream(listOf(0, 5, 10, 15, 20))
        val stream = SubtitleReplaySampleStream(upstream)
        stream.read(3)
        stream.rewind(fromUs = 4_000_000L)
        assertEquals(listOf(5L, 10L, 15L, 20L), stream.read(4))
    }

    @Test
    fun `a peek passes on the format it took and replays nothing across it`() {
        val upstream = FakeStream(listOf(0, 5, 10))
        val stream = SubtitleReplaySampleStream(upstream)
        stream.read(2)
        val newFormat = Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build()
        upstream.formatOwed = newFormat
        stream.rewind(fromUs = 0L)
        val holder = FormatHolder()
        assertEquals(
            C.RESULT_FORMAT_READ,
            stream.readData(
                holder,
                DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL),
                0,
            ),
        )
        assertEquals(newFormat, holder.format)
        assertEquals(listOf(10L), stream.read(1))
    }
}
