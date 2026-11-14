package dev.jdtech.jellyfin.models

import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.AudioSpatialFormat
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.VideoRange
import org.jellyfin.sdk.model.api.VideoRangeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The server's transcode and subtitle URLs start with a slash, and the stored address of a server
 * under a path ends with one. Joined as plain strings they gave `/stable//videos/...`, which the
 * server answers with 404 (#444).
 */
class ServerUrlTest {
    private val itemId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    private fun repository(base: String) =
        Proxy.newProxyInstance(
            JellyfinRepository::class.java.classLoader,
            arrayOf(JellyfinRepository::class.java),
        ) { _, method, _ ->
            if (method.name == "getBaseUrl") base else throw NotImplementedError(method.name)
        } as JellyfinRepository

    private fun subtitle(deliveryUrl: String?) =
        MediaStream(
            videoRange = VideoRange.UNKNOWN,
            videoRangeType = VideoRangeType.UNKNOWN,
            audioSpatialFormat = AudioSpatialFormat.NONE,
            isInterlaced = false,
            isDefault = false,
            isForced = false,
            isHearingImpaired = false,
            isOriginal = false,
            type = MediaStreamType.SUBTITLE,
            index = 2,
            isExternal = true,
            deliveryUrl = deliveryUrl,
            isTextSubtitleStream = true,
            supportsExternalStream = true,
        )

    private fun source(transcodingUrl: String?, streams: List<MediaStream> = emptyList()) =
        MediaSourceInfo(
            protocol = MediaProtocol.FILE,
            id = "source",
            type = MediaSourceType.DEFAULT,
            isRemote = false,
            readAtNativeFramerate = false,
            ignoreDts = false,
            ignoreIndex = false,
            genPtsInput = false,
            supportsTranscoding = true,
            supportsDirectStream = true,
            supportsDirectPlay = true,
            isInfiniteStream = false,
            useMostCompatibleTranscodingProfile = false,
            requiresOpening = false,
            requiresClosing = false,
            requiresLooping = false,
            supportsProbing = true,
            mediaStreams = streams,
            transcodingUrl = transcodingUrl,
            transcodingSubProtocol = MediaStreamProtocol.HLS,
            hasSegments = false,
        )

    private fun transcodePath(base: String) = runBlocking {
        source("/videos/$itemId/master.m3u8?VideoCodec=h264")
            .toFindroidSource(repository(base), itemId)
            .path
    }

    private fun subtitlePath(
        base: String,
        deliveryUrl: String? = "/Videos/$itemId/source/Subtitles/2/0/Stream.srt",
    ) = subtitle(deliveryUrl).toFindroidMediaStream(repository(base)).path

    @Test
    fun `serverUrl joins with one slash whatever the two sides bring`() {
        assertEquals("https://host/jf/videos/1", serverUrl("https://host/jf/", "/videos/1"))
        assertEquals("https://host/jf/videos/1", serverUrl("https://host/jf", "/videos/1"))
        assertEquals("https://host/jf/videos/1", serverUrl("https://host/jf/", "videos/1"))
        assertEquals("https://host/jf/videos/1", serverUrl("https://host/jf", "videos/1"))
        assertEquals(
            "http://10.0.0.2:8096/videos/1",
            serverUrl("http://10.0.0.2:8096", "/videos/1"),
        )
    }

    @Test
    fun `serverUrl keeps the query of the path as it is`() {
        assertEquals(
            "https://host/jf/videos/1/master.m3u8?a=b//c&d=/e",
            serverUrl("https://host/jf/", "/videos/1/master.m3u8?a=b//c&d=/e"),
        )
    }

    @Test
    fun `transcode url on a server under a path has one slash after the path`() {
        // The address as the SDK's discovery stores it: with a trailing slash.
        assertEquals(
            "https://demo.jellyfin.org/stable/videos/$itemId/master.m3u8?VideoCodec=h264",
            transcodePath("https://demo.jellyfin.org/stable/"),
        )
    }

    @Test
    fun `transcode url on a server at the root of a host is unchanged`() {
        assertEquals(
            "https://jf.example/videos/$itemId/master.m3u8?VideoCodec=h264",
            transcodePath("https://jf.example"),
        )
    }

    @Test
    fun `subtitle url on a server under a path has one slash after the path`() {
        assertEquals(
            "https://demo.jellyfin.org/stable/Videos/$itemId/source/Subtitles/2/0/Stream.srt",
            subtitlePath("https://demo.jellyfin.org/stable/"),
        )
    }

    @Test
    fun `subtitle url on a server at the root of a host is unchanged`() {
        assertEquals(
            "https://jf.example/Videos/$itemId/source/Subtitles/2/0/Stream.srt",
            subtitlePath("https://jf.example"),
        )
    }

    @Test
    fun `a stream without a delivery url has no path`() {
        assertNull(subtitlePath("https://jf.example", deliveryUrl = null))
    }

    @Test
    fun `subtitle streams of a transcoded source get the same join`() {
        val mapped = runBlocking {
            source(
                    "/videos/$itemId/stream.mp4",
                    listOf(subtitle("/Videos/$itemId/source/Subtitles/2/0/Stream.srt")),
                )
                .toFindroidSource(repository("https://host/jellyfin/"), itemId)
        }

        assertEquals("https://host/jellyfin/videos/$itemId/stream.mp4", mapped.path)
        assertEquals(
            "https://host/jellyfin/Videos/$itemId/source/Subtitles/2/0/Stream.srt",
            mapped.mediaStreams.single().path,
        )
    }
}
