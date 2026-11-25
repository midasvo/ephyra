package dev.jdtech.jellyfin.repository

import android.app.Application
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.EpisodeEntity
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemSourceEntity
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.MovieEntity
import dev.jdtech.jellyfin.models.SeasonEntity
import dev.jdtech.jellyfin.models.ShowEntity
import dev.jdtech.jellyfin.models.UserDataEntity
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toMediaSourceId
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.testing.FakeResumeThresholdsStore
import dev.jdtech.jellyfin.testing.asItemId
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#281): the online repository's item getters give the copy in Room when the server fails, so a
 * downloaded episode opens and plays from its file without the server. The server here always
 * fails: the API client has no address.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StoredItemFallbackTest {
    private val userId = "00000000-0000-0000-0000-000000000001".toUserId()
    private val showId = "11111111-1111-1111-1111-111111111111".toItemId()
    private val seasonId = "22222222-2222-2222-2222-222222222222".toItemId()
    private val episodeId = "33333333-3333-3333-3333-333333333333".toItemId()
    private val movieId = "44444444-4444-4444-4444-444444444444".toItemId()
    private val unknownId = "55555555-5555-5555-5555-555555555555".toItemId()

    private val show =
        ShowEntity(
            id = showId,
            serverId = "server".toServerId(),
            name = "Bluey",
            originalTitle = null,
            overview = "",
            runtimeTicks = 0L,
            communityRating = null,
            officialRating = null,
            status = "Continuing",
            productionYear = 2018,
            endDate = null,
        )
    private val season =
        SeasonEntity(
            id = seasonId,
            seriesId = showId,
            name = "Season 3",
            seriesName = "Bluey",
            overview = "",
            indexNumber = 3,
        )
    private val episode =
        EpisodeEntity(
            id = episodeId,
            serverId = "server".toServerId(),
            seasonId = seasonId,
            seriesId = showId,
            name = "Obstacle Course",
            seriesName = "Bluey",
            overview = "",
            indexNumber = 3,
            indexNumberEnd = null,
            parentIndexNumber = 3,
            runtimeTicks = 4_200_000_000L,
            premiereDate = null,
            communityRating = null,
            chapters = null,
        )
    private val movie =
        MovieEntity(
            id = movieId,
            serverId = "server".toServerId(),
            name = "Movie",
            originalTitle = null,
            overview = "",
            runtimeTicks = 0L,
            premiereDate = null,
            communityRating = null,
            officialRating = null,
            status = "",
            productionYear = null,
            endDate = null,
            chapters = null,
        )

    private fun localSource(itemId: ItemId) =
        ItemSourceEntity(
            id = "source-${itemId.asString()}".toMediaSourceId(),
            itemId = itemId,
            name = "Original",
            type = ItemSourceType.LOCAL,
            path = "/storage/downloads/${itemId.asString()}.mkv",
        )

    private val database =
        fakeProxy(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, args ->
            // A value class parameter reaches the proxy as its String (see testing/ProxyCalls.kt).
            val id = (args?.firstOrNull() as? String)?.asItemId()
            when (method.kotlinName) {
                "observeHasSources" -> flowOf(true)
                "getEpisodeOfServer" -> episode.takeIf { it.id == id }
                "getMovieOfServer" -> movie.takeIf { it.id == id }
                "getShowOfServer" -> show.takeIf { it.id == id }
                "getSeasonOfServer" -> season.takeIf { it.id == id }
                "getUserDataOrCreateNew" ->
                    UserDataEntity(
                        userId = userId,
                        itemId = id!!,
                        played = false,
                        favorite = false,
                        playbackPositionTicks = 1_200_000_000L,
                    )
                "getSourcesForItems",
                "getSourcesForItemsOfServer" ->
                    @Suppress("UNCHECKED_CAST")
                    (args!![0] as List<ItemId>)
                        .filter { it == episodeId || it == movieId }
                        .map { localSource(it) }
                "getMediaStreamsForSources" -> emptyList<Any>()
                "getTrickplayInfo" -> null
                else -> throw NotImplementedError("ServerDatabaseDao.${method.kotlinName}")
            }
        }
            as ServerDatabaseDao

    private val repository: MediaRepositoryImpl by lazy {
        val context = RuntimeEnvironment.getApplication()
        // Every request to the server fails, as with the server unreachable.
        roomMediaRepository(
            context,
            UnansweredServer(userId),
            database,
            currentServer = { "server".toServerId() },
            nextUpMaxDays = { 0 },
            resumeThresholds = FakeResumeThresholdsStore(),
        )
    }

    @Test
    fun `a downloaded episode comes from Room with its local file`() = runBlocking {
        val result = repository.getEpisode(episodeId)

        assertEquals("Obstacle Course", result.name)
        assertEquals(seasonId, result.seasonId)
        assertEquals(1_200_000_000L, result.playbackPositionTicks)
        assertEquals(listOf(ItemSourceType.LOCAL), result.sources.map { it.type })
        assertEquals("/storage/downloads/${episodeId.asString()}.mkv", result.sources.single().path)
    }

    @Test
    fun `a downloaded movie comes from Room with its local file`() = runBlocking {
        val result = repository.getMovie(movieId)

        assertEquals("Movie", result.name)
        assertEquals(listOf(ItemSourceType.LOCAL), result.sources.map { it.type })
    }

    @Test
    fun `the show and season of a downloaded episode come from Room`() = runBlocking {
        assertEquals("Bluey", repository.getShow(showId).name)
        val result = repository.getSeason(seasonId)
        assertEquals("Season 3", result.name)
        assertEquals(showId, result.seriesId)
    }

    @Test
    fun `getItem finds every kind in Room`() = runBlocking {
        assertTrue(repository.getItem(episodeId) is Episode)
        assertTrue(repository.getItem(movieId) is Movie)
        assertEquals(showId, repository.getItem(showId)?.id)
        assertEquals(seasonId, repository.getItem(seasonId)?.id)
    }

    @Test
    fun `an item that is not in Room still fails`() = runBlocking {
        for (get in
            listOf<suspend () -> Any?>(
                { repository.getEpisode(unknownId) },
                { repository.getMovie(unknownId) },
                { repository.getShow(unknownId) },
                { repository.getSeason(unknownId) },
                { repository.getItem(unknownId) },
            )) {
            try {
                get()
                fail("expected the server's error")
            } catch (_: Exception) {}
        }
    }
}
