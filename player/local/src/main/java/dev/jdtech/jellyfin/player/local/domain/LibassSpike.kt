package dev.jdtech.jellyfin.player.local.domain

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import dev.jdtech.jellyfin.api.JellyfinApi
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegTextRenderer
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

const val LIBASS_TAG = "LibassSpike"

/**
 * Spike (#668): ASS/SSA subtitles drawn by libass (nextlib) in the Media3 player. On while the
 * system property `debug.ephyra.libass` is `1`:
 *
 *     adb shell setprop debug.ephyra.libass 1     # then open the player again
 */
fun libassSpikeEnabled(): Boolean = libassProperty("debug.ephyra.libass") == "1"

/** The measurements without libass (`stats`), for the baseline: media3's own text, or mpv. */
fun libassStatsEnabled(): Boolean = libassProperty("debug.ephyra.libass") in setOf("1", "stats")

@SuppressLint("PrivateApi")
internal fun libassProperty(name: String): String =
    try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, name) as? String ?: ""
    } catch (_: Exception) {
        ""
    }

/** media3's parsers without the ASS/SSA one, so a script reaches libass as it is. */
@OptIn(UnstableApi::class)
internal class WithoutSsaParserFactory(
    private val delegate: SubtitleParser.Factory = DefaultSubtitleParserFactory()
) : SubtitleParser.Factory by delegate {
    override fun supportsFormat(format: Format): Boolean =
        format.sampleMimeType != MimeTypes.TEXT_SSA && delegate.supportsFormat(format)
}

/** Where the fonts of the playing item are, for a script that does not come with them. */
/** libass on a thread of its own ([AsyncLibassRenderer]) instead of the playback thread. */
fun libassAsync(): Boolean = libassProperty("debug.ephyra.libass.async") == "1"

fun libassFontsDirectory(context: Context) = File(context.cacheDir, "libass-fonts")

/**
 * nextlib's libass renderer, with the app's subtitle delay and, for the spike, how long each render
 * call takes: logged every [REPORT_MS] under [LIBASS_TAG].
 */
@OptIn(UnstableApi::class)
class LibassRenderer
private constructor(private val libass: FfmpegTextRenderer, private val delayUs: () -> Long) :
    ForwardingRenderer(libass) {

    constructor(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        delayUs: () -> Long,
    ) : this(
        FfmpegTextRenderer(context, output, outputLooper, libassFontsDirectory(context)),
        delayUs,
    )

    private var windowStartNs = 0L
    private var calls = 0
    private var drawn = 0
    private var totalNs = 0L
    private var maxNs = 0L
    private var over8 = 0
    private var over16 = 0

    /**
     * The share of the playback thread libass may take, in percent (`debug.ephyra.libass.budget`, 0
     * for no limit). libass draws on the thread that also feeds the audio and video decoders: a
     * frame that takes 90 ms holds both up. With a budget, a slow frame is followed by a pause in
     * proportion, so the subtitles lose frames instead of the audio.
     */
    private val budgetPercent = libassProperty("debug.ephyra.libass.budget").toIntOrNull() ?: 0
    private var pauseUntilNs = 0L
    private var paused = 0

    override fun resetPosition(positionUs: Long, sampleStreamIsResetToKeyFrame: Boolean) {
        pauseUntilNs = 0L
        super.resetPosition(positionUs, sampleStreamIsResetToKeyFrame)
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        libass.syncOffsetMilliseconds = delayUs() / 1000
        val start = System.nanoTime()
        if (start < pauseUntilNs) {
            paused++
            return
        }
        super.render(positionUs, elapsedRealtimeUs)
        val took = System.nanoTime() - start
        if (budgetPercent in 1..99 && took > DRAW_NS) {
            pauseUntilNs = start + took * 100 / budgetPercent
        }
        if (windowStartNs == 0L) windowStartNs = start
        calls++
        // A call that draws nothing returns in microseconds.
        if (took > DRAW_NS) drawn++
        totalNs += took
        if (took > maxNs) maxNs = took
        if (took > 8_000_000) over8++
        if (took > 16_000_000) over16++
        if (start - windowStartNs >= REPORT_MS * 1_000_000) {
            val average = if (drawn == 0) 0 else totalNs / drawn / 1000
            Log.i(
                LIBASS_TAG,
                "render pos=${positionUs / 1_000_000}s calls=$calls drawn=$drawn " +
                    "total=${totalNs / 1_000_000}ms avgDrawn=${average}us " +
                    "max=${maxNs / 1000}us over8ms=$over8 over16ms=$over16 paused=$paused",
            )
            windowStartNs = 0L
            paused = 0
            calls = 0
            drawn = 0
            totalNs = 0
            maxNs = 0
            over8 = 0
            over16 = 0
        }
    }

    private companion object {
        const val REPORT_MS = 5_000L
        const val DRAW_NS = 300_000L
    }
}

/**
 * libass draws at the size of the video surface, which the player only tells the video renderers.
 */
