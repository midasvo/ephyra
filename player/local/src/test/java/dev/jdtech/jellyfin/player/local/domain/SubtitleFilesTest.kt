package dev.jdtech.jellyfin.player.local.domain

import androidx.media3.common.MimeTypes
import dev.jdtech.jellyfin.models.FindroidMediaStream
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSourceType
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #597: a transcode does not carry the subtitle tracks that are inside the file, and only the
 * external ones were loaded next to it. The server lists each track it can extract with a URL of
 * its own. The streams here are what the test server answers for "Tracks Movie": two tracks in the
 * file (the Dutch one forced) and an external German one, which the server lists first.
 */
class SubtitleFilesTest {

    @Test
    fun `a transcode gets the tracks inside the file as files, before the external ones`() {
        val files =
            subtitleFiles(source(transcoded = true, method = SubtitleDeliveryMethod.EXTERNAL))

        assertEquals(listOf("eng", "nld", "deu"), files.map { it.language })
        assertEquals(listOf(true, true, false), files.map { it.embedded })
        assertEquals(listOf(false, true, false), files.map { it.forced })
        assertEquals(listOf(url(4), url(5), url(0)), files.map { it.path })
    }

    @Test
    fun `a stream that plays directly gets only the external ones`() {
        // ExoPlayer's profile: the server says the tracks inside the file come with the stream.
        val embed = subtitleFiles(source(transcoded = false, method = SubtitleDeliveryMethod.EMBED))
        assertEquals(listOf("deu"), embed.map { it.language })

        // mpv's profile: the server lists a URL for them all the same. Loading those would put
        // each track of the file in the dialog twice.
        val external =
            subtitleFiles(source(transcoded = false, method = SubtitleDeliveryMethod.EXTERNAL))
        assertEquals(listOf("deu"), external.map { it.language })
        assertEquals(listOf(false), external.map { it.embedded })
    }

    @Test
    fun `a track the server cannot deliver as a file is left out`() {
        // An image format it would burn into the picture: no URL, or a method that is not a file.
        val streams =
            listOf(
                subtitle(
                    4,
                    "eng",
                    codec = "dvdsub",
                    path = null,
                    method = SubtitleDeliveryMethod.ENCODE,
                ),
                subtitle(5, "nld", codec = "pgssub", method = SubtitleDeliveryMethod.ENCODE),
                subtitle(6, "fra", codec = "subrip", method = SubtitleDeliveryMethod.EXTERNAL),
                subtitle(
                    7,
                    "spa",
                    codec = "subrip",
                    path = "",
                    method = SubtitleDeliveryMethod.EXTERNAL,
                ),
            )

        val files = subtitleFiles(source(transcoded = true, streams = streams))

        assertEquals(listOf("fra"), files.map { it.language })
    }

    @Test
    fun `an image track inside the file is left out of a transcode, also with an address`() {
        // What the test server lists for "Image Subs Movie" under ExoPlayer's profile: both with an
        // address. ExoPlayer lists such a file and draws nothing from it.
        val streams =
            listOf(
                subtitle(2, "eng", codec = "PGSSUB", method = SubtitleDeliveryMethod.EXTERNAL)
                    .copy(
                        path =
                            "http://server/Videos/id/source/Subtitles/2/0/Stream.pgssub?ApiKey=key"
                    ),
                subtitle(3, "nld", codec = "DVDSUB", method = SubtitleDeliveryMethod.EXTERNAL)
                    .copy(
                        path =
                            "http://server/Videos/id/source/Subtitles/3/0/Stream.dvdsub?ApiKey=key"
                    ),
                subtitle(4, "deu", codec = "mov_text", method = SubtitleDeliveryMethod.EXTERNAL)
                    .copy(
                        path = "http://server/Videos/id/source/Subtitles/4/0/Stream.srt?ApiKey=key"
                    ),
                subtitle(5, "fra", codec = "pgssub", external = true)
                    .copy(
                        path =
                            "http://server/Videos/id/source/Subtitles/5/0/Stream.pgssub?ApiKey=key"
                    ),
            )

        val files = subtitleFiles(source(transcoded = true, streams = streams))

        // The converted text track, and the external image file, which was loaded before too.
        assertEquals(listOf("deu", "fra"), files.map { it.language })
        assertEquals(
            listOf(MimeTypes.APPLICATION_SUBRIP, MimeTypes.APPLICATION_PGS),
            files.map { it.mimeType },
        )
    }

    @Test
    fun `audio and video streams are never subtitle files`() {
        val streams =
            listOf(
                subtitle(1, "eng", method = SubtitleDeliveryMethod.EXTERNAL)
                    .copy(type = MediaStreamType.AUDIO),
                subtitle(2, "eng", external = true).copy(type = MediaStreamType.VIDEO),
            )

        assertEquals(0, subtitleFiles(source(transcoded = true, streams = streams)).size)
    }

