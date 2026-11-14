package dev.jdtech.jellyfin.player.local.domain

import androidx.media3.common.C
import androidx.media3.common.Format
import dev.jdtech.jellyfin.player.core.domain.models.ServerAudioTrack
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #451: the track dialogs showed raw codes ("und - mp4a.40.2" on ExoPlayer, "aac" on mpv) and a
 * different name per backend. Each case builds the [Format] each backend gives for the same track
 * and expects one readable name for both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackNamesTest {

    private fun name(format: Format) = trackName(format, forcedLabel = "Forced", Locale.ENGLISH)

    private fun exoAudio(
        label: String? = null,
        language: String? = null,
        mime: String,
        codecs: String? = null,
        channels: Int,
    ) =
        Format.Builder()
            .setLabel(label)
            .setLanguage(language)
            .setSampleMimeType(mime)
            .setCodecs(codecs)
            .setChannelCount(channels)
            .build()

    /** What MPVPlayer builds from mpv's track-list. */
    private fun mpvTrack(
        type: String,
        label: String? = null,
        language: String? = null,
        codec: String,
        channels: Int = Format.NO_VALUE,
        flags: Int = 0,
    ) =
        Format.Builder()
            .setLabel(label)
            .setLanguage(language)
            .setCodecs(codec)
            .setSampleMimeType("$type/$codec")
            .setChannelCount(channels)
            .setSelectionFlags(flags)
            .build()

    /** A subtitle ExoPlayer parsed itself: the original MIME type is in codecs. */
    private fun exoSubtitle(
        label: String? = null,
        language: String?,
        mime: String,
        flags: Int = 0,
    ) =
        Format.Builder()
            .setLabel(label)
            .setLanguage(language)
            .setSampleMimeType("application/x-media3-cues")
            .setCodecs(mime)
            .setSelectionFlags(flags)
            .build()

    private fun assertBoth(expected: String, exo: Format, mpv: Format) {
        assertEquals("ExoPlayer", expected, name(exo))
        assertEquals("mpv", expected, name(mpv))
    }

    @Test
    fun `an untagged AAC stereo track is named by codec and channels`() {
        // The demo server's only track: "und - mp4a.40.2" on ExoPlayer, "aac" on mpv.
        assertBoth(
            "AAC 2.0",
            exoAudio(
                language = "und",
                mime = "audio/mp4a-latm",
                codecs = "mp4a.40.2",
                channels = 2,
            ),
            mpvTrack("audio", codec = "aac", channels = 2),
        )
    }

    @Test
    fun `a tagged track without a title gets language, codec and layout`() {
        assertBoth(
            "English · AC3 5.1",
            exoAudio(language = "en", mime = "audio/ac3", channels = 6),
            mpvTrack("audio", language = "eng", codec = "ac3", channels = 6),
        )
    }

    @Test
    fun `parts the title already names are left out`() {
        // Tracks Movie: "English Stereo" (eng, AAC mono) and "Dutch 5.1" (dut, AC3 5.1).
        assertBoth(
            "English Stereo · AAC 1.0",
            exoAudio(
                label = "English Stereo",
                language = "en",
                mime = "audio/mp4a-latm",
                codecs = "mp4a.40.2",
                channels = 1,
            ),
            mpvTrack(
                "audio",
                label = "English Stereo",
                language = "eng",
                codec = "aac",
                channels = 1,
            ),
        )
        assertBoth(
            "Dutch 5.1 · AC3",
            exoAudio(label = "Dutch 5.1", language = "nl", mime = "audio/ac3", channels = 6),
            mpvTrack("audio", label = "Dutch 5.1", language = "dut", codec = "ac3", channels = 6),
        )
    }

    @Test
    fun `subtitles are named by language and format`() {
        assertBoth(
            "English · SRT",
            exoSubtitle(language = "en", mime = "application/x-subrip"),
            mpvTrack("text", language = "eng", codec = "subrip"),
        )
        assertBoth(
            "Japanese · ASS",
            exoSubtitle(language = "ja", mime = "text/x-ssa"),
            mpvTrack("text", language = "jpn", codec = "ass"),
        )
        assertBoth(
            "PGS",
            exoSubtitle(language = null, mime = "application/pgs"),
            mpvTrack("text", codec = "hdmv_pgs_subtitle"),
        )
    }

    @Test
    fun `a forced subtitle says so`() {
        assertBoth(
            "Dutch · Forced · SRT",
            exoSubtitle(
                language = "nl",
                mime = "application/x-subrip",
                flags = C.SELECTION_FLAG_FORCED,
            ),
            mpvTrack("text", language = "dut", codec = "subrip", flags = C.SELECTION_FLAG_FORCED),
        )
    }

    @Test
    fun `an external subtitle keeps its External title`() {
        assertBoth(
            "External · German · SRT",
            Format.Builder()
                .setLabel("External")
                .setLanguage("de")
                .setSampleMimeType("application/x-subrip")
                .build(),
            mpvTrack("text", label = "External", language = "ger", codec = "subrip"),
        )
    }

    @Test
    fun `a regional language tag names the language and region`() {
        // The old code kept the part after the dash: "pt-BR" became "br", Breton.
        assertEquals(
            "Portuguese (Brazil) · AAC 2.0",
            name(exoAudio(language = "pt-BR", mime = "audio/mp4a-latm", channels = 2)),
        )
    }

    @Test
    fun `codes for no language are left out`() {
        for (code in listOf("und", "mul", "zxx", "mis", "")) {
            assertEquals(
                code,
                "Opus 2.0",
                name(exoAudio(language = code, mime = "audio/opus", channels = 2)),
            )
        }
    }

    @Test
    fun `the language name follows the app locale`() {
        val format = exoAudio(language = "en", mime = "audio/eac3", channels = 8)
        assertEquals("Engels · E-AC3 7.1", trackName(format, "Geforceerd", Locale("nl")))
    }

    @Test
    fun `codec names cover both backends`() {
        val pairs =
            listOf(
                Triple("audio/eac3-joc", "eac3", "E-AC3"),
                Triple("audio/vnd.dts", "dts", "DTS"),
                Triple("audio/true-hd", "truehd", "TrueHD"),
                Triple("audio/flac", "flac", "FLAC"),
                Triple("audio/mpeg", "mp3", "MP3"),
                Triple("audio/raw", "pcm_s16le", "PCM"),
            )
        for ((exoMime, mpvCodec, expected) in pairs) {
            assertBoth(
                "$expected 2.0",
                exoAudio(mime = exoMime, channels = 2),
                mpvTrack("audio", codec = mpvCodec, channels = 2),
            )
        }
        assertEquals(
            "HE-AAC 2.0",
            name(exoAudio(mime = "audio/mp4a-latm", codecs = "mp4a.40.5", channels = 2)),
        )
    }

    @Test
    fun `an unknown codec keeps its own name`() {
        assertEquals("WMAPRO 2.0", name(mpvTrack("audio", codec = "wmapro", channels = 2)))
    }

    @Test
    fun `a track with nothing to name falls back to its id`() {
        assertEquals("3", name(Format.Builder().setId("3").build()))
    }

    private fun serverName(track: ServerAudioTrack) =
        serverTrackName(track, forcedLabel = "Forced", Locale.ENGLISH)

    @Test
    fun `an audio stream of the server has the name the player gives the same track`() {
        // #596: during a transcode the audio dialog lists the server's streams. The server says
        // "nld" and "ac3" where ExoPlayer says "nl" and "audio/ac3".
        assertEquals(
            name(exoAudio("Dutch 5.1", "nl", "audio/ac3", channels = 6)),
            serverName(ServerAudioTrack(3, "Dutch 5.1", "nld", "ac3", 6)),
        )
        assertEquals(
            "Dutch 5.1 · AC3",
            serverName(ServerAudioTrack(3, "Dutch 5.1", "nld", "ac3", 6)),
        )
        assertEquals(
            "English Stereo · AAC 2.0",
            serverName(ServerAudioTrack(2, "English Stereo", "eng", "aac", 2)),
        )
        assertEquals("Dutch · E-AC3 5.1", serverName(ServerAudioTrack(1, null, "dut", "eac3", 6)))
    }

    @Test
    fun `an audio stream of the server with nothing to name falls back to its index`() {
        assertEquals("4", serverName(ServerAudioTrack(4, null, null, null, null)))
    }
}
