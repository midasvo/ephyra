package dev.jdtech.jellyfin.player.local.domain

import dev.jdtech.jellyfin.models.FindroidChapter
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidImages
import dev.jdtech.jellyfin.models.FindroidSeason
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistManagerShuffleTest {
    private val seriesId = UUID.randomUUID()
    private val calls = mutableListOf<String>()
    private val chapters = listOf(FindroidChapter(startPosition = 0L, name = "Opening"))

    @Test
    fun `shuffling a series queues episodes across regular seasons and starts from 0`() {
        val season0 = season(0)
        val season1 = season(1)
        val season2 = season(2)

        val epSpecial = episode(season0, 1)
        val ep1 = episode(season1, 1).copy(playbackPositionTicks = 50000000L)
        val ep2 = episode(season1, 2)
        val ep3 = episode(season2, 1)

        val showMap =
            mapOf(season0 to listOf(epSpecial), season1 to listOf(ep1, ep2), season2 to listOf(ep3))

        val manager = PlaylistManager(fakeRepository(showMap))
        val initialItem = runBlocking {
            manager.getInitialItem(
                itemId = seriesId,
                itemKind = BaseItemKind.SERIES,
                shuffle = true,
            )
        }

        assertNotNull(initialItem)
        assertEquals(0L, initialItem!!.playbackPosition)
        assertTrue(initialItem.itemId in setOf(ep1.id, ep2.id, ep3.id))

        // Total queue count should be 3 (excluding season 0 specials when regular seasons exist)
        val upcoming = manager.upcomingItems()
        assertEquals(2, upcoming.size)
        val allQueuedIds = upcoming.map { it.id }.toSet() + initialItem.itemId
        assertEquals(setOf(ep1.id, ep2.id, ep3.id), allQueuedIds)
    }

    @Test
    fun `shuffling a series asks for its episodes once, not per season`() {
        val seasons = List(24) { season(it) }
        val showMap = seasons.associateWith { season -> List(5) { episode(season, it + 1) } }

        val manager = PlaylistManager(fakeRepository(showMap))
        val initialItem = runBlocking {
            manager.getInitialItem(
                itemId = seriesId,
                itemKind = BaseItemKind.SERIES,
                shuffle = true,
            )
        }

        assertNotNull(initialItem)
        // One request for the episode list, not one per season.
        assertEquals(1, calls.count { it == "getEpisodes" })
        // Season 0 (Specials) is left out: 23 seasons of 5.
        assertEquals(115, 1 + manager.upcomingItems().size)
    }

    @Test
    fun `a shuffled series keeps going after the second episode`() {
        val seasons = List(4) { season(it + 1) }
        val showMap = seasons.associateWith { season -> List(5) { episode(season, it + 1) } }
        val manager = PlaylistManager(fakeRepository(showMap))

        runBlocking {
            val first =
                manager.getInitialItem(
                    itemId = seriesId,
                    itemKind = BaseItemKind.SERIES,
                    shuffle = true,
                )!!
            manager.setCurrentMediaItemIndex(first.itemId)
            val second = manager.getNextPlayerItem()
            assertNotNull("second", second)

            manager.setCurrentMediaItemIndex(second!!.itemId)
            assertNull("previous is already queued", manager.getPreviousPlayerItem())
            val third = manager.getNextPlayerItem()
            assertNotNull("third", third)
        }
    }

    @Test
    fun `jumping to a queued episode of a series shuffle gets its chapters`() {
        val seasons = List(3) { season(it + 1) }
        val showMap = seasons.associateWith { season -> List(4) { episode(season, it + 1) } }
        val manager = PlaylistManager(fakeRepository(showMap))

        runBlocking {
            manager.getInitialItem(
                itemId = seriesId,
                itemKind = BaseItemKind.SERIES,
                shuffle = true,
            )
            val later = manager.upcomingItems().last()

            val jumped = manager.jumpTo(later.id)!!

            assertEquals(1, jumped.chapters.size)
        }
    }

    @Test
    fun `a shuffled episode is fetched in full once, however often it is converted`() {
        val seasons = List(2) { season(it + 1) }
        val showMap = seasons.associateWith { season -> List(4) { episode(season, it + 1) } }
        val manager = PlaylistManager(fakeRepository(showMap))

        runBlocking {
            val first =
                manager.getInitialItem(
                    itemId = seriesId,
                    itemKind = BaseItemKind.SERIES,
                    shuffle = true,
                )!!
            manager.setCurrentMediaItemIndex(first.itemId)
            val second = manager.getNextPlayerItem()!!
            // A quality or audio switch rebuilds the item that is playing.
            val rebuilt = manager.recreatePlayerItem(second)

            assertEquals(1, rebuilt.chapters.size)
            // The first item and the second, each once.
            assertEquals(2, calls.count { it == "getEpisode" })
        }
    }

    @Test
    fun `a series with only specials shuffles the specials`() {
        val specials = season(0)
        val showMap = mapOf(specials to List(3) { episode(specials, it + 1) })

        val manager = PlaylistManager(fakeRepository(showMap))
        val initialItem = runBlocking {
            manager.getInitialItem(
                itemId = seriesId,
                itemKind = BaseItemKind.SERIES,
                shuffle = true,
            )
        }

        assertNotNull(initialItem)
        assertEquals(3, 1 + manager.upcomingItems().size)
    }

    @Test
    fun `shuffling a season queues only its episodes and starts from 0`() {
        val season1 = season(1)
        val season2 = season(2)
        val inSeason =
            List(10) { episode(season1, it + 1) }.map { it.copy(playbackPositionTicks = 50000000L) }
        val showMap = mapOf(season1 to inSeason, season2 to listOf(episode(season2, 1)))

        val manager = PlaylistManager(fakeRepository(showMap)).apply { random = Random(638) }
        val initialItem = runBlocking {
            manager.getInitialItem(
                itemId = season1.id,
                itemKind = BaseItemKind.SEASON,
                shuffle = true,
            )
        }

        assertNotNull(initialItem)
        assertEquals(0L, initialItem!!.playbackPosition)
        val queued = listOf(initialItem.itemId) + manager.upcomingItems().map { it.id }
        assertEquals(inSeason.map { it.id }.toSet(), queued.toSet())
        assertEquals(inSeason.size, queued.size)
        // The seeded order differs from the season's own, so the queue really is shuffled.
        assertNotEquals(inSeason.map { it.id }, queued)
    }

    @Test
    fun `a season without shuffle plays in order from its first episode`() {
        val season1 = season(1)
        val inSeason = List(10) { episode(season1, it + 1) }
        val manager = PlaylistManager(fakeRepository(mapOf(season1 to inSeason)))

        val initialItem = runBlocking {
            manager.getInitialItem(itemId = season1.id, itemKind = BaseItemKind.SEASON)
        }

        val queued = listOf(initialItem!!.itemId) + manager.upcomingItems().map { it.id }
        assertEquals(inSeason.map { it.id }, queued)
    }

    private fun fakeRepository(show: Map<FindroidSeason, List<FindroidEpisode>>) =
        Proxy.newProxyInstance(
            JellyfinRepository::class.java.classLoader,
            arrayOf(JellyfinRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getSeasons" -> show.keys.toList()
                "getSeason" -> show.keys.first { it.id == args[0] }
                "getEpisodes" -> {
                    calls += "getEpisodes"
                    if (args[1] == null)
                        show.values.flatten().map { it.copy(chapters = emptyList()) }
                    else show.entries.first { it.key.id == args[1] }.value
                }
                "getEpisode" -> {
                    calls += "getEpisode"
                    show.values.flatten().first { it.id == args[0] }.copy(chapters = chapters)
                }
                "getPlaybackSources" -> listOf(source())
                else -> error("Unexpected call to ${method.name}")
            }
        } as JellyfinRepository

    private fun season(number: Int) =
        FindroidSeason(
            id = UUID.randomUUID(),
            name = "Season $number",
            seriesId = seriesId,
            seriesName = "Show",
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            indexNumber = number,
            episodes = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            unplayedItemCount = null,
            images = FindroidImages(),
        )

    private fun episode(season: FindroidSeason, number: Int) =
        FindroidEpisode(
            id = UUID.randomUUID(),
            name = "Episode $number",
            originalTitle = null,
            overview = "",
            indexNumber = number,
            indexNumberEnd = null,
            parentIndexNumber = season.indexNumber,
            sources = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            runtimeTicks = 100000000L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            seriesId = seriesId,
            seriesName = "Show",
            seasonId = season.id,
            seasonName = season.name,
            communityRating = null,
            people = emptyList(),
            missing = false,
            images = FindroidImages(),
            chapters = emptyList(),
            trickplayInfo = null,
        )

    private fun source() =
        FindroidSource(
            id = "source",
            name = "Source",
            type = FindroidSourceType.REMOTE,
            path = "http://server/video",
            size = 0L,
            mediaStreams = emptyList(),
        )
}