@OptIn(UnstableApi::class)
fun Array<Renderer>.forwardVideoSizeToLibass(): Array<Renderer> {
    val libass = filter { it is LibassRenderer || it is AsyncLibassRenderer }
    if (libass.isEmpty()) return this
    return map { renderer ->
            if (renderer.trackType != C.TRACK_TYPE_VIDEO) {
                renderer
            } else {
                object : ForwardingRenderer(renderer) {
                    override fun handleMessage(messageType: Int, message: Any?) {
                        super.handleMessage(messageType, message)
                        if (messageType == Renderer.MSG_SET_VIDEO_OUTPUT_RESOLUTION) {
                            libass.forEach { it.handleMessage(messageType, message) }
                        }
                    }
                }
            }
        }
        .toTypedArray()
}

/** Spike measurements: dropped video frames, the video decoder and its format. */
@OptIn(UnstableApi::class)
fun ExoPlayer.logLibassSpikeStats() {
    addAnalyticsListener(
        object : AnalyticsListener {
            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                val at = eventTime.currentPlaybackPositionMs / 1000
                Log.i(LIBASS_TAG, "dropped=$droppedFrames in ${elapsedMs}ms at ${at}s")
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                Log.i(LIBASS_TAG, "videoDecoder=$decoderName")
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                Log.i(
                    LIBASS_TAG,
                    "videoFormat=${format.sampleMimeType} ${format.width}x${format.height} " +
                        "fps=${format.frameRate} color=${format.colorInfo}",
                )
            }

            override fun onAudioUnderrun(
                eventTime: AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long,
            ) {
                Log.i(LIBASS_TAG, "audioUnderrun sinceLastFeed=${elapsedSinceLastFeedMs}ms")
            }

            override fun onVideoDisabled(
                eventTime: AnalyticsListener.EventTime,
                decoderCounters: DecoderCounters,
            ) {
                Log.i(
                    LIBASS_TAG,
                    "videoTotals rendered=${decoderCounters.renderedOutputBufferCount} " +
                        "dropped=${decoderCounters.droppedBufferCount} " +
                        "skipped=${decoderCounters.skippedOutputBufferCount}",
                )
            }
        }
    )
}

private const val MAX_FONT_BYTES = 16L * 1024 * 1024
private const val MAX_FONTS_BYTES = 32L * 1024 * 1024

/**
 * Puts the fonts attached to [itemId]'s file in [libassFontsDirectory]. nextlib reads them from a
 * Matroska file itself, but a script the server delivers as a file of its own (during a transcode,
 * or an external file next to the video) comes without them.
 */
suspend fun downloadLibassFonts(
    context: Context,
    jellyfinApi: JellyfinApi,
    itemId: UUID,
    mediaSourceId: String,
) =
    withContext(Dispatchers.IO) {
        val directory = libassFontsDirectory(context)
        directory.deleteRecursively()
        directory.mkdirs()
        try {
            val base = jellyfinApi.api.baseUrl.orEmpty().trimEnd('/')
            val token = jellyfinApi.api.accessToken.orEmpty()
            fun get(path: String): ByteArray? {
                val request =
                    Request.Builder()
                        .url(base + path)
                        .addHeader("Authorization", "MediaBrowser Token=\"$token\"")
                        .build()
                return jellyfinApi.httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) response.body?.bytes() else null
                }
            }
            val item = JSONObject(String(get("/Items/$itemId?userId=${jellyfinApi.userId}")!!))
            val sources = item.optJSONArray("MediaSources") ?: return@withContext
            val source =
                (0 until sources.length())
                    .map { sources.getJSONObject(it) }
                    .let { all ->
                        all.firstOrNull { it.optString("Id") == mediaSourceId } ?: all.firstOrNull()
                    } ?: return@withContext
            val attachments = source.optJSONArray("MediaAttachments") ?: return@withContext
            var total = 0L
            for (i in 0 until attachments.length()) {
                val attachment = attachments.getJSONObject(i)
                val name = attachment.optString("FileName").lowercase()
                val mime = attachment.optString("MimeType").lowercase()
                val font =
                    "font" in mime ||
                        "truetype" in mime ||
                        "opentype" in mime ||
                        name.endsWith(".ttf") ||
                        name.endsWith(".otf") ||
                        name.endsWith(".ttc")
                if (!font) continue
                val index = attachment.getInt("Index")
                val sourceId = source.optString("Id", mediaSourceId)
                val bytes = get("/Videos/$itemId/$sourceId/Attachments/$index") ?: continue
                if (bytes.size > MAX_FONT_BYTES) continue
                total += bytes.size
                if (total > MAX_FONTS_BYTES) return@withContext
                val extension = name.substringAfterLast('.', "ttf").take(4)
                File(directory, "$index.$extension").writeBytes(bytes)
                Log.i(LIBASS_TAG, "font $name ${bytes.size} bytes")
            }
        } catch (e: Exception) {
            Log.w(LIBASS_TAG, "fonts not loaded", e)
        }
    }
