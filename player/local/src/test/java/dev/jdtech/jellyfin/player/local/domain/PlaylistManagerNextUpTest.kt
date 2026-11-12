package dev.jdtech.jellyfin.player.local.domain

import dev.jdtech.jellyfin.models.FindroidChapter
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidImages
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.FindroidTrickplayInfo
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * CE (#594): the episode a show's Play starts is the next-up one, which the server sends without
 * trickplay and chapters. The player gets the episode as its own page has it.
 */
class PlaylistManagerNextUpTest {
    private val seriesId = UUID.randomUUID()
    private val seasonId = UUID.randomUUID()
    private val episodeId = UUID.randomUUID()
    private val calls = mutableListOf<String>()

    private val nextUp = episode(trickplay = null, chapters = emptyList())
    private val full =
        episode(
            trickplay =
                mapOf(SOURCE to FindroidTrickplayInfo(320, 180, 10, 10, 72, 10_000, 100_000)),
            chapters = listOf(FindroidChapter(0L, "Opening"), FindroidChapter(60_000L, "Part One")),
        )

    @Test
    fun `the show's next-up episode starts with its trickplay and chapters`() {
        val manager = PlaylistManager(fakeRepository { full })

        val item = runBlocking { manager.getInitialItem(seriesId, BaseItemKind.SERIES) }

        assertNotNull(item)
        assertEquals(episodeId, item!!.itemId)
        assertEquals(320, item.trickplayInfo?.width)
        assertEquals(listOf("Opening", "Part One"), item.chapters.map { it.name })
        // The sources fetched next to the episode are the ones used.
        assertEquals(1, calls.count { it == "getPlaybackSources" })
    }

    @Test
    fun `the next-up episode still plays when the full episode cannot be fetched`() {
        val manager = PlaylistManager(fakeRepository { error("server error") })

        val item = runBlocking { manager.getInitialItem(seriesId, BaseItemKind.SERIES) }

        assertNotNull(item)
        assertEquals(episodeId, item!!.itemId)
        assertNull(item.trickplayInfo)
    }

    private fun fakeRepository(getEpisode: () -> FindroidEpisode) =
        Proxy.newProxyInstance(
            JellyfinRepository::class.java.classLoader,
            arrayOf(JellyfinRepository::class.java),
        ) { _, method, _ ->
            calls.add(method.name)
            when (method.name) {
                "getNextUp" -> listOf(nextUp)
                "getEpisode" -> getEpisode()
                "getPlaybackSources" -> listOf(source())
                else -> error("Unexpected call to ${method.name}")
            }
        } as JellyfinRepository

    private fun episode(
        trickplay: Map<String, FindroidTrickplayInfo>?,
        chapters: List<FindroidChapter>,
    ) =
        FindroidEpisode(
            id = episodeId,
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
            seriesId = seriesId,
            seriesName = "Show",
            seasonId = seasonId,
            seasonName = "Season 1",
            communityRating = null,
            people = emptyList(),
            missing = false,
            images = FindroidImages(),
            chapters = chapters,
            trickplayInfo = trickplay,
        )

    private fun source() =
        FindroidSource(
            id = SOURCE,
            name = "Source",
            type = FindroidSourceType.REMOTE,
            path = "http://server/video",
            size = 0L,
            mediaStreams = emptyList(),
        )

    private companion object {
        const val SOURCE = "source"
    }
}
