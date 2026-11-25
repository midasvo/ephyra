package dev.jdtech.jellyfin.database

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.jdtech.jellyfin.models.ItemFilter
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.Segment
import dev.jdtech.jellyfin.models.SegmentType
import dev.jdtech.jellyfin.models.SeriesTrackPreference
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.repository.StoredLibraries
import dev.jdtech.jellyfin.repository.StoredLibraryNames
import dev.jdtech.jellyfin.testing.ContractLibrary
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #966: the read roles of the stored fallback ([dev.jdtech.jellyfin.repository.StoredItems],
 * [dev.jdtech.jellyfin.repository.StoredItemSources],
 * [dev.jdtech.jellyfin.repository.StoredItemLists]), the user data writes and the segment read of
 * [RoomDownloadStore], and the track preferences of [RoomServerStore], over a real Room database.
 * What is written through the download's own roles comes back through the reads, of its own server
 * only (#718), and only a finished download counts as one (#454).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RoomStoredReadsTest {
    private val room =
        Room.inMemoryDatabaseBuilder(
                RuntimeEnvironment.getApplication(),
                ServerDatabase::class.java,
            )
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val store = RoomDownloadStore(room.getServerDatabaseDao())
    private val servers = RoomServerStore(room.getServerDatabaseDao())

    @After fun close() = room.close()

    private val serverId = "server-1".toServerId()
    private val otherServer = "server-2".toServerId()
    private val userId = ContractLibrary.userId

    private val movie = ContractLibrary.movieWithResume
    private val favorite = ContractLibrary.favoriteMovie
    private val episode = ContractLibrary.season1Episodes[0]
    private val secondEpisode = ContractLibrary.season1Episodes[1]

    /** [item] stored for [server] with its user data and a local source at [path]. */
    private suspend fun storeMovie(item: Movie, path: String = "/movies/${item.id.value}.mkv") {
        store.insertMovie(item, serverId)
        store.storeUserDataOfDownload(userId, item)
        store.insertSource(item.id, item.sources.first(), path, 1L, null, false)
    }

    private suspend fun storeEpisodes() {
        for (e in listOf(episode, secondEpisode)) {
            store.insertEpisode(e, ContractLibrary.series, ContractLibrary.season1, serverId)
            store.storeUserDataOfDownload(userId, e)
            store.insertSource(e.id, e.sources.first(), "/shows/${e.id.value}.mkv", 2L, null, false)
        }
    }

    @Test
    fun `a stored item is read for its own server only, with the user's data`() = runBlocking {
        storeMovie(movie)
        storeEpisodes()

        val stored = assertNotNull(store.movieOfServer(movie.id, serverId)).load(userId)
        assertEquals(movie.name, stored.name)
        assertEquals(movie.playbackPositionTicks, stored.playbackPositionTicks)
        assertTrue(stored.sources.isNotEmpty(), "with its local source")
        assertNull(store.movieOfServer(movie.id, otherServer))
        assertNotNull(store.episodeOfServer(episode.id, serverId))
        assertNull(store.episodeOfServer(episode.id, otherServer))
        assertEquals(
            ContractLibrary.series.name,
            assertNotNull(store.showOfServer(ContractLibrary.series.id, serverId)).load(userId).name,
        )
        assertNotNull(store.seasonOfServer(ContractLibrary.season1.id, serverId))
        assertNull(store.showOfServer(ContractLibrary.series.id, otherServer))
        assertEquals(movie.runtimeTicks, store.runtimeTicks(movie.id))
    }

    @Test
    fun `a show's stored seasons and episodes are read in order and mapped when loaded`() =
        runBlocking {
            storeEpisodes()

            val seasons = store.seasonsOfShow(ContractLibrary.series.id, serverId)
            assertEquals(listOf(ContractLibrary.season1.id), seasons.load(userId).map { it.id })
            val ofSeason = store.episodesOfSeason(ContractLibrary.season1.id, serverId)
            assertEquals(listOf(episode.id, secondEpisode.id), ofSeason.load(userId).map { it.id })
            val ofSeries = store.episodesOfSeries(ContractLibrary.series.id, serverId)
            assertEquals(2, ofSeries.size)
            assertEquals(listOf(secondEpisode.id), ofSeries.slice(1, 5).load(userId).map { it.id })
            assertEquals(0, store.episodesOfSeries(ContractLibrary.series.id, otherServer).size)
            assertEquals(
                listOf(ContractLibrary.series.id),
                store.showsOfServer(serverId).load(userId).map { it.id },
            )
        }

    @Test
    fun `only a finished download is a completed source`() = runBlocking {
        assertFalse(store.hasSources())
        storeMovie(movie)
        storeMovie(favorite, path = "/movies/partial.mkv.download")

        assertTrue(store.hasSources())
        assertTrue(store.observeHasSources().first())
        assertEquals(1, store.completedSourcesOfServer(movie.id, serverId).size)
        assertTrue(store.completedSourcesOfServer(favorite.id, serverId).isEmpty())
        assertTrue(store.completedSourcesOfServer(movie.id, otherServer).isEmpty())
        val local = store.localSourcesOfServer(listOf(movie.id, favorite.id), serverId)
        assertEquals(setOf(movie.id, favorite.id), local.keys)
        assertTrue(store.localSourcesOfServer(listOf(movie.id), otherServer).isEmpty())
        assertEquals(
            listOf(movie.id),
            store.moviesOfServer(serverId).load(userId).map { it.id }.filter { it == movie.id },
        )
    }

    @Test
    fun `the stored lists hold the finished downloads of the server`() = runBlocking {
        storeMovie(movie)
        storeMovie(favorite)
        storeEpisodes()

        assertTrue(store.hasDownloads(serverId))
        assertFalse(store.hasDownloads(otherServer))
        assertEquals(
            listOf(favorite.id),
            store.favorites(serverId, userId).load(userId).map { it.id },
        )
        assertEquals(0, store.favorites(otherServer, userId).size)
        assertEquals(
            listOf(movie.id),
            store.search(serverId, "long", listOf(ItemKind.MOVIE), 10).load(userId).map { it.id },
        )
        val libraries = store.libraries(serverId, StoredLibraryNames.ENGLISH) { _, _ -> false }
        assertEquals(listOf(StoredLibraries.MOVIES, StoredLibraries.SHOWS), libraries.map { it.id })
        val movies =
            store.libraryContents(
                serverId,
                userId,
                StoredLibraries.MOVIES,
                SortBy.NAME,
                SortOrder.ASCENDING,
                emptySet(),
            )
        assertEquals(listOf(movie.id, favorite.id), movies.load(userId).map { it.id })
        assertEquals(
            2,
            store.libraryCount(serverId, userId, StoredLibraries.MOVIES, emptySet(), null),
        )
        assertEquals(
            1,
            store.libraryCount(serverId, userId, StoredLibraries.MOVIES, emptySet(), "M"),
        )
    }

    @Test
    fun `a user data write is marked, and the mark comes off only while the row holds it`() =
        runBlocking {
            storeMovie(movie)

            val marked = store.writeAndMarkToBeSynced(userId, movie.id) { it.copy(favorite = true) }
            assertTrue(marked.marked)
            val written = assertNotNull(marked.written)
            assertTrue(written.favorite)
            assertTrue(store.hasUserDataToBeSynced(userId, movie.id))

            store.updatePlayState(userId, movie.id) { it.copy(positionTicks = 1L) }
            store.clearToBeSynced(userId, movie.id, written)
            assertTrue(store.hasUserDataToBeSynced(userId, movie.id), "a later change keeps it")

            store.clearToBeSynced(userId, movie.id, written.copy(positionTicks = 1L))
            assertFalse(store.hasUserDataToBeSynced(userId, movie.id))
            assertTrue(store.markToBeSynced(userId, movie.id))

            val notStored = store.writeAndMarkToBeSynced(userId, favorite.id) { it }
            assertFalse(notStored.marked)
            assertNull(notStored.written)
            assertFalse(store.markToBeSynced(userId, favorite.id))
        }

    @Test
    fun `stored segments and track preferences come back as written`() = runBlocking {
        val segments = listOf(Segment(SegmentType.INTRO, 0L, 30_000L))
        store.replaceSegments(episode.id, segments)
        assertEquals(segments, store.segments(episode.id))
        assertTrue(store.segments(movie.id).isEmpty())

        val preference =
            SeriesTrackPreference(
                userId = userId,
                seriesId = ContractLibrary.series.id,
                trackType = 1,
                disabled = false,
                language = "nld",
                title = "Dutch",
                positionInLanguage = 1,
            )
        servers.saveTrackPreference(preference)
        servers.saveTrackPreference(preference.copy(title = "Nederlands"))
        assertEquals(
            listOf(preference.copy(title = "Nederlands")),
            servers.trackPreferences(userId, ContractLibrary.series.id),
        )
    }

    /**
     * The review of #1010: each read asked for another server gives nothing, with the same read for
     * the storing server as the control; and the reads of a user's data answer per user.
     */
    @Test
    fun `every stored read of another server gives nothing`() = runBlocking {
        storeMovie(movie)
        storeMovie(favorite)
        storeEpisodes()
        val series = ContractLibrary.series.id
        val season = ContractLibrary.season1.id
        val movies = StoredLibraries.MOVIES

        for ((server, expected) in listOf(serverId to true, otherServer to false)) {
            val what = "for ${server.value}"
            assertEquals(expected, store.seasonOfServer(season, server) != null, what)
            assertEquals(expected, store.seasonsOfShow(series, server).size > 0, what)
            assertEquals(expected, store.episodesOfSeason(season, server).size > 0, what)
            assertEquals(expected, store.moviesOfServer(server).size > 0, what)
            assertEquals(expected, store.showsOfServer(server).size > 0, what)
            assertEquals(expected, store.search(server, "movie", null, 10).size > 0, what)
            assertEquals(
                expected,
                store.libraries(server, StoredLibraryNames.ENGLISH) { _, _ -> false }.isNotEmpty(),
                what,
            )
            assertEquals(
                expected,
                store
                    .libraryContents(
                        server,
                        userId,
                        movies,
                        SortBy.NAME,
                        SortOrder.ASCENDING,
                        setOf(),
                    )
                    .size > 0,
                what,
            )
            assertEquals(
                expected,
                store.libraryCount(server, userId, movies, emptySet(), null) > 0,
                what,
            )
        }
    }

    @Test
    fun `the favourites, the unplayed filter and the loaded user data are per user`() =
        runBlocking {
            storeMovie(movie)
            storeMovie(favorite)
            val other = "00000000-0000-0000-0000-0000000000b2".toUserId()
            store.updatePlayState(userId, movie.id) { it.copy(played = true) }
            val unplayed = setOf(ItemFilter.IS_UNPLAYED)
            val movies = StoredLibraries.MOVIES

            assertEquals(1, store.favorites(serverId, userId).size)
            assertEquals(0, store.favorites(serverId, other).size)
            assertEquals(
                listOf(favorite.id),
                store
                    .libraryContents(
                        serverId,
                        userId,
                        movies,
                        SortBy.NAME,
                        SortOrder.ASCENDING,
                        unplayed,
                    )
                    .load(userId)
                    .map { it.id },
            )
            assertEquals(
                2,
                store
                    .libraryContents(
                        serverId,
                        other,
                        movies,
                        SortBy.NAME,
                        SortOrder.ASCENDING,
                        unplayed,
                    )
                    .size,
            )
            assertEquals(1, store.libraryCount(serverId, userId, movies, unplayed, null))
            assertEquals(2, store.libraryCount(serverId, other, movies, unplayed, null))
            val row = assertNotNull(store.movieOfServer(favorite.id, serverId))
            assertTrue(row.load(userId).favorite)
            assertFalse(row.load(other).favorite)
        }
}
