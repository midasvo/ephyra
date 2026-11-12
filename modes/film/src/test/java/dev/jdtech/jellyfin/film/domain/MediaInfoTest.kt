package dev.jdtech.jellyfin.film.domain

import dev.jdtech.jellyfin.models.FindroidMediaStream
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSourceType
import java.util.Locale
import org.jellyfin.sdk.model.api.MediaStreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #510: the media info sheet's content, from the source the page already has. */
class MediaInfoTest {
    private fun stream(
        type: MediaStreamType,
        displayTitle: String? = null,
        title: String = "",
        codec: String = "",
        width: Int? = null,
        height: Int? = null,
        bitRate: Int? = null,
        frameRate: Float? = null,
        bitDepth: Int? = null,
        sampleRate: Int? = null,
    ) =
        FindroidMediaStream(
            title = title,
            displayTitle = displayTitle,
            language = "eng",
            type = type,
            codec = codec,
            profile = null,
            isExternal = false,
            path = null,
            channelLayout = null,
            videoRangeType = null,
            height = height,
            width = width,
            videoDoViTitle = null,
            bitRate = bitRate,
            frameRate = frameRate,
            bitDepth = bitDepth,
            sampleRate = sampleRate,
        )

    private fun source(
        vararg streams: FindroidMediaStream,
        container: String? = null,
        bitrate: Int? = null,
    ) =
        FindroidSource(
            id = "s",
            name = "s",
            type = FindroidSourceType.REMOTE,
            path = "",
            size = 58_000_000_000,
            mediaStreams = streams.toList(),
            container = container,
            bitrate = bitrate,
        )

    @Test
    fun `a UHD remux lists every track with its numbers`() {
        val info =
            source(
                    stream(
                        MediaStreamType.VIDEO,
                        displayTitle = "4K HEVC Dolby Vision",
                        width = 3840,
                        height = 2160,
                        bitRate = 58_300_000,
                        frameRate = 23.976025f,
                        bitDepth = 10,
                    ),
                    stream(
                        MediaStreamType.AUDIO,
                        displayTitle = "English - TrueHD 7.1 - Default",
                        bitRate = 4_210_000,
                        bitDepth = 24,
                        sampleRate = 48_000,
                    ),
                    stream(
                        MediaStreamType.AUDIO,
                        displayTitle = "English - Dolby Digital 5.1",
                        bitRate = 640_000,
                        sampleRate = 44_100,
                    ),
                    stream(MediaStreamType.SUBTITLE, displayTitle = "English - PGSSUB"),
                    container = "mkv",
                    bitrate = 62_100_000,
                )
                .toMediaInfo(Locale.US)

        assertEquals("MKV", info.container)
        assertEquals(58_000_000_000, info.sizeBytes)
        assertEquals("62.1 Mbps", info.bitrate)
        assertEquals(
            listOf(
                MediaInfoTrack(
                    "4K HEVC Dolby Vision",
                    listOf("3840×2160", "23.976 fps", "10-bit", "58.3 Mbps"),
                )
            ),
            info.video,
        )
        assertEquals(
            listOf(
                MediaInfoTrack(
                    "English - TrueHD 7.1 - Default",
                    listOf("48 kHz", "24-bit", "4.2 Mbps"),
                ),
                MediaInfoTrack("English - Dolby Digital 5.1", listOf("44.1 kHz", "640 kbps")),
            ),
            info.audio,
        )
        assertEquals(listOf(MediaInfoTrack("English - PGSSUB", emptyList())), info.subtitles)
    }

    @Test
    fun `a download, which stores none of the numbers, still lists its tracks`() {
        val info =
            source(
                    stream(MediaStreamType.VIDEO, displayTitle = "1080p H264 SDR"),
                    stream(MediaStreamType.AUDIO, displayTitle = "English - AAC - Stereo"),
                )
                .toMediaInfo(Locale.US)

        assertNull(info.container)
        assertNull(info.bitrate)
        assertEquals(listOf(MediaInfoTrack("1080p H264 SDR", emptyList())), info.video)
        assertEquals(listOf(MediaInfoTrack("English - AAC - Stereo", emptyList())), info.audio)
        assertEquals(emptyList<MediaInfoTrack>(), info.subtitles)
    }

    @Test
    fun `a track without a display title falls back to its title, then its codec`() {
        val info =
            source(
                    stream(MediaStreamType.AUDIO, displayTitle = " ", title = "Commentary"),
                    stream(MediaStreamType.SUBTITLE, codec = "subrip"),
                )
                .toMediaInfo(Locale.US)

        assertEquals("Commentary", info.audio.single().title)
        assertEquals("SUBRIP", info.subtitles.single().title)
    }

