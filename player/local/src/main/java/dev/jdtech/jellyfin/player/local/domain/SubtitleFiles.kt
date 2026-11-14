package dev.jdtech.jellyfin.player.local.domain

import androidx.media3.common.MimeTypes
import dev.jdtech.jellyfin.models.FindroidMediaStream
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSourceType
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod

/** A subtitle the player loads as a file of its own, next to the stream. */
data class SubtitleFile(
    val title: String,
    val language: String,
    val path: String,
    val mimeType: String,
    /** True for a track that is inside the video file, which the server extracts (#597). */
    val embedded: Boolean,
    val forced: Boolean,
    val default: Boolean,
)

/**
 * The subtitles of [source] to load as files of their own, in the order the track dialog lists
 * them.
 *
 * For a stream that plays directly these are the external ones: the tracks inside the file come
 * with the stream. `isExternal` reflects the *server's* original file layout, which only matters
 * for a REMOTE source. A LOCAL (downloaded) source only ever has a mediastreams row for a subtitle
 * that was saved as its own sidecar file (downloadExternalMediaStreams never writes a row for a
 * track that stays muxed inside the downloaded video), so every row on a LOCAL source is a real
 * external file on disk, regardless of `isExternal`.
 *
 * CE (#597): a transcode does not carry the tracks inside the file. The server extracts the ones it
 * can and lists each with a delivery URL of its own, so the text tracks among those are loaded as
 * files too, before the external ones, which is the order of a stream that plays directly. Left out
 * are a track the server lists without a URL, and an image track (PGS, VobSub, DVB) even when it
 * has one: ExoPlayer lists such a file and then draws nothing from it (seen with the PGS track of
 * the test server's "Image Subs Movie"), and a track that shows nothing is worse than a track that
 * is not offered. Only for a transcode: for a stream that plays directly the server can list the
 * same URLs (it does for mpv's profile), and the track would then be there twice.
 */
fun subtitleFiles(source: FindroidSource): List<SubtitleFile> {
    val local = source.type == FindroidSourceType.LOCAL
    val delivered =
        source.mediaStreams.filter {
            it.type == MediaStreamType.SUBTITLE && !it.path.isNullOrBlank()
        }
    val embedded =
        if (!local && source.transcoded) {
            delivered.filter {
                !it.isExternal &&
                    it.deliveryMethod == SubtitleDeliveryMethod.EXTERNAL &&
                    subtitleMimeType(it.codec, it.path) in TEXT_SUBTITLE_TYPES
            }
        } else {
            emptyList()
        }
    val external = delivered.filter { local || it.isExternal }
    return embedded.map { it.toSubtitleFile(embedded = true, local) } +
        external.map { it.toSubtitleFile(embedded = false, local) }
}

/** The subtitle formats that are text: the ones a player can load from a file of their own. */
private val TEXT_SUBTITLE_TYPES =
    setOf(
        MimeTypes.APPLICATION_SUBRIP,
        MimeTypes.TEXT_VTT,
        MimeTypes.TEXT_SSA,
        MimeTypes.APPLICATION_TTML,
    )

private fun FindroidMediaStream.toSubtitleFile(embedded: Boolean, local: Boolean) =
    SubtitleFile(
        title = title,
        language = language,
        path = path!!,
        // A downloaded file's name says nothing about its format.
        mimeType = subtitleMimeType(codec, path.takeUnless { local }),
        embedded = embedded,
        forced = isForced,
        default = isDefault,
    )

/**
 * The MIME type of a subtitle file. The format in the server's delivery [url]
 * (".../Stream.srt?...") comes first: the server converts a text track it cannot deliver as it is,
 * and the stream's [codec] is then that of the file's track, not of what is delivered. Without a
 * format in the URL the codec decides.
 */
fun subtitleMimeType(codec: String, url: String?): String {
    val format =
        url?.substringBefore('?')
            ?.substringBefore('#')
            ?.substringAfterLast('/')
            ?.substringAfterLast('.', "")
            ?.lowercase()
    return format?.let(::mimeTypeOfSubtitleFormat)
        ?: mimeTypeOfSubtitleFormat(codec.lowercase())
        ?: MimeTypes.TEXT_UNKNOWN
}

private fun mimeTypeOfSubtitleFormat(format: String): String? =
    when (format) {
        "subrip",
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "webvtt",
        "vtt" -> MimeTypes.TEXT_VTT
        "ass",
        "ssa" -> MimeTypes.TEXT_SSA
        "ttml" -> MimeTypes.APPLICATION_TTML
        // Image-based subtitle sidecars. media3 ships native parsers
        // for these, so advertising the correct MIME lets the renderer
        // actually draw the bitmaps instead of dropping the track.
        "pgssub",
        "sup" -> MimeTypes.APPLICATION_PGS
        "dvdsub",
        "idx" -> MimeTypes.APPLICATION_VOBSUB
        "dvbsub" -> MimeTypes.APPLICATION_DVBSUBS
        else -> null
    }
