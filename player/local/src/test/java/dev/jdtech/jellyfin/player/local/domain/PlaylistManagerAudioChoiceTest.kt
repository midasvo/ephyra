package dev.jdtech.jellyfin.player.local.domain

import androidx.media3.common.C
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidImages
import dev.jdtech.jellyfin.models.FindroidMediaStream
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.SeriesTrackPreferenceDto
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * CE (#580): a transcode carries one audio track, so the user's track goes to the server with the
 * request. The fake server here answers a request that names an audio stream with a stream for it.
 */
class PlaylistManagerAudioChoiceTest {
    private val episode = episode()

    /** The audio stream index of every playback request, null for one without. */
    private val requests = mutableListOf<Int?>()

    @Test
    fun `a transcode is requested again with the audio track saved for the series`() {
        val manager = PlaylistManager(repository(transcodes = true, saved = dutch()))

        val item = runBlocking { manager.getInitialItem(episode.id, BaseItemKind.EPISODE) }

        assertEquals(listOf(null, DUTCH), requests)
        assertEquals(path(DUTCH), item?.mediaSourceUri)
    }

    @Test
    fun `a saved track that is the server's default needs no second request`() {
        val saved = dutch().copy(language = "eng", title = "English")
        val manager = PlaylistManager(repository(transcodes = true, saved = saved))

        val item = runBlocking { manager.getInitialItem(episode.id, BaseItemKind.EPISODE) }

        assertEquals(listOf<Int?>(null), requests)
        assertEquals(path(null), item?.mediaSourceUri)
    }

    @Test
    fun `a source that plays directly is not requested again`() {
        val manager = PlaylistManager(repository(transcodes = false, saved = dutch()))

        runBlocking { manager.getInitialItem(episode.id, BaseItemKind.EPISODE) }

        assertEquals(listOf<Int?>(null), requests)
    }

    @Test
    fun `without a saved track the server's default plays`() {
        val manager = PlaylistManager(repository(transcodes = true, saved = null))

        runBlocking { manager.getInitialItem(episode.id, BaseItemKind.EPISODE) }

        assertEquals(listOf<Int?>(null), requests)
    }

    @Test
    fun `a saved choice for subtitles is not an audio choice`() {
        val saved = dutch().copy(trackType = C.TRACK_TYPE_TEXT)
        val manager = PlaylistManager(repository(transcodes = true, saved = saved))

        runBlocking { manager.getInitialItem(episode.id, BaseItemKind.EPISODE) }

        assertEquals(listOf<Int?>(null), requests)
    }

    @Test
    fun `a quality switch carries the playing track, and keeps it from one transcode to the next`() {
        val manager = PlaylistManager(repository(transcodes = true, saved = null))
        val item = runBlocking { manager.getInitialItem(episode.id, BaseItemKind.EPISODE) }!!
        requests.clear()

        // The stream that plays directly names its tracks.
        val first = runBlocking {
            manager.recreatePlayerItem(item, StoredChoice("nl", "Nederlands", 0))
        }
        // The transcode's single track has no name: the track of the last request stays.
        val second = runBlocking { manager.recreatePlayerItem(first, null) }

        assertEquals(listOf(null, DUTCH, null, DUTCH), requests)
        assertEquals(path(DUTCH), first.mediaSourceUri)
        assertEquals(path(DUTCH), second.mediaSourceUri)
    }

    @Test
    fun `a transcode lists the file's audio tracks and names the one it carries`() {
        val manager = PlaylistManager(repository(transcodes = true, saved = dutch()))

        val item = runBlocking { manager.getInitialItem(episode.id, BaseItemKind.EPISODE) }!!

        assertEquals(listOf(ENGLISH, DUTCH), item.audioTracks.map { it.index })
        assertEquals(listOf("English", "Nederlands"), item.audioTracks.map { it.title })
        assertEquals(DUTCH, item.audioStreamIndex)
    }

    @Test
    fun `without a choice the transcode carries the server's default track`() {
        val manager = PlaylistManager(repository(transcodes = true, saved = null))

        val item = runBlocking { manager.getInitialItem(episode.id, BaseItemKind.EPISODE) }!!

        assertEquals(ENGLISH, item.audioStreamIndex)
    }

    @Test
    fun `a stream that plays directly lists no server audio tracks`() {
        val manager = PlaylistManager(repository(transcodes = false, saved = dutch()))

        val item = runBlocking { manager.getInitialItem(episode.id, BaseItemKind.EPISODE) }!!

        assertEquals(emptyList<Int>(), item.audioTracks.map { it.index })
        assertNull(item.audioStreamIndex)
    }

    @Test
    fun `a track picked in the audio dialog of a transcode is requested by its index`() {
        // #596: it wins over the choice saved for the series, and stays on the next switch.
        val saved = dutch().copy(language = "eng", title = "English")
        val manager = PlaylistManager(repository(transcodes = true, saved = saved))
        val item = runBlocking { manager.getInitialItem(episode.id, BaseItemKind.EPISODE) }!!
        requests.clear()

        val picked = runBlocking { manager.recreatePlayerItem(item, audioStreamIndex = DUTCH) }

        assertEquals(listOf(null, DUTCH), requests)
        assertEquals(path(DUTCH), picked.mediaSourceUri)
        assertEquals(DUTCH, picked.audioStreamIndex)
    }

    @Test
    fun `a picked index the file does not have is ignored`() {
        val manager = PlaylistManager(repository(transcodes = true, saved = null))
        val item = runBlocking { manager.getInitialItem(episode.id, BaseItemKind.EPISODE) }!!
        requests.clear()

        val picked = runBlocking { manager.recreatePlayerItem(item, audioStreamIndex = 9) }

        assertEquals(listOf<Int?>(null), requests)
        assertEquals(ENGLISH, picked.audioStreamIndex)
    }

    @Test
    fun `when the request for the picked track fails the item names the track that plays`() {
        val manager =
            PlaylistManager(repository(transcodes = true, saved = null, failWithAudio = true))
        val item = runBlocking { manager.getInitialItem(episode.id, BaseItemKind.EPISODE) }!!

        val picked = runBlocking { manager.recreatePlayerItem(item, audioStreamIndex = DUTCH) }

        assertEquals(path(null), picked.mediaSourceUri)
        assertEquals(ENGLISH, picked.audioStreamIndex)
    }

    @Test
    fun `a downloaded copy plays as before, whatever the server would transcode`() {
        val manager =
            PlaylistManager(repository(transcodes = true, saved = dutch(), downloaded = true))

        val item = runBlocking { manager.getInitialItem(episode.id, BaseItemKind.EPISODE) }

        assertEquals(listOf<Int?>(null), requests)
        assertEquals(DOWNLOAD, item?.mediaSourceUri)
        // #596: its audio dialog lists the player's tracks, as before.
        assertEquals(0, item?.audioTracks?.size)
    }

    @Test
    fun `when the second request fails the default track plays`() {
        val manager =
            PlaylistManager(repository(transcodes = true, saved = dutch(), failWithAudio = true))

        val item = runBlocking { manager.getInitialItem(episode.id, BaseItemKind.EPISODE) }

        assertEquals(path(null), item?.mediaSourceUri)
    }

    private fun repository(
        transcodes: Boolean,
        saved: SeriesTrackPreferenceDto?,
        failWithAudio: Boolean = false,
        downloaded: Boolean = false,
    ) =
        Proxy.newProxyInstance(
            JellyfinRepository::class.java.classLoader,
            arrayOf(JellyfinRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getEpisode" -> episode
                "getEpisodes" -> listOf(episode)
                "getPlaybackSources" -> {
                    val audio = args[2] as Int?
                    requests += audio
                    if (audio != null) {
                        assertEquals("the server needs the source's id", SOURCE, args[1])
                        if (failWithAudio) error("server error")
                    }
                    listOf(source(transcodes, audio)) +
                        if (downloaded) listOf(download()) else emptyList()
                }
                "getSeriesTrackPreferences" -> {
                    assertEquals(episode.seriesId, args[0])
                    listOfNotNull(saved)
                }
                else -> error("Unexpected call to ${method.name}")
            }
        } as JellyfinRepository

    private fun dutch() =
        SeriesTrackPreferenceDto(
            userId = UUID.randomUUID(),
            seriesId = episode.seriesId,
            trackType = C.TRACK_TYPE_AUDIO,
            disabled = false,
            // As ExoPlayer stores it; the server says "nld" or, from the file's tag, "dut".
            language = "nld",
            title = "Nederlands",
            positionInLanguage = 0,
        )

    private fun source(transcodes: Boolean, audio: Int?) =
        FindroidSource(
            id = SOURCE,
            name = "Source",
            type = FindroidSourceType.REMOTE,
            path = path(audio),
            size = 0L,
            mediaStreams =
                listOf(audio(ENGLISH, "eng", "English"), audio(DUTCH, "dut", "Nederlands")),
            transcoded = transcodes,
            defaultAudioStreamIndex = audio ?: ENGLISH,
        )

    private fun download() =
        FindroidSource(
            id = SOURCE,
            name = "Source",
            type = FindroidSourceType.LOCAL,
            path = DOWNLOAD,
            size = 0L,
            mediaStreams = emptyList(),
            downloadId = 1L,
        )

    private fun audio(index: Int, language: String, title: String) =
        FindroidMediaStream(
            title = title,
            displayTitle = title,
            language = language,
            type = MediaStreamType.AUDIO,
            codec = "aac",
            profile = null,
            isExternal = false,
            path = null,
            channelLayout = null,
            videoRangeType = null,
            height = null,
            width = null,
            videoDoViTitle = null,
            index = index,
        )

    private fun episode() =
        FindroidEpisode(
            id = UUID.randomUUID(),
            name = "Episode 1",
            originalTitle = null,
            overview = "",
            indexNumber = 1,
            indexNumberEnd = null,
            parentIndexNumber = 1,
            sources = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            runtimeTicks = 0L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            seriesId = UUID.randomUUID(),
            seriesName = "Show",
            seasonId = UUID.randomUUID(),
            seasonName = "Season 1",
            communityRating = null,
            people = emptyList(),
            missing = false,
            images = FindroidImages(),
            chapters = emptyList(),
            trickplayInfo = null,
        )

    private companion object {
        const val SOURCE = "source"
        const val ENGLISH = 1
        const val DUTCH = 2
        const val DOWNLOAD = "/downloads/Show/S01/S01E01 - Episode.mkv"

        fun path(audio: Int?) = "http://server/master.m3u8?AudioStreamIndex=${audio ?: ENGLISH}"
    }
}
