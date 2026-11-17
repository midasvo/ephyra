package dev.jdtech.jellyfin.player.local.domain

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.text.TextOutput
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spike (#668): libass on a thread of its own (`debug.ephyra.libass.async` set to `1`).
 *
 * nextlib's FfmpegTextRenderer draws inside `render`, on the playback thread, which also feeds the
 * audio and video decoders: a subtitle frame that takes 90 ms stops both for 90 ms. Here the
 * playback thread only reads the samples and notes the position; a worker thread gives them to
 * libass and draws the picture for the latest position. When libass cannot keep up, the subtitles
 * lose frames and nothing else does.
 */
@OptIn(UnstableApi::class)
class AsyncLibassRenderer(
    private val context: Context,
    private val output: TextOutput,
    outputLooper: Looper,
    private val delayUs: () -> Long,
) : BaseRenderer(C.TRACK_TYPE_TEXT) {
    private val outputHandler = Handler(outputLooper)
    private val thread = HandlerThread("libass").apply { start() }
    private val worker = Handler(thread.looper)

    // Playback thread.
    private val holder = FormatHolder()
    private val input = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)
    private var format: Format? = null
    private var inputEnded = false
    private var playbackNs = 0L

    // Worker thread.
    private var decoder: LibassDecoder? = null
    private var decoderFormat: Format? = null
    private var decoderFailed = false
    private var windowStartNs = 0L
    private var frames = 0
    private var totalNs = 0L
    private var maxNs = 0L

    @Volatile private var width = 1280
    @Volatile private var height = 720
    @Volatile private var latestPositionUs = 0L
    @Volatile private var offsetUs = 0L
    private val renderQueued = AtomicBoolean(false)
    private val generation = AtomicInteger()

    override fun getName() = "AsyncLibassRenderer"

    override fun isReady() = true

    override fun isEnded() = inputEnded

    override fun supportsFormat(format: Format): Int {
        val mime = format.sampleMimeType
        return RendererCapabilities.create(
            when {
                mime != MimeTypes.TEXT_SSA ->
                    if (MimeTypes.isText(mime)) C.FORMAT_UNSUPPORTED_SUBTYPE
                    else C.FORMAT_UNSUPPORTED_TYPE
                format.cryptoType != C.CRYPTO_TYPE_NONE -> C.FORMAT_UNSUPPORTED_DRM
                else -> C.FORMAT_HANDLED
            }
        )
    }

    override fun onStreamChanged(
        formats: Array<Format>,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        this.offsetUs = offsetUs
        setFormat(formats[0].withStreamOffset(offsetUs))
        inputEnded = false
        clearOutput()
    }

    override fun onPositionReset(
        positionUs: Long,
        joining: Boolean,
        sampleStreamIsResetToKeyFrame: Boolean,
    ) {
        inputEnded = false
        worker.post { decoder?.invalidate() }
        clearOutput()
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val start = System.nanoTime()
        for (sample in 0 until MAX_SAMPLES_PER_RENDER) {
            if (inputEnded) break
            input.clear()
            val result = readSource(holder, input, 0)
            if (result == C.RESULT_NOTHING_READ) break
            if (result == C.RESULT_FORMAT_READ) {
                val next = checkNotNull(holder.format)
                val current = format
                if (
                    next.sampleMimeType != current?.sampleMimeType ||
                        next.initializationData != current?.initializationData ||
                        next.metadata != current?.metadata
                ) {
                    setFormat(next)
                } else {
                    format = next
                }
            } else if (input.isEndOfStream) {
                inputEnded = true
            } else {
                input.flip()
                val data = input.data ?: continue
                val copy = ByteBuffer.allocateDirect(data.remaining())
                copy.put(data)
                copy.flip()
                val timeUs = input.timeUs
                val sampleFormat = checkNotNull(format)
                val sampleOffsetUs =
                    if (sampleFormat.subsampleOffsetUs == Format.OFFSET_SAMPLE_RELATIVE) timeUs
                    else sampleFormat.subsampleOffsetUs
                worker.post { decode(copy, timeUs, sampleOffsetUs) }
            }
        }
        // The renderer's time starts at the stream offset, far above any delay.
        latestPositionUs = positionUs - delayUs()
        if (renderQueued.compareAndSet(false, true)) worker.post(::draw)
        playbackNs += System.nanoTime() - start
    }

    private fun setFormat(next: Format) {
        format = next
        worker.post {
            closeDecoder()
            decoderFormat = next
        }
    }

    private fun decode(data: ByteBuffer, timeUs: Long, sampleOffsetUs: Long) {
        if (decoderFailed) return
        val current =
            decoder
                ?: try {
                    LibassDecoder(
                            context,
                            checkNotNull(decoderFormat),
                            libassFontsDirectory(context),
                        )
                        .also { decoder = it }
                } catch (e: Throwable) {
                    Log.e(LIBASS_TAG, "libass could not open the script", e)
                    decoderFailed = true
                    return
                }
        if (!current.decode(data, timeUs, sampleOffsetUs)) {
            Log.w(LIBASS_TAG, "libass skipped a sample at ${timeUs / 1000} ms")
        }
    }

    private fun draw() {
        renderQueued.set(false)
        val current = decoder ?: return
        val drawn = generation.get()
        val positionUs = latestPositionUs
        val start = System.nanoTime()
        val cues: List<Cue>? = current.render(positionUs, width, height)
        val took = System.nanoTime() - start
        if (cues != null) {
            val group = CueGroup(cues, positionUs - offsetUs)
            outputHandler.post {
                if (drawn != generation.get()) return@post
                @Suppress("DEPRECATION") output.onCues(cues)
                output.onCues(group)
            }
        }
        if (windowStartNs == 0L) windowStartNs = start
        if (took > DRAW_NS) {
            frames++
            totalNs += took
            if (took > maxNs) maxNs = took
        }
        if (start - windowStartNs >= REPORT_NS) {
            val average = if (frames == 0) 0 else totalNs / frames / 1000
            Log.i(
                LIBASS_TAG,
                "async pos=${(positionUs - offsetUs) / 1_000_000}s frames=$frames " +
                    "total=${totalNs / 1_000_000}ms avg=${average}us max=${maxNs / 1000}us " +
                    "playbackThread=${playbackNs / 1_000_000}ms",
            )
            windowStartNs = 0L
            frames = 0
            totalNs = 0L
            maxNs = 0L
            playbackNs = 0L
        }
    }

    private fun closeDecoder() {
        decoder?.close()
        decoder = null
        decoderFailed = false
    }

    private fun clearOutput() {
        generation.incrementAndGet()
        outputHandler.post {
            @Suppress("DEPRECATION") output.onCues(emptyList())
            output.onCues(CueGroup.EMPTY_TIME_ZERO)
        }
    }

    override fun handleMessage(messageType: Int, message: Any?) {
        if (messageType == Renderer.MSG_SET_VIDEO_OUTPUT_RESOLUTION) {
            val size = message as Size
            if (size.width <= 0 || size.height <= 0) return
            val scale = minOf(1.0, 1920.0 / size.width, 1080.0 / size.height)
            width = maxOf(1, (size.width * scale).toInt())
            height = maxOf(1, (size.height * scale).toInt())
            worker.post { decoder?.invalidate() }
        } else {
            super.handleMessage(messageType, message)
        }
    }

    override fun onDisabled() {
        worker.post { closeDecoder() }
        clearOutput()
        format = null
    }

    override fun onRelease() {
        worker.post { closeDecoder() }
        thread.quitSafely()
    }

    private fun Format.withStreamOffset(offsetUs: Long): Format =
        if (subsampleOffsetUs == Format.OFFSET_SAMPLE_RELATIVE) this
        else buildUpon().setSubsampleOffsetUs(subsampleOffsetUs + offsetUs).build()

    private companion object {
        const val MAX_SAMPLES_PER_RENDER = 100
        const val REPORT_NS = 5_000_000_000L
        const val DRAW_NS = 300_000L
    }
}
