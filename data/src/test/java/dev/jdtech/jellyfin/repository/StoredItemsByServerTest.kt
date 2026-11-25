package dev.jdtech.jellyfin.repository

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.jdtech.jellyfin.database.ServerDatabase
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.EpisodeEntity
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemSourceEntity
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.MovieEntity
import dev.jdtech.jellyfin.models.PlaybackTarget
import dev.jdtech.jellyfin.models.PlayerEngineId
import dev.jdtech.jellyfin.models.SeasonEntity
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.ShowEntity
import dev.jdtech.jellyfin.models.UserDataEntity
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.models.getLocalSourcesForItems
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toMediaSourceId
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.testing.FakeResumeThresholdsStore
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#718): the copies in Room serve the server that stored them, not another server with the same
 * item ids (Jellyfin derives ids from the media's paths, so a server reinstalled over the same
 * folders has them too). The server here always fails: the API client has no address, so every
 * getter falls back to Room or fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StoredItemsByServerTest {
    private val userId = "00000000-0000-0000-0000-000000000001".toUserId()
    private val movieId = "00000000-0000-0000-0000-000000000040".toItemId()
    private val showId = "00000000-0000-0000-0000-000000000020".toItemId()
    private val seasonId = "00000000-0000-0000-0000-000000000021".toItemId()
    private val episodeId = "00000000-0000-0000-0000-000000000022".toItemId()
    private val otherSeasonId = "00000000-0000-0000-0000-000000000031".toItemId()
    private val otherEpisodeId = "00000000-0000-0000-0000-000000000032".toItemId()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val room =
        Room.inMemoryDatabaseBuilder(context, ServerDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val database: ServerDatabaseDao = room.getServerDatabaseDao()
    private var currentServerId: ServerId? = null

    private val repository: MediaRepositoryImpl by lazy {
        roomMediaRepository(
            context,
            UnansweredServer(userId),
            database,
            currentServer = { currentServerId },
            nextUpMaxDays = { 0 },
            resumeThresholds = FakeResumeThresholdsStore(),
        )
    }

    @After fun close() = room.close()

    private fun current(serverId: String) {
        currentServerId = serverId.toServerId()
    }

    private suspend fun storeMovie(serverId: String) {
        database.insertMovie(
            MovieEntity(
                id = movieId,
                serverId = serverId.toServerId(),
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
        )
        database.insertSource(source(movieId))
    }

    /** What the downloader stores for an episode: the show and season as it finds them. */
    private suspend fun storeEpisode(
        serverId: String,
        season: ItemId,
        episode: ItemId,
        index: Int,
    ) {
        database.insertShow(
            ShowEntity(
                id = showId,
                serverId = serverId.toServerId(),
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
        )
        database.insertSeason(
            SeasonEntity(
                id = season,
                seriesId = showId,
                name = "Season $index",
                seriesName = "Bluey",
                overview = "",
                indexNumber = index,
            )
        )
        database.insertEpisode(
            EpisodeEntity(
                id = episode,
                serverId = serverId.toServerId(),
                seasonId = season,
                seriesId = showId,
                name = "Episode $index",
                seriesName = "Bluey",
                overview = "",
                indexNumber = 1,
                indexNumberEnd = null,
                parentIndexNumber = index,
                runtimeTicks = 4_200_000_000L,
                premiereDate = null,
                communityRating = null,
                chapters = null,
            )
        )
        database.insertSource(source(episode))
    }

    private fun source(itemId: ItemId) =
        ItemSourceEntity(
            id = "source-${itemId.asString()}".toMediaSourceId(),
            itemId = itemId,
            name = "Original",
            type = ItemSourceType.LOCAL,
            path = "/storage/downloads/${itemId.asString()}.mkv",
        )

    private suspend fun fails(get: suspend () -> Any?): Boolean = runCatching { get() }.isFailure

    @Test
    fun `a movie stored for another server is not the current server's`() = runBlocking {
        storeMovie(SERVER_A)

        current(SERVER_B)
        assertTrue(fails { repository.getMovie(movieId) }, "getMovie on B")
        assertTrue(fails { repository.getItem(movieId) }, "getItem on B")

        current(SERVER_A)
        assertEquals("Movie", repository.getMovie(movieId).name)
        assertEquals(movieId, repository.getItem(movieId)?.id)
    }

    @Test
    fun `an episode, its season and its show stored for another server are not the current server's`() =
        runBlocking {
            storeEpisode(SERVER_A, seasonId, episodeId, index = 1)

            current(SERVER_B)
            assertTrue(fails { repository.getEpisode(episodeId) }, "getEpisode on B")
            assertTrue(fails { repository.getSeason(seasonId) }, "getSeason on B")
            assertTrue(fails { repository.getShow(showId) }, "getShow on B")
            for (id in listOf(episodeId, seasonId, showId)) {
                assertTrue(fails { repository.getItem(id) }, "getItem(${id.asString()}) on B")
            }

            current(SERVER_A)
            assertEquals("Episode 1", repository.getEpisode(episodeId).name)
            assertEquals("Season 1", repository.getSeason(seasonId).name)
            assertEquals("Bluey", repository.getShow(showId).name)
            assertEquals(episodeId, repository.getItem(episodeId)?.id)
        }

    @Test
    fun `the stored lists of a show are the current server's`() = runBlocking {
        storeEpisode(SERVER_A, seasonId, episodeId, index = 1)

        current(SERVER_B)
        assertEquals(emptyList(), repository.getSeasons(showId, offline = true).map { it.id })
        assertEquals(
            emptyList(),
            repository.getEpisodes(showId, seasonId, offline = true).map { it.id },
        )
        assertEquals(
            emptyList(),
            repository.getEpisodes(showId, null, offline = true).map { it.id },
        )

        current(SERVER_A)
        assertEquals(listOf(seasonId), repository.getSeasons(showId, offline = true).map { it.id })
        assertEquals(
            listOf(episodeId),
            repository.getEpisodes(showId, seasonId, offline = true).map { it.id },
        )
    }

    @Test
    fun `a show both servers downloaded episodes of is listed for each with its own episodes`() =
        runBlocking {
            // A stored the show first, so its row names A; B's episode is in another season.
            storeEpisode(SERVER_A, seasonId, episodeId, index = 1)
            storeEpisode(SERVER_B, otherSeasonId, otherEpisodeId, index = 2)

            current(SERVER_B)
            assertEquals(listOf(showId), repository.getDownloads().map { it.id })
            assertEquals("Bluey", repository.getShow(showId).name)
            assertEquals(
                listOf(otherSeasonId),
                repository.getSeasons(showId, offline = true).map { it.id },
            )
            assertEquals(
                listOf(otherEpisodeId),
                repository.getEpisodes(showId, null, offline = true).map { it.id },
            )
            assertTrue(fails { repository.getSeason(seasonId) }, "A's season on B")

            current(SERVER_A)
            assertEquals(listOf(showId), repository.getDownloads().map { it.id })
            assertEquals(
                listOf(seasonId),
                repository.getSeasons(showId, offline = true).map { it.id },
            )
            assertEquals(
                listOf(episodeId),
                repository.getEpisodes(showId, null, offline = true).map { it.id },
            )
        }

    @Test
    fun `playback asks the current server rather than play another server's download`() =
        runBlocking {
            storeMovie(SERVER_A)

            current(SERVER_B)
            // Asking the server is what fails here; A's file must not be the answer.
            val onB = runCatching { repository.getPlaybackSources(movieId, LOCAL_PLAYER, null) }
            assertTrue(onB.isFailure, "Got ${onB.getOrNull()?.map { it.path }}")

            current(SERVER_A)
            assertEquals(
                listOf("/storage/downloads/${movieId.asString()}.mkv"),
                repository.getPlaybackSources(movieId, LOCAL_PLAYER, null).map { it.path },
            )
        }

    @Test
    fun `a download still in progress is not played`() = runBlocking {
        storeMovie(SERVER_A)
        database.insertSource(
            source(movieId)
                .copy(
                    id = "partial".toMediaSourceId(),
                    path = "/storage/downloads/other.mkv.download",
                )
        )

        current(SERVER_A)
        assertEquals(
            listOf("source-${movieId.asString()}"),
            database.getCompletedSources(movieId, SERVER_A.toServerId()).map { it.id.asString() },
        )
        assertEquals(
            listOf("/storage/downloads/${movieId.asString()}.mkv"),
            repository.getPlaybackSources(movieId, LOCAL_PLAYER, null).map { it.path },
        )
    }

    @Test
    fun `downloaded marks, episode counts and smart downloads see only the current server's`() =
        runBlocking {
            storeMovie(SERVER_A)
            storeEpisode(SERVER_A, seasonId, episodeId, index = 1)
            val ids = listOf(movieId, episodeId)

            assertEquals(emptyMap(), database.getLocalSourcesForItems(ids, SERVER_B.toServerId()))
            assertEquals(
                ids.toSet(),
                database.getLocalSourcesForItems(ids, SERVER_A.toServerId()).keys,
            )
            assertEquals(
                emptyList(),
                database.getSeasonEpisodeCounts(showId, SERVER_B.toServerId()),
            )
            assertEquals(
                1,
                database.getSeasonEpisodeCounts(showId, SERVER_A.toServerId()).single().downloaded,
            )
            assertEquals(
                emptyList(),
                database.getSeriesIdsWithCompletedDownloads(SERVER_B.toServerId()),
            )
            assertEquals(
                listOf(showId),
                database.getSeriesIdsWithCompletedDownloads(SERVER_A.toServerId()),
            )
            assertEquals(emptyList(), database.getSourcesOfServer(movieId, SERVER_B.toServerId()))
            assertEquals(
                emptyList(),
                database.getCompletedSources(episodeId, SERVER_B.toServerId()),
            )
        }

    @Test
    fun `progress still to be sent to a server is sent after another server took the item over`() =
        runBlocking {
            // A's user watched the movie offline; then B's download of the same id took the row.
            storeMovie(SERVER_B)
            database.insertUserData(
                UserDataEntity(
                    userId = userId,
                    itemId = movieId,
                    played = false,
                    favorite = false,
                    playbackPositionTicks = 1_200_000_000L,
                    toBeSynced = true,
                )
            )

            assertEquals(
                listOf(movieId),
                database.getAllUserDataToBeSynced(SERVER_A.toServerId(), userId).map { it.itemId },
            )
        }

    private companion object {
        const val SERVER_A = "server-a"
        const val SERVER_B = "server-b"

        // CE (#766): the local player with every player preference at its default.
        val LOCAL_PLAYER = PlaybackTarget.LocalPlayer(PlayerEngineId.EXOPLAYER, true)
    }
}
