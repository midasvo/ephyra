package dev.jdtech.jellyfin.utils

import android.content.Context
import dev.jdtech.jellyfin.core.R
import dev.jdtech.jellyfin.player.core.domain.models.Track
import dev.jdtech.jellyfin.player.core.domain.models.TrackDisplayInfo
import java.util.Locale

/**
 * Utility object for formatting media track information such as codecs, channel layouts, languages,
 * and display labels.
 */
object TrackFormatter {

    /** Formats a raw codec string into a human-readable display label. */
    fun formatCodec(codec: String?): String? {
        if (codec.isNullOrBlank()) return null
        val c = codec.lowercase(Locale.US)
        return when {
            c.contains("subrip") || c.contains("srt") -> "SubRip"
            c.contains("ass") || c.contains("ssa") || c.contains("substationalpha") -> "ASS"
            c.contains("pgs") || c.contains("hdmv_pgs") -> "PGS"
            c.contains("vtt") || c.contains("webvtt") -> "VTT"
            c.contains("dts-hd ma") -> "DTS-HD MA"
            c.contains("dts-hd hra") -> "DTS-HD HRA"
            c.contains("dts-hd") || c.contains("dtshd") -> "DTS-HD"
            c.contains("dts:x") || c.contains("dtsx") -> "DTS:X"
            c.contains("dts") || c == "dca" -> "DTS"
            c.contains("truehd") || c.contains("mlp") -> "TrueHD"
            c.contains("eac3") || c.contains("ec-3") || c.contains("e-ac-3") -> "E-AC-3"
            c.contains("ac3") || c.contains("ac-3") -> "AC-3"
            c.contains("flac") -> "FLAC"
            c.contains("alac") -> "ALAC"
            c.contains("aac") -> "AAC"
            c.contains("opus") -> "Opus"
            c.contains("vorbis") -> "Vorbis"
            c.contains("mp3") -> "MP3"
            c.contains("pcm") -> "PCM"
            c.contains("hevc") || c.contains("h265") -> "HEVC"
            c.contains("h264") || c.contains("avc") -> "AVC"
            c.contains("av1") -> "AV1"
            c.contains("dvd_subtitle") || c.contains("dvdsub") -> "VobSub"
            else -> codec.uppercase(Locale.US)
        }
    }

    /**
     * Formats audio channel layout or channel count as the local player names it ("2.0", "5.1"), so
     * nothing here needs translating.
     */
    fun formatChannelLayout(channelLayout: String? = null, channelCount: Int? = null): String? {
        if (!channelLayout.isNullOrBlank()) {
            val layout = channelLayout.trim().lowercase(Locale.US)
            return when {
                layout == "stereo" || layout == "2.0" -> "2.0"
                layout == "mono" || layout == "1.0" -> "1.0"
                layout.startsWith("5.1") -> "5.1"
                layout.startsWith("7.1") -> "7.1"
                layout.startsWith("2.1") -> "2.1"
                layout.startsWith("6.1") -> "6.1"
                layout == "atmos" -> "Atmos"
                else ->
                    channelLayout.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
                    }
            }
        }
        return when (channelCount) {
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
    }

    /** Formats an ISO language code into a localized display language name. */
    fun formatLanguageName(
        language: String?,
        currentLocale: Locale = Locale.getDefault(),
    ): String? {
        val langTag = language?.takeIf { it.isNotBlank() && it != "und" } ?: return null
        val cleanLang = langTag.split("-").firstOrNull() ?: langTag
        val localeFromTag = Locale.forLanguageTag(cleanLang.replace("_", "-"))
        var display = localeFromTag.getDisplayLanguage(currentLocale)
        if (display.isBlank() || display.equals(cleanLang, ignoreCase = true)) {
            val fallbackLocale = @Suppress("DEPRECATION") Locale(cleanLang)
            val fallbackDisplay = fallbackLocale.getDisplayLanguage(currentLocale)
            if (fallbackDisplay.isNotBlank()) {
                display = fallbackDisplay
            }
        }
        return display
            .takeIf { it.isNotBlank() }
            ?.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(currentLocale) else it.toString()
            }
    }

    /**
     * Creates formatted track display information including localized names and status tags/chips
     * for UI display. [forcedLabel] marks a forced subtitle track: the caller passes the player's
     * own string, as the local player's track names do.
     */
    fun getTrackDisplayInfo(
        track: Track,
        context: Context,
        forcedLabel: String,
        isSubtitle: Boolean,
        displayExtraInfo: Boolean = false,
        currentLocale: Locale = Locale.getDefault(),
        fallbackIndex: Int = track.id,
    ): TrackDisplayInfo {
        val localizedLanguage = formatLanguageName(track.language, currentLocale)
        var name =
            localizedLanguage
                ?: track.label?.takeIf { it.isNotBlank() }
                ?: track.language?.takeIf { it.isNotBlank() && it != "und" }
                ?: context.getString(R.string.ce_track_number, fallbackIndex)

        val chips = mutableListOf<String>()

        if (track.isDefault == true) {
            chips.add(context.getString(R.string.ce_track_default))
        }

        if (isSubtitle) {
            val subtitleAttributes = mutableListOf<String>()
            if (track.isForced == true) {
                subtitleAttributes.add(forcedLabel)
            }
            if (track.isHearingImpaired == true) {
                subtitleAttributes.add(context.getString(R.string.ce_track_sdh))
            }
            if (subtitleAttributes.isNotEmpty()) {
                name = "$name (${subtitleAttributes.joinToString(", ")})"
            }
            if (track.isOriginal == true) {
                chips.add(context.getString(R.string.ce_track_original))
            }
            val formattedCodec = formatCodec(track.codec)
            if (!formattedCodec.isNullOrBlank()) {
                chips.add(formattedCodec)
            }
            if (displayExtraInfo && track.isExternal == true) {
                chips.add(context.getString(R.string.external))
            }
        } else {
            val channelLayout = formatChannelLayout(track.channelLayout, track.channelCount)
            if (channelLayout != null) {
                chips.add(channelLayout)
            }
            val formattedCodec = formatCodec(track.codec)
            if (!formattedCodec.isNullOrBlank()) {
                chips.add(formattedCodec)
            }
            if (track.isOriginal == true) {
                chips.add(context.getString(R.string.ce_track_original))
            }
            if (displayExtraInfo && track.isExternal == true) {
                chips.add(context.getString(R.string.external))
            }
        }

        return TrackDisplayInfo(name = name, chips = chips)
    }
}
