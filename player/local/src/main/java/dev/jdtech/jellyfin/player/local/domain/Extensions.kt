package dev.jdtech.jellyfin.player.local.domain

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import dev.jdtech.jellyfin.player.core.domain.models.ServerAudioTrack
import dev.jdtech.jellyfin.player.local.R
import java.util.Locale

/** The names of [this] track groups in the track dialogs, as [trackName] gives them. */
fun List<Tracks.Group>.getTrackNames(context: Context): Array<String> {
    val forcedLabel = context.getString(R.string.ce_track_forced)
    return map { group -> trackName(group.mediaTrackGroup.getFormat(0), forcedLabel) }
        .toTypedArray()
}

/**
 * CE (#596): the names of [this] audio streams of the server in the audio dialog, as
 * [serverTrackName] gives them.
 */
@JvmName("getServerTrackNames")
fun List<ServerAudioTrack>.getTrackNames(context: Context): List<String> {
    val forcedLabel = context.getString(R.string.ce_track_forced)
    return map { serverTrackName(it, forcedLabel) }
}

/**
 * CE (#596): the name of an audio stream as the server lists it, by the rules of [trackName], so a
 * track has the same name whether the player lists it or, during a transcode, the server.
 */
fun serverTrackName(
    track: ServerAudioTrack,
    forcedLabel: String,
    locale: Locale = Locale.getDefault(),
): String =
    trackName(
        Format.Builder()
            .setId(track.index)
            .setLabel(track.title)
            .setLanguage(track.language)
            .setCodecs(track.codec)
            .setChannelCount(track.channels ?: Format.NO_VALUE)
            .build(),
        forcedLabel,
        locale,
    )

/**
 * CE (#451): the name of a track in the track dialogs, the same on ExoPlayer and mpv, such as
 * "English · AC3 5.1" or "Dutch · Forced · SRT". In order: the track's title, its language (left
 * out when undetermined), [forcedLabel] for a forced track, a short codec name and, for audio, the
 * channel layout. A part the title already contains is left out ("Dutch 5.1 · AC3").
 */
fun trackName(format: Format, forcedLabel: String, locale: Locale = Locale.getDefault()): String {
    val title = format.label?.trim()?.takeIf { it.isNotEmpty() }
    val parts = mutableListOf<String>()
    title?.let(parts::add)
    fun unlessInTitle(part: String?): String? = part?.takeUnless {
        it.isBlank() || (title != null && containsWord(title, it))
    }
    unlessInTitle(languageName(format.language, locale))?.let(parts::add)
    if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) {
        unlessInTitle(forcedLabel)?.let(parts::add)
    }
    // Codec and layout make one part: "AC3 5.1".
    listOfNotNull(
            unlessInTitle(codecName(format)),
            unlessInTitle(channelLayout(format.channelCount)),
        )
        .takeIf { it.isNotEmpty() }
        ?.let { parts.add(it.joinToString(" ")) }
    return parts.joinToString(" · ").ifEmpty { format.id.orEmpty() }
}

/** Language codes that name no language. */
private val NO_LANGUAGE = setOf("und", "mul", "zxx", "mis")

/**
 * The display name of [code] in [locale], such as "Dutch" for "nl", "dut" or "nld", and "Portuguese
 * (Brazil)" for "pt-BR". Null for a missing or undetermined language.
 */
@OptIn(UnstableApi::class)
internal fun languageName(code: String?, locale: Locale): String? {
    val raw = code?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    // ExoPlayer reports "nl", mpv "dut": both become "nl".
    val tag = Util.normalizeLanguageCode(raw.replace('_', '-'))
    val target = Locale.forLanguageTag(tag)
    if (target.language.isEmpty() || target.language in NO_LANGUAGE) return null
    val name =
        target.getDisplayName(locale).ifBlank {
            return null
        }
    return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}

/**
 * Short codec names, keyed by what either backend reports: ExoPlayer's MIME types and RFC 6381
 * codec strings (without the "audio/" or "application/" part), and mpv's (ffmpeg's) codec names.
 */