    @Test
    fun `a container that lists its aliases shows the first`() {
        assertEquals("MOV", source(container = "mov,mp4,m4a,3gp,3g2,mj2").toMediaInfo().container)
        assertNull(source(container = "").toMediaInfo().container)
    }

    @Test
    fun `whole numbers lose their decimals`() {
        val numbers = MediaInfoNumbers(Locale.US)

        assertEquals("25 fps", numbers.frameRate(25f))
        assertEquals("59.94 fps", numbers.frameRate(59.94006f))
        assertEquals("29.97 fps", numbers.frameRate(29.97003f))
        assertEquals("96 kHz", numbers.sampleRate(96_000))
        assertEquals("22.05 kHz", numbers.sampleRate(22_050))
        assertEquals("11.025 kHz", numbers.sampleRate(11_025))
        assertEquals("120 fps", numbers.frameRate(120f))
        assertEquals("7", numbers.count(7))
        assertEquals("128 kbps", numbers.bitrate(128_000))
        assertEquals("8.0 Mbps", numbers.bitrate(8_000_000))
        assertEquals("3840×2160", numbers.resolution(3840, 2160))
    }

    @Test
    fun `a value that is not a measurement is left out`() {
        val numbers = MediaInfoNumbers(Locale.US)

        assertNull(numbers.bitrate(0))
        assertNull(numbers.bitrate(-1))
        assertNull(numbers.frameRate(0f))
        assertNull(numbers.frameRate(Float.NaN))
        assertNull(numbers.frameRate(Float.POSITIVE_INFINITY))
        // A time base in place of a rate, as variable frame rate files can report.
        assertNull(numbers.frameRate(1000f))
        assertNull(numbers.frameRate(90000f))
        assertNull(numbers.sampleRate(0))
        assertNull(numbers.bitDepth(0))
        assertNull(numbers.resolution(1920, null))
        assertNull(numbers.resolution(0, 0))
    }

    @Test
    fun `a stream with zeros where the numbers should be shows no details`() {
        val info =
            source(
                    stream(
                        MediaStreamType.VIDEO,
                        displayTitle = "1080p H264",
                        width = 0,
                        height = 0,
                        bitRate = 0,
                        frameRate = 0f,
                        bitDepth = 0,
                    ),
                    bitrate = 0,
                )
                .toMediaInfo(Locale.US)

        assertNull(info.bitrate)
        assertEquals(listOf(MediaInfoTrack("1080p H264", emptyList())), info.video)
    }

    @Test
    fun `numbers follow the locale's decimal separator`() {
        val numbers = MediaInfoNumbers(Locale.GERMANY)

        assertEquals("62,1 Mbps", numbers.bitrate(62_100_000))
        assertEquals("23,976 fps", numbers.frameRate(23.976f))
        assertEquals("44,1 kHz", numbers.sampleRate(44_100))
        assertEquals("100 fps", numbers.frameRate(100f))
        // Never grouped: "3.840" would read as a decimal.
        assertEquals("3840×2160", numbers.resolution(3840, 2160))
    }

    @Test
    fun `a locale with its own digits uses them for every number`() {
        val numbers = MediaInfoNumbers(Locale.forLanguageTag("ar-EG-u-nu-arab"))

        assertEquals("٢٥ fps", numbers.frameRate(25f))
        assertEquals("٣٨٤٠×٢١٦٠", numbers.resolution(3840, 2160))
        assertEquals("١٠-bit", numbers.bitDepth(10))
        assertEquals("٦٤٠ kbps", numbers.bitrate(640_000))
    }

    @Test
    fun `a download, which stores only its sidecar subtitles, is not a version to describe`() {
        val server = source(stream(MediaStreamType.VIDEO), stream(MediaStreamType.AUDIO))
        val download =
            source(stream(MediaStreamType.SUBTITLE, displayTitle = "German - SUBRIP - External"))
                .copy(type = FindroidSourceType.LOCAL)
        val audioOnly = source(stream(MediaStreamType.AUDIO))

        assertEquals(listOf(server), listOf(server, download).withMediaInfo())
        assertEquals(listOf(audioOnly), listOf(audioOnly).withMediaInfo())
        // Offline, the downloads are all there is.
        assertEquals(emptyList<FindroidSource>(), listOf(download).withMediaInfo())
        assertEquals(emptyList<FindroidSource>(), listOf(source()).withMediaInfo())
    }
}
