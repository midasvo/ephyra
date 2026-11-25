package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.domain.userdata.PlayState
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.Library
import dev.jdtech.jellyfin.models.PlaybackTarget
import dev.jdtech.jellyfin.models.Segment
import dev.jdtech.jellyfin.models.SegmentType
import dev.jdtech.jellyfin.models.SeriesTrackPreference
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.testing.ContractLibrary
import dev.jdtech.jellyfin.testing.FakeMediaServer
import dev.jdtech.jellyfin.testing.FakeRepositoryStores
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

/**
 * #966: the repository over [FakeMediaServer] and the fake stores, without Android: what it asks
 * the server, what it takes from the stores when the server does not answer (#281, #775), the user
 * data it writes and reports (#281, #635), and that a cancelled request stays cancelled. The same
 * paths over Room are in `data`'s tests.
 */
class MediaRepositoryImplTest {
    private val serverId = "server-1".toServerId()
    private val otherServer = "server-2".toServerId()
    private val userId = ContractLibrary.userId

    private val movie = ContractLibrary.movieWithResume
    private val episode = ContractLibrary.season1Episodes[0]

    private val server = FakeMediaServer()
    private val stores = FakeRepositoryStores()
    private var current: ServerId? = serverId
    private var syncRequests = 0
    private var knownAway = false

    private val repository =
        stores.repository(
            server,
            currentServer = { current },
            syncRequester = { syncRequests++ },
            knownServerState =
                object : KnownServerState {
                    override fun isKnownUnreachable() = knownAway

                    override fun onServerSkipped() = Unit
                },
        )

    private fun away() = server.failWith(ServerException.Unreachable())

    @Test
    fun `an item comes from the server while it answers`() = runBlocking {
        stores.items.put(serverId, movie.copy(name = "Stored"))

        assertEquals(movie.name, repository.getMovie(movie.id).name)
    }

    @Test
    fun `an item comes from the store of the current server when the server is away`() =
        runBlocking<Unit> {
            stores.items.put(serverId, movie.copy(name = "Stored"))
            away()

            assertEquals("Stored", repository.getMovie(movie.id).name)
            assertEquals("Stored", repository.getItem(movie.id)?.name)
            current = otherServer
            assertFailsWith<ServerException> { repository.getMovie(movie.id) }
        }

    @Test
    fun `a list known to be away comes from the store without asking the server`() = runBlocking {
        stores.lists.favorites[serverId] = listOf(movie)
        knownAway = true

        val result = repository.getFavoriteItems()

        assertTrue(result.fromStore)
        assertEquals(listOf(movie.id), result.items.map { it.id })
        assertFalse("favorites" in server.calls)
    }

    @Test
    fun `a list the server answers is the server's`() = runBlocking {
        stores.lists.favorites[serverId] = listOf(movie)

        val result = repository.getFavoriteItems()

        assertFalse(result.fromStore)
        assertTrue("favorites" in server.calls)
    }

    @Test
    fun `the stored libraries are paged from the store`() = runBlocking {
        val stored = ContractLibrary.season1Episodes.take(3)
        stores.lists.libraryContents[StoredLibraries.SHOWS] = stored

        val page =
            repository.getItemsPage(
                parentId = StoredLibraries.SHOWS,
                startIndex = 1,
                limit = 1,
                includeTotal = true,
            )

        assertEquals(listOf(stored[1].id), page.items.map { it.id })
        assertEquals(3, page.totalRecordCount)
        assertTrue(server.calls.isEmpty(), "the server does not know these ids: ${server.calls}")
    }

    @Test
    fun `without the server the libraries are the stored ones`() = runBlocking {
        val library =
            Library(
                id = StoredLibraries.MOVIES,
                name = "Movies",
                type = ContractLibrary.moviesLibrary.type,
                images = ContractLibrary.moviesLibrary.images,
            )
        stores.lists.libraries[serverId] = listOf(library)
        knownAway = true

        assertEquals(listOf(library), repository.getLibraries().items)
    }

    @Test
    fun `a finished download plays without asking the server`() = runBlocking {
        val local = movie.sources.first().copy(type = ItemSourceType.LOCAL, path = "/movie.mkv")
        stores.sources.put(serverId, movie.id, local)

        assertEquals(listOf(local), repository.getPlaybackSources(movie.id, TARGET))
        assertFalse("sources" in server.calls)
    }

    @Test
    fun `an unfinished download is not played, the server is asked`() = runBlocking {
        val partial =
            movie.sources.first().copy(type = ItemSourceType.LOCAL, path = "/movie.mkv.download")
        stores.sources.put(serverId, movie.id, partial)

        repository.getPlaybackSources(movie.id, TARGET)

        assertTrue("sources" in server.calls)
    }