    @Test
    fun `a download gets every subtitle saved next to it, by its codec`() {
        // The row of a downloaded subtitle does not say external, and its file is named ".sub"
        // whatever is in it.
        val streams =
            listOf(subtitle(0, "deu", codec = "subrip", path = "/downloads/Movie.source.id.sub"))
        val source =
            source(transcoded = false, streams = streams).copy(type = FindroidSourceType.LOCAL)

        val files = subtitleFiles(source)

        assertEquals(listOf("deu"), files.map { it.language })
        assertEquals(listOf(false), files.map { it.embedded })
        assertEquals(listOf(MimeTypes.APPLICATION_SUBRIP), files.map { it.mimeType })
    }

    @Test
    fun `the format the server delivers decides the type, not the codec of the track`() {
        // A track the server converts: MP4 timed text delivered as SubRip.
        assertEquals(
            MimeTypes.APPLICATION_SUBRIP,
            subtitleMimeType(
                "mov_text",
                "http://server/Videos/id/source/Subtitles/3/0/Stream.srt?ApiKey=key",
            ),
        )
        assertEquals(
            MimeTypes.APPLICATION_SUBRIP,
            subtitleMimeType("subrip", "http://server/Videos/id/source/Subtitles/3/0/Stream.subrip"),
        )
        assertEquals(MimeTypes.TEXT_VTT, subtitleMimeType("subrip", "http://server/Stream.vtt"))
        assertEquals(MimeTypes.TEXT_SSA, subtitleMimeType("ass", "http://server/Stream.ass?a=b.c"))
        assertEquals(
            MimeTypes.APPLICATION_PGS,
            subtitleMimeType("pgssub", "http://server/Stream.sup"),
        )
    }

    @Test
    fun `without a known format in the address the codec decides`() {
        assertEquals(MimeTypes.APPLICATION_SUBRIP, subtitleMimeType("subrip", null))
        assertEquals(MimeTypes.TEXT_VTT, subtitleMimeType("webvtt", "http://server/subtitle"))
        assertEquals(
            MimeTypes.APPLICATION_VOBSUB,
            subtitleMimeType("dvdsub", "http://server.tld/a/b"),
        )
        assertEquals(
            MimeTypes.APPLICATION_PGS,
            subtitleMimeType("PGSSUB", "http://server/Stream.bin"),
        )
        assertEquals(MimeTypes.APPLICATION_DVBSUBS, subtitleMimeType("dvbsub", null))
        assertEquals(MimeTypes.TEXT_UNKNOWN, subtitleMimeType("mov_text", null))
    }

    private fun url(index: Int) =
        "http://server/Videos/id/source/Subtitles/$index/0/Stream.subrip?ApiKey=key"

    /** Tracks Movie, with the tracks inside the file delivered by [method]. */
    private fun source(
        transcoded: Boolean,
        method: SubtitleDeliveryMethod = SubtitleDeliveryMethod.EXTERNAL,
        streams: List<FindroidMediaStream> =
            listOf(
                subtitle(0, "deu", external = true, method = SubtitleDeliveryMethod.EXTERNAL),
                subtitle(2, "eng", method = null).copy(type = MediaStreamType.AUDIO),
                subtitle(
                    4,
                    "eng",
                    path = url(4).takeIf { method == SubtitleDeliveryMethod.EXTERNAL },
                    method = method,
                ),
                subtitle(
                    5,
                    "nld",
                    path = url(5).takeIf { method == SubtitleDeliveryMethod.EXTERNAL },
                    method = method,
                    forced = true,
                ),
            ),
    ) =
        FindroidSource(
            id = "source",
            name = "Source",
            type = FindroidSourceType.REMOTE,
            path = "http://server/stream",
            size = 0L,
            mediaStreams = streams,
            transcoded = transcoded,
        )

    private fun subtitle(
        index: Int,
        language: String,
        codec: String = "subrip",
        external: Boolean = false,
        path: String? = url(index),
        method: SubtitleDeliveryMethod? = null,
        forced: Boolean = false,
    ) =
        FindroidMediaStream(
            title = "",
            displayTitle = null,
            language = language,
            type = MediaStreamType.SUBTITLE,
            codec = codec,
            profile = null,
            isExternal = external,
            path = path,
            channelLayout = null,
            videoRangeType = null,
            height = null,
            width = null,
            videoDoViTitle = null,
            index = index,
            deliveryMethod = method,
            isForced = forced,
        )
}