private val CODEC_NAMES =
    mapOf(
        // Audio
        "aac" to "AAC",
        "mp4a-latm" to "AAC",
        "mp4a.40.2" to "AAC",
        "mp4a.40.5" to "HE-AAC",
        "mp4a.40.29" to "HE-AAC",
        "ac3" to "AC3",
        "ac-3" to "AC3",
        "eac3" to "E-AC3",
        "ec-3" to "E-AC3",
        "eac3-joc" to "E-AC3",
        "ec+3" to "E-AC3",
        "ac4" to "AC4",
        "ac-4" to "AC4",
        "dts" to "DTS",
        "vnd.dts" to "DTS",
        "dtsc" to "DTS",
        "vnd.dts.hd" to "DTS-HD",
        "dtsh" to "DTS-HD",
        "dtsl" to "DTS-HD",
        "vnd.dts.uhd" to "DTS:X",
        "truehd" to "TrueHD",
        "true-hd" to "TrueHD",
        "mlpa" to "TrueHD",
        "opus" to "Opus",
        "vorbis" to "Vorbis",
        "flac" to "FLAC",
        "mp3" to "MP3",
        "mpeg" to "MP3",
        "mp2" to "MP2",
        "mpeg-l2" to "MP2",
        "alac" to "ALAC",
        "raw" to "PCM",
        // Subtitles
        "subrip" to "SRT",
        "x-subrip" to "SRT",
        "ass" to "ASS",
        "ssa" to "ASS",
        "x-ssa" to "ASS",
        "webvtt" to "WebVTT",
        "vtt" to "WebVTT",
        "x-mp4-vtt" to "WebVTT",
        "wvtt" to "WebVTT",
        "pgs" to "PGS",
        "hdmv_pgs_subtitle" to "PGS",
        "vobsub" to "VobSub",
        "dvd_subtitle" to "VobSub",
        "dvbsubs" to "DVB",
        "dvb_subtitle" to "DVB",
        "ttml+xml" to "TTML",
        "ttml" to "TTML",
        "mov_text" to "TX3G",
        "x-quicktime-tx3g" to "TX3G",
        "cea-608" to "CEA-608",
        "cea-708" to "CEA-708",
    )

/** What ExoPlayer reports as the sample type of subtitles it parsed itself; says nothing. */
private const val MEDIA3_CUES = "x-media3-cues"

/**
 * A short codec name for [format]: ExoPlayer puts the codec in `codecs` (or, for subtitles it
 * parsed, the original MIME type) and its MIME type in `sampleMimeType`; mpv puts its codec name in
 * `codecs`. An unknown codec keeps its own name in capitals.
 */
internal fun codecName(format: Format): String? {
    val candidates =
        listOfNotNull(format.codecs, format.sampleMimeType)
            .flatMap { it.split(',') }
            .map { it.trim().lowercase(Locale.ROOT).substringAfter('/') }
            .filter { it.isNotEmpty() && it != MEDIA3_CUES }
    for (candidate in candidates) {
        CODEC_NAMES[candidate]?.let {
            return it
        }
        if (candidate.startsWith("pcm")) return "PCM"
        if (candidate.startsWith("mp4a.40.")) return "AAC"
    }
    return candidates.firstOrNull()?.uppercase(Locale.ROOT)
}

/** The channel layout of an audio track, such as "2.0" or "5.1". Null for anything else. */
internal fun channelLayout(channelCount: Int): String? =
    when (channelCount) {
        1 -> "1.0"
        2 -> "2.0"
        3 -> "2.1"
        4 -> "4.0"
        5 -> "5.0"
        6 -> "5.1"
        7 -> "6.1"
        8 -> "7.1"
        else -> null
    }

/** True when [text] contains [word] as a whole word, ignoring case. */
private fun containsWord(text: String, word: String): Boolean =
    Regex(
            "(?<![\\p{L}\\p{N}])" + Regex.escape(word) + "(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE,
        )
        .containsMatchIn(text)