    @Test
    fun `offline seasons, episodes and downloads are the current server's stored ones`() =
        runBlocking {
            stores.items.put(serverId, ContractLibrary.series, ContractLibrary.season1, episode)
            stores.items.put(otherServer, movie)
            away()

            assertEquals(
                listOf(ContractLibrary.season1.id),
                repository.getSeasons(ContractLibrary.series.id, offline = true).map { it.id },
            )
            assertEquals(
                listOf(episode.id),
                repository
                    .getEpisodes(
                        ContractLibrary.series.id,
                        ContractLibrary.season1.id,
                        offline = true,
                    )
                    .map { it.id },
            )
            assertEquals(listOf(ContractLibrary.series.id), repository.getDownloads().map { it.id })
        }

    @Test
    fun `a favourite of a download is written, marked and unmarked once the server has it`() =
        runBlocking {
            stores.userData.put(userId, movie.id)

            repository.markAsFavorite(movie.id)

            val row = stores.userData.row(userId, movie.id)!!
            assertTrue(row.state.favorite)
            assertFalse(row.toBeSynced, "the server has it")
            assertEquals(0, syncRequests)
        }

    @Test
    fun `a favourite made while the server is away stays marked and asks for a sync`() =
        runBlocking {
            stores.userData.put(userId, movie.id)
            away()

            repository.markAsFavorite(movie.id)

            val row = stores.userData.row(userId, movie.id)!!
            assertTrue(row.state.favorite)
            assertTrue(row.toBeSynced)
            assertEquals(1, syncRequests)
        }

    @Test
    fun `a mark of an item without a stored row is the server call alone`() = runBlocking {
        repository.markAsPlayed(movie.id)

        assertTrue("setPlayed" in server.calls)
        assertNull(stores.userData.row(userId, movie.id))
    }

    @Test
    fun `an unplayed mark that reached the server clears the last played date`() = runBlocking {
        stores.userData.put(userId, movie.id, PlayState(true, false, 0L, lastPlayedMillis = 1L))

        repository.markAsUnplayed(movie.id)

        val row = stores.userData.row(userId, movie.id)!!
        assertFalse(row.state.played)
        assertNull(row.state.lastPlayedMillis)
        assertFalse(row.toBeSynced)
    }

    @Test
    fun `a track choice is saved for the current user whatever the preference says`() =
        runBlocking {
            val other = "00000000-0000-0000-0000-000000000002".toUserId()
            val audio =
                SeriesTrackPreference(
                    other,
                    ContractLibrary.series.id,
                    1,
                    false,
                    "jpn",
                    "Japanese",
                    0,
                )

            repository.saveSeriesTrackPreference(audio)

            assertEquals(
                listOf(audio.copy(userId = userId)),
                repository.getSeriesTrackPreferences(ContractLibrary.series.id),
            )
            assertTrue(stores.trackPreferences.trackPreferences(other, audio.seriesId).isEmpty())
        }

    @Test
    fun `stored segments and trickplay sheets are used before the server`() = runBlocking {
        val segments = listOf(Segment(SegmentType.INTRO, 0L, 30_000L))
        stores.segments.segments[episode.id] = segments
        stores.files.sheets[Triple(episode.id, null, 0)] = byteArrayOf(1, 2)

        assertEquals(segments, repository.getSegments(episode.id))
        assertContentEquals(byteArrayOf(1, 2), repository.getTrickplayData(episode.id, 320, 0))
        assertFalse("segments" in server.calls || "trickplayTile" in server.calls)
    }

    @Test
    fun `the current user's image tag goes to the stored user`() = runBlocking {
        repository.refreshCurrentUserImage()

        assertTrue(userId in stores.userImageTags)
    }

    @Test
    fun `a failed theme song, segments or trickplay request gives nothing`() = runBlocking {
        away()

        assertNull(repository.getThemeSongUrl(episode.id))
        assertTrue(repository.getServerSegments(episode.id).isEmpty())
        assertNull(repository.getTrickplayData(episode.id, 320, 0))
    }

    @Test
    fun `a cancelled theme song, segments or trickplay request stays cancelled`() =
        runBlocking<Unit> {
            server.stall()

            assertFailsWith<CancellationException> {
                withTimeout(100) { repository.getThemeSongUrl(episode.id) }
            }
            assertFailsWith<CancellationException> {
                withTimeout(100) { repository.getServerSegments(episode.id) }
            }
            assertFailsWith<CancellationException> {
                withTimeout(100) { repository.getTrickplayData(episode.id, 320, 0) }
            }
        }

    private companion object {
        val TARGET = PlaybackTarget.Download(false)
    }
}
