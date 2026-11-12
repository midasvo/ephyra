package dev.jdtech.jellyfin.film.domain

import dev.jdtech.jellyfin.models.FindroidMediaStream
import dev.jdtech.jellyfin.models.FindroidSource
import java.text.NumberFormat
import java.util.Locale
import org.jellyfin.sdk.model.api.MediaStreamType

/**
 * CE (#510): what the media info sheet shows for one version of a movie or episode. Built from the
 * source the page already has when the sheet opens, so it costs no request and nothing until asked.
 */
data class MediaInfo(
    /** The container in capitals ("MKV"), or null when the server did not say. */
    val container: String?,
    /** The file's size, or 0 when it is not known. */
    val sizeBytes: Long,
    /** The whole file's bitrate ("62.1 Mbps"), or null. */
    val bitrate: String?,
    val video: List<MediaInfoTrack>,
    val audio: List<MediaInfoTrack>,
    val subtitles: List<MediaInfoTrack>,
)

data class MediaInfoTrack(
    /** The server's own description: "4K HEVC HDR10", "English - TrueHD 7.1 - Default". */
    val title: String,
    /** The numbers behind it, in reading order: "3840×2160", "23.976 fps", "10-bit", "58.3 Mbps". */
    val details: List<String>,
)

/**
 * The versions the media info sheet can describe: those whose video or audio tracks are known.
 * A download stores only its sidecar subtitles, so the copy on the device is not among them, and
 * offline, where the downloads are all there is, the list is empty.
 */
fun List<FindroidSource>.withMediaInfo(): List<FindroidSource> = filter { source ->
    source.mediaStreams.any {
        it.type == MediaStreamType.VIDEO || it.type == MediaStreamType.AUDIO
    }
}

fun FindroidSource.toMediaInfo(locale: Locale = Locale.getDefault()): MediaInfo {
    val numbers = MediaInfoNumbers(locale)
    fun tracks(type: MediaStreamType) =
        mediaStreams.filter { it.type == type }.map { it.toTrack(numbers) }
    return MediaInfo(
        // A container can be a list of the names a format goes by: "mov,mp4,m4a,3gp,3g2,mj2".
        container =
            container
                ?.substringBefore(',')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.uppercase(Locale.ROOT),
        sizeBytes = size.coerceAtLeast(0),
        bitrate = bitrate?.let(numbers::bitrate),
        video = tracks(MediaStreamType.VIDEO),
        audio = tracks(MediaStreamType.AUDIO),
        subtitles = tracks(MediaStreamType.SUBTITLE),
    )
}

private fun FindroidMediaStream.toTrack(numbers: MediaInfoNumbers): MediaInfoTrack {
    val name =
        displayTitle?.takeIf { it.isNotBlank() }
            ?: title.takeIf { it.isNotBlank() }
            ?: codec.uppercase(Locale.ROOT)
    val details =
        when (type) {
            MediaStreamType.VIDEO ->
                listOfNotNull(
                    numbers.resolution(width, height),
                    frameRate?.let(numbers::frameRate),
                    bitDepth?.let(numbers::bitDepth),
                    bitRate?.let(numbers::bitrate),
                )
            MediaStreamType.AUDIO ->
                listOfNotNull(
                    sampleRate?.let(numbers::sampleRate),
                    bitDepth?.let(numbers::bitDepth),
                    bitRate?.let(numbers::bitrate),
                )
            else -> emptyList()
        }
    return MediaInfoTrack(title = name, details = details)
}

/**
 * Writes the sheet's numbers the way [locale] writes numbers: its decimal separator and its digits,
 * the same for every number on the sheet. Nothing is grouped ("3840", not "3,840"), and a value
 * that is zero or less is not a measurement, so it gives null.
 */
class MediaInfoNumbers(private val locale: Locale) {
    private fun format(maxDecimals: Int, minDecimals: Int = 0): NumberFormat =
        NumberFormat.getNumberInstance(locale).apply {
            isGroupingUsed = false
            minimumFractionDigits = minDecimals
            maximumFractionDigits = maxDecimals
        }

    private val whole = format(0)
    private val oneDecimal = format(1, 1)
    private val upToThreeDecimals = format(3)

    /** A plain count, such as how many audio tracks there are. */
    fun count(value: Int): String = whole.format(value)

    /** 3840 by 2160 is "3840×2160". */
    fun resolution(width: Int?, height: Int?): String? {
        if (width == null || height == null || width <= 0 || height <= 0) return null
        return "${whole.format(width)}×${whole.format(height)}"
    }

    /** 62 100 000 is "62.1 Mbps", 640 000 is "640 kbps". */
    fun bitrate(bitsPerSecond: Int): String? =
        when {
            bitsPerSecond <= 0 -> null
            bitsPerSecond >= 1_000_000 -> "${oneDecimal.format(bitsPerSecond / 1e6)} Mbps"
            else -> "${whole.format(bitsPerSecond / 1e3)} kbps"
        }

    /**
     * 23.976025 is "23.976 fps", 25.0 is "25 fps". A rate above [MAX_FRAME_RATE] is a time base
     * (1000, 90000) that a server reported in place of a rate, not something to show.
     */
    fun frameRate(framesPerSecond: Float): String? {
        if (!framesPerSecond.isFinite() || framesPerSecond <= 0f) return null
        if (framesPerSecond > MAX_FRAME_RATE) return null
        return "${upToThreeDecimals.format(framesPerSecond.toDouble())} fps"
    }

    /** 48 000 is "48 kHz", 44 100 is "44.1 kHz", 22 050 is "22.05 kHz". */
    fun sampleRate(hertz: Int): String? {
        if (hertz <= 0) return null
        return "${upToThreeDecimals.format(hertz / 1e3)} kHz"
    }

    /** 10 is "10-bit". */
    fun bitDepth(bits: Int): String? {
        if (bits <= 0) return null
        return "${whole.format(bits)}-bit"
    }

    private companion object {
        const val MAX_FRAME_RATE = 480f
    }
}
