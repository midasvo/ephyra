package dev.jdtech.jellyfin.player.cast

import dev.jdtech.jellyfin.player.cast.models.CastMediaItem
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import java.util.UUID
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [castTracks]: the tracks the receiver and the track dialogs get for a media source
 * (#626). The source here has a video stream, English and Dutch audio, and three subtitles: one the
 * server delivers as a file, one it burns in or leaves in the stream (no URL), and one whose URL
 * points away from the server.
 */
class CastTracksTest {

    private val base = "https://jellyfin.example"

    private val source =
        source(
            stream(0, MediaStreamType.VIDEO),
            stream(1, MediaStreamType.AUDIO, language = "eng", title = "English 5.1"),
            stream(2, MediaStreamType.AUDIO, language = "nld", displayTitle = "Dutch - AAC"),
            stream(3, MediaStreamType.SUBTITLE, language = "eng", deliveryUrl = "/Videos/1/3.vtt"),
            stream(4, MediaStreamType.SUBTITLE, language = "nld", title = "Signs"),
            stream(
                5,
                MediaStreamType.SUBTITLE,
                language = "deu",
                deliveryUrl = "https://other.example/5.vtt",
            ),
        )

    @Test
    fun `subtitle track ids are the stream index plus 100, audio track ids the stream index`() {
        val tracks = castTracks(source, base, audioStreamIndex = null, subtitleTrackId = null)

        assertEquals(listOf(103, 104, 105), tracks.subtitles.map { it.id })
        assertEquals(listOf(1, 2), tracks.audio.map { it.id })
        assertEquals(listOf("eng", "nld", "deu"), tracks.subtitles.map { it.language })
        assertEquals(listOf("English 5.1", "Dutch - AAC"), tracks.audio.map { it.label })
    }

    @Test
    fun `a subtitle is supported only when the server delivers it as a file on the server`() {
        val tracks = castTracks(source, base, audioStreamIndex = null, subtitleTrackId = null)

        assertEquals(listOf(true, false, false), tracks.subtitles.map { it.supported })
        assertEquals(
            listOf(
                ReceiverTextTrack(
                    id = 103L,
                    url = "$base/Videos/1/3.vtt",
                    language = "eng",
                    name = null,
                )
            ),
            tracks.receiverTracks,
        )
        assertTrue(tracks.audio.all { it.supported })
    }

    @Test
    fun `the chosen audio stream is the one that plays`() {
        val tracks = castTracks(source, base, audioStreamIndex = 2, subtitleTrackId = null)

        assertEquals(listOf(false, true), tracks.audio.map { it.selected })
    }

    @Test
    fun `without a choice the source's default audio stream plays`() {
        val tracks =
            castTracks(
                source.copy(defaultAudioStreamIndex = 2),
                base,
                audioStreamIndex = null,
                subtitleTrackId = null,
            )

        assertEquals(listOf(false, true), tracks.audio.map { it.selected })
    }

    @Test
    fun `without a choice or a default the first audio stream plays`() {
        val tracks = castTracks(source, base, audioStreamIndex = null, subtitleTrackId = null)

        assertEquals(listOf(true, false), tracks.audio.map { it.selected })
    }

    @Test
    fun `the subtitle that shows is selected and active`() {
        val tracks = castTracks(source, base, audioStreamIndex = null, subtitleTrackId = 103)

        assertEquals(listOf(true, false, false), tracks.subtitles.map { it.selected })
        assertEquals(listOf(103L), tracks.activeTrackIds)
    }

    @Test
    fun `no subtitle shows without a subtitle track id`() {
        val tracks = castTracks(source, base, audioStreamIndex = null, subtitleTrackId = null)

        assertTrue(tracks.subtitles.none { it.selected })
        assertEquals(emptyList<Long>(), tracks.activeTrackIds)
    }

    @Test
    fun `a subtitle the receiver cannot get is neither selected nor active`() {
        // The receiver was not told about these tracks; naming one would fail the whole load.
        listOf(104, 105).forEach { trackId ->
            val tracks = castTracks(source, base, audioStreamIndex = null, subtitleTrackId = trackId)

            assertTrue(tracks.subtitles.none { it.selected })
            assertEquals(emptyList<Long>(), tracks.activeTrackIds)
        }
    }

    @Test
    fun `the server's subtitle pick becomes a track id`() {
        assertEquals(103, defaultSubtitleTrackId(source.copy(defaultSubtitleStreamIndex = 3)))
        assertNull(defaultSubtitleTrackId(source.copy(defaultSubtitleStreamIndex = -1)))
        assertNull(defaultSubtitleTrackId(source))
    }

    @Test
    fun `a track is named by its title, else by the server's display title`() {
        val tracks = castTracks(source, base, audioStreamIndex = null, subtitleTrackId = null)

        assertEquals("Signs", tracks.subtitles[1].label)
        assertEquals("Dutch - AAC", tracks.audio[1].label)
        assertNull(tracks.subtitles[0].label)
    }

    @Test
    fun `a source without streams has no tracks`() {
        val tracks = castTracks(source(), base, audioStreamIndex = null, subtitleTrackId = null)

        assertTrue(tracks.subtitles.isEmpty())
        assertTrue(tracks.audio.isEmpty())
        assertTrue(tracks.receiverTracks.isEmpty())
    }

    @Test
    fun `picking a subtitle selects that one alone`() {
        val tracks = castTracks(source, base, audioStreamIndex = null, subtitleTrackId = 103)
        val item = castItem(tracks)

        assertEquals(
            listOf(false, false, false),
            item.withSubtitle(emptyList()).subtitleTracks.map { it.selected },
        )
        assertEquals(
            listOf(false, true, false),
            item.withSubtitle(listOf(104L)).subtitleTracks.map { it.selected },
        )
        // The audio tracks are left as they were.
        assertEquals(tracks.audio, item.withSubtitle(listOf(104L)).audioTracks)
    }

    @Test
    fun `picking an audio track selects that one alone`() {
        val tracks = castTracks(source, base, audioStreamIndex = 1, subtitleTrackId = 103)
        val item = castItem(tracks).withAudio(2)

        assertEquals(listOf(false, true), item.audioTracks.map { it.selected })
        assertEquals(tracks.subtitles, item.subtitleTracks)
    }

    private fun castItem(tracks: CastTracks) =
        CastMediaItem(
            item =
                PlayerItem(
                    name = "Movie",
                    itemId = UUID.randomUUID(),
                    mediaSourceId = "source",
                    playbackPosition = 0L,
                ),
            subtitleTracks = tracks.subtitles,
            audioTracks = tracks.audio,
        )

    private fun stream(
        index: Int,
        type: MediaStreamType,
        language: String? = null,
        title: String? = null,
        displayTitle: String? = null,
        deliveryUrl: String? = null,
    ) =
        MediaStream(
            language = language,
            title = title,
            displayTitle = displayTitle,
            deliveryUrl = deliveryUrl,
            videoRange = VideoRange.UNKNOWN,
            videoRangeType = VideoRangeType.UNKNOWN,
            audioSpatialFormat = AudioSpatialFormat.NONE,
            isInterlaced = false,
            isDefault = false,
            isForced = false,
            isHearingImpaired = false,
            isOriginal = false,
            type = type,
            index = index,
            isExternal = false,
            isTextSubtitleStream = type == MediaStreamType.SUBTITLE,
            supportsExternalStream = false,
        )

    private fun source(vararg streams: MediaStream) =
        MediaSourceInfo(
            protocol = MediaProtocol.FILE,
            encoderProtocol = MediaProtocol.FILE,
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
            mediaStreams = streams.toList(),
            transcodingSubProtocol = MediaStreamProtocol.HTTP,
            hasSegments = false,
        )
}
