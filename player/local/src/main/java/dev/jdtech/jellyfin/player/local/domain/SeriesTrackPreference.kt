package dev.jdtech.jellyfin.player.local.domain

import dev.jdtech.jellyfin.models.FindroidMediaStream
import dev.jdtech.jellyfin.player.core.domain.models.ServerAudioTrack
import java.util.Locale
import org.jellyfin.sdk.model.api.MediaStreamType

/** A track as the matcher sees it: just what survives from one file to the next. */
data class TrackCandidate(val language: String?, val title: String?)

/** The identifying part of a stored series track choice. */
data class StoredChoice(val language: String?, val title: String?, val positionInLanguage: Int?)

/**
 * The ISO 639-2 languages with two codes: the bibliographic one, which Matroska files are tagged
 * with ("dut"), to the terminologic one, which ExoPlayer and the server turn it into ("nld").
 */
private val BIBLIOGRAPHIC_CODES =
    mapOf(
        "alb" to "sqi",
        "arm" to "hye",
        "baq" to "eus",
        "bur" to "mya",
        "chi" to "zho",
        "cze" to "ces",
        "dut" to "nld",
        "fre" to "fra",
        "geo" to "kat",
        "ger" to "deu",
        "gre" to "ell",
        "ice" to "isl",
        "mac" to "mkd",
        "mao" to "mri",
        "may" to "msa",
        "per" to "fas",
        "rum" to "ron",
        "slo" to "slk",
        "tib" to "bod",
        "wel" to "cym",
    )

/**
 * Normalizes a language code to ISO 639-2/T, so the "en" ExoPlayer reports and the "eng" mpv
 * reports compare equal, and so do the "dut" mpv reads from a file and the "nld" ExoPlayer and
 * the server make of it (#580). Blank and undetermined codes give null.
 */
fun normalizeLanguage(code: String?): String? {
    val base = code?.trim()?.lowercase()?.split('-', '_')?.firstOrNull().orEmpty()
    if (base.isEmpty() || base == "und") return null
    if (base.length != 2 && base.length != 3) return base
    val iso3 =
        try {
            Locale(base).isO3Language.ifEmpty { base }
        } catch (e: Exception) {
            base
        }
    return BIBLIOGRAPHIC_CODES[iso3] ?: iso3
}

private fun sameTitle(a: String?, b: String?): Boolean {
    val left = a?.trim().orEmpty()
    val right = b?.trim().orEmpty()
    return left.isNotEmpty() && left.equals(right, ignoreCase = true)
}

/**
 * Finds the track in [candidates] that best matches [stored], or null when none does. In order of
 * preference: same language and title, same language and position within that language, first
 * track of the same language.
 */
fun matchSeriesTrack(stored: StoredChoice, candidates: List<TrackCandidate>): Int? {
    val language = normalizeLanguage(stored.language)
    val sameLanguage =
        candidates.indices.filter { normalizeLanguage(candidates[it].language) == language }

    sameLanguage
        .firstOrNull { sameTitle(candidates[it].title, stored.title) }
        ?.let {
            return it
        }
    if (language == null) return null
    stored.positionInLanguage?.let { position ->
        sameLanguage.getOrNull(position)?.let {
            return it
        }
    }
    return sameLanguage.firstOrNull()
}

/**
 * Describes the track at [index] in [candidates] for storing, or null when it has neither a
 * language nor a title and so cannot be recognized in another file.
 */
fun seriesTrackChoice(candidates: List<TrackCandidate>, index: Int): StoredChoice? {
    val candidate = candidates.getOrNull(index) ?: return null
    val language = normalizeLanguage(candidate.language)
    val title = candidate.title?.trim()?.ifEmpty { null }
    if (language == null && title == null) return null
    val position =
        language?.let { candidates.take(index).count { normalizeLanguage(it.language) == language } }
    return StoredChoice(language, title, position)
}

/**
 * CE (#580): the server's index of the audio stream in [streams] that [choice] names, or null
 * when none matches. A transcode carries one audio track, so the choice has to go to the server
 * with the request; the player cannot select it afterwards. External audio is left out: the
 * server's transcode is of the file's own streams.
 */
fun audioStreamIndex(choice: StoredChoice, streams: List<FindroidMediaStream>): Int? {
    val audio = serverAudioTracks(streams)
    val match = matchSeriesTrack(choice, audio.map { TrackCandidate(it.language, it.title) })
    return match?.let { audio[it].index }
}

/**
 * CE (#596): the audio streams in [streams] a transcode can carry, in the file's order, as the
 * audio dialog lists them while the server transcodes. External audio is left out, as in
 * [audioStreamIndex].
 */
fun serverAudioTracks(streams: List<FindroidMediaStream>): List<ServerAudioTrack> =
    streams
        .filter { it.type == MediaStreamType.AUDIO && !it.isExternal }
        .mapNotNull { stream ->
            ServerAudioTrack(
                index = stream.index ?: return@mapNotNull null,
                title = stream.title.trim().ifEmpty { null },
                language = stream.language.trim().ifEmpty { null },
                codec = stream.codec.trim().ifEmpty { null },
                channels = stream.channels,
            )
        }
