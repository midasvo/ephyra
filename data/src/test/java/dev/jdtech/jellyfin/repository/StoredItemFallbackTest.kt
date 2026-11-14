package dev.jdtech.jellyfin.repository

import android.app.Application
import android.content.Context
import android.provider.Settings
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidEpisodeDto
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidMovieDto
import dev.jdtech.jellyfin.models.FindroidSeasonDto
import dev.jdtech.jellyfin.models.FindroidShowDto
import dev.jdtech.jellyfin.models.FindroidSourceDto
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.FindroidUserDataDto
import dev.jdtech.jellyfin.player.DeviceProfileBuilder
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val showId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val seasonId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val episodeId = UUID.fromString("33333333-3333-3333-3333-333333333333")
    private val movieId = UUID.fromString("44444444-4444-4444-4444-444444444444")
    private val unknownId = UUID.fromString("55555555-5555-5555-5555-555555555555")

    private val show =
        FindroidShowDto(
            id = showId,
            serverId = "server",
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
        FindroidSeasonDto(
            id = seasonId,
            seriesId = showId,
            name = "Season 3",
            seriesName = "Bluey",
            overview = "",
            indexNumber = 3,
        )
    private val episode =
        FindroidEpisodeDto(
            id = episodeId,
            serverId = "server",
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
        FindroidMovieDto(
            id = movieId,
            serverId = "server",
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

    private fun localSource(itemId: UUID) =
        FindroidSourceDto(
            id = "source-$itemId",
            itemId = itemId,
            name = "Original",
            type = FindroidSourceType.LOCAL,
            path = "/storage/downloads/$itemId.mkv",
        )

    private val database =
        Proxy.newProxyInstance(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, args ->
            val id = args?.firstOrNull() as? UUID
            when (method.name) {
                "observeHasSources" -> flowOf(true)
                "getEpisodeOrNull" -> episode.takeIf { it.id == id }
                "getMovieOrNull" -> movie.takeIf { it.id == id }
                "getShowOrNull" -> show.takeIf { it.id == id }
                "getSeasonOrNull" -> season.takeIf { it.id == id }
                "getUserDataOrCreateNew" ->
                    FindroidUserDataDto(
                        userId = userId,
                        itemId = id!!,
                        played = false,
                        favorite = false,
                        playbackPositionTicks = 1_200_000_000L,
                    )
                "getSourcesForItems" ->
                    @Suppress("UNCHECKED_CAST")
                    (args!![0] as List<UUID>)
                        .filter { it == episodeId || it == movieId }
                        .map { localSource(it) }
                "getMediaStreamsForSources" -> emptyList<Any>()
                "getTrickplayInfo" -> null
                else -> throw NotImplementedError("ServerDatabaseDao.${method.name}")
            }
        } as ServerDatabaseDao

    private val repository: JellyfinRepository by lazy {
        val context = RuntimeEnvironment.getApplication()
        // The SDK names the device after it; Robolectric has none.
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "test")
        // No base URL: every request to the server fails, as with the server unreachable.
        val api = JellyfinApi(context).apply { userId = this@StoredItemFallbackTest.userId }
        JellyfinRepositoryImpl(
            context,
            api,
            database,
            AppPreferences(context.getSharedPreferences("test", Context.MODE_PRIVATE)),
            DeviceProfileBuilder(context),
        )
    }

    @Test
    fun `a downloaded episode comes from Room with its local file`() = runBlocking {
        val result = repository.getEpisode(episodeId)

        assertEquals("Obstacle Course", result.name)
        assertEquals(seasonId, result.seasonId)
        assertEquals(1_200_000_000L, result.playbackPositionTicks)
        assertEquals(listOf(FindroidSourceType.LOCAL), result.sources.map { it.type })
        assertEquals("/storage/downloads/$episodeId.mkv", result.sources.single().path)
    }

    @Test
    fun `a downloaded movie comes from Room with its local file`() = runBlocking {
        val result = repository.getMovie(movieId)

        assertEquals("Movie", result.name)
        assertEquals(listOf(FindroidSourceType.LOCAL), result.sources.map { it.type })
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
        assertTrue(repository.getItem(episodeId) is FindroidEpisode)
        assertTrue(repository.getItem(movieId) is FindroidMovie)
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
