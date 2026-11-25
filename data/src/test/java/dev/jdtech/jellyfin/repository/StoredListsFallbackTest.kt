package dev.jdtech.jellyfin.repository

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.jdtech.jellyfin.database.ServerDatabase
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.EpisodeEntity
import dev.jdtech.jellyfin.models.ItemFilter
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.ItemSourceEntity
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.MovieEntity
import dev.jdtech.jellyfin.models.SeasonEntity
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.ShowEntity
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.models.UserDataEntity
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toMediaSourceId
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.testing.FakeResumeThresholdsStore
import java.io.File
import java.time.LocalDateTime
import java.util.UUID
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
 * CE (#775): the list getters answer from Room while the server is away: the downloaded favourites,
 * search results and the stored Movies and Shows libraries, of the current server and user only.
 * The server here always fails: the API client has no address.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StoredListsFallbackTest {
    private val userId = "00000000-0000-0000-0000-000000000001".toUserId()
    private val otherUserId = "00000000-0000-0000-0000-000000000002".toUserId()
    private val showId = "00000000-0000-0000-0000-000000000020".toItemId()
    private val seasonId = "00000000-0000-0000-0000-000000000021".toItemId()
    private val episodeId = "00000000-0000-0000-0000-000000000022".toItemId()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val room =
        Room.inMemoryDatabaseBuilder(context, ServerDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val database: ServerDatabaseDao = room.getServerDatabaseDao()
    private var currentServerId: ServerId? = SERVER_A.toServerId()

    private val knownServerState =
        object : KnownServerState {
            var unreachable = false
            var skipped = 0

            override fun isKnownUnreachable() = unreachable

            override fun onServerSkipped() {
                skipped++
            }
        }

    /** Requests go unanswered by default. */
    private val server = UnansweredServer(userId)

    private val repository: MediaRepositoryImpl by lazy {
        roomMediaRepository(
            context,
            server,
            database,
            currentServer = { currentServerId },
            nextUpMaxDays = { 0 },
            resumeThresholds = FakeResumeThresholdsStore(),
            knownServerState = knownServerState,
        )
    }

    @After
    fun close() {
        room.close()
    }

    private fun id(n: Int) = ("00000000-0000-0000-0000-0000000001%02d".format(n)).toItemId()

    private suspend fun storeMovie(
        id: ItemId,
        name: String,
        serverId: String = SERVER_A,
        rating: Float? = null,
        premiere: LocalDateTime? = null,
        downloadedAt: Long? = null,
        download: Download = Download.FINISHED,
    ) {
        database.insertMovie(
            MovieEntity(
                id = id,
                serverId = serverId.toServerId(),
                name = name,
                originalTitle = null,
                overview = "",
                runtimeTicks = 0L,
                premiereDate = premiere,
                communityRating = rating,
                officialRating = null,
                status = "",
                productionYear = premiere?.year,
                endDate = null,
                chapters = null,
            )
        )
        storeSource(id, downloadedAt, download)
    }

    /** What the downloader leaves behind: rows first, then the source, then the finished file. */
    enum class Download {
        FINISHED,
        WRITING,
        NO_SOURCE,
    }

    private suspend fun storeSource(itemId: ItemId, downloadedAt: Long?, download: Download) {
        when (download) {
            Download.FINISHED -> database.insertSource(source(itemId, downloadedAt))
            Download.WRITING ->
                database.insertSource(
                    source(itemId, null)
                        .copy(path = "/storage/downloads/${itemId.asString()}.mkv.download")
                )
            Download.NO_SOURCE -> Unit
        }
    }

    private suspend fun storeEpisode(
        serverId: String = SERVER_A,
        name: String = "Obstacle Course",
        download: Download = Download.FINISHED,
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
                id = seasonId,
                seriesId = showId,
                name = "Season 1",
                seriesName = "Bluey",
                overview = "",
                indexNumber = 1,
            )
        )
        database.insertEpisode(
            EpisodeEntity(
                id = episodeId,
                serverId = serverId.toServerId(),
                seasonId = seasonId,
                seriesId = showId,
                name = name,
                seriesName = "Bluey",
                overview = "",
                indexNumber = 1,
                indexNumberEnd = null,
                parentIndexNumber = 1,
                runtimeTicks = 4_200_000_000L,
                premiereDate = null,
                communityRating = null,
                chapters = null,
            )
        )
        storeSource(episodeId, null, download)
    }

    private suspend fun userData(
        itemId: ItemId,
        user: UserId = userId,
        favorite: Boolean = false,
        played: Boolean = false,
        lastPlayed: Long? = null,
    ) =
        database.insertUserData(
            UserDataEntity(
                userId = user,
                itemId = itemId,
                played = played,
                favorite = favorite,
                playbackPositionTicks = 0L,
                lastPlayedDate = lastPlayed,
            )
        )

    private fun source(itemId: ItemId, downloadedAt: Long?) =
        ItemSourceEntity(
            id = "source-${itemId.asString()}".toMediaSourceId(),
            itemId = itemId,
            name = "Original",
            type = ItemSourceType.LOCAL,
            path = "/storage/downloads/${itemId.asString()}.mkv",
            downloadedAt = downloadedAt,
        )

    private suspend fun fails(get: suspend () -> Any?): Boolean = runCatching { get() }.isFailure

    private suspend fun page(
        library: ItemId,
        sortBy: SortBy = SortBy.NAME,
        sortOrder: SortOrder = SortOrder.ASCENDING,
        filters: Set<ItemFilter> = emptySet(),
        startIndex: Int? = null,
        limit: Int? = null,
    ) =
        repository.getItemsPage(
            parentId = library,
            sortBy = sortBy,
            sortOrder = sortOrder,
            filters = filters,
            startIndex = startIndex,
            limit = limit,
            includeTotal = true,
        )

    @Test
    fun `favourites are the downloaded ones of the current server and user`() = runBlocking {
        storeMovie(id(1), "Favourite")
        storeMovie(id(2), "Not a favourite")
        storeMovie(id(3), "Another user's favourite")
        storeMovie(id(4), "Another server's favourite", serverId = SERVER_B)
        storeEpisode()
        userData(id(1), favorite = true)
        userData(id(2))
        userData(id(3), user = otherUserId, favorite = true)
        userData(id(4), favorite = true)
        userData(showId, favorite = true)
        userData(episodeId, favorite = true)

        assertEquals(
            listOf(id(1), showId, episodeId),
            repository.getFavoriteItems().items.map { it.id },
        )
        assertTrue(repository.getFavoriteItems().items.all { it.favorite })
    }

    @Test
    fun `with a downloaded non-favourite an unanswered server gives no favourites`() = runBlocking {
        // The screen says that none of the favourites are downloaded, not that loading failed.
        storeMovie(id(1), "Not a favourite")
        userData(id(1))
        val result = repository.getFavoriteItems()
        assertEquals(emptyList<Any>(), result.items)
        // CE (#779): Room answered the unanswered request, before anything knew the server away.
        assertTrue(result.fromStore)
    }

    @Test
    fun `with a downloaded non-favourite a server known to be away gives no favourites`() =
        runBlocking {
            storeMovie(id(1), "Not a favourite")
            knownServerState.unreachable = true

            assertEquals(emptyList<Any>(), repository.getFavoriteItems().items)
            assertEquals(1, knownServerState.skipped)
        }

    @Test
    fun `with nothing downloaded the server's error surfaces for favourites`() = runBlocking {
        // TV, or a phone without downloads: the error page, as before.
        assertTrue(fails { repository.getFavoriteItems().items })

        knownServerState.unreachable = true
        assertTrue(fails { repository.getFavoriteItems().items })
        // Only an unfinished download: nothing to play, so the same.
        storeMovie(id(1), "Writing", download = Download.WRITING)
        assertTrue(fails { repository.getFavoriteItems().items })
        assertEquals(0, knownServerState.skipped)
    }

    @Test
    fun `without stored favourites an answered error still surfaces`() = runBlocking {
        // The server is there and says no: that is an error to show.
        storeMovie(id(1), "Not a favourite")
        server.failure = { ServerException.HttpStatus(500) }
        assertTrue(fails { repository.getFavoriteItems().items })
    }

    @Test
    fun `without a current server there is no fallback for favourites`() = runBlocking {
        currentServerId = null
        assertTrue(fails { repository.getFavoriteItems().items })
    }

    @Test
    fun `the stored libraries are those with downloads of the current server`() = runBlocking {
        assertTrue(fails { repository.getLibraries().items }, "nothing stored")

        storeMovie(id(1), "Movie", serverId = SERVER_B)
        assertTrue(fails { repository.getLibraries().items }, "only another server's")

        storeMovie(id(2), "Movie")
        assertEquals(
            listOf(StoredLibraries.MOVIES to CollectionType.Movies),
            repository.getLibraries().items.map { it.id to it.type },
        )

        storeEpisode()
        val libraries = repository.getLibraries().items
        assertEquals(listOf(StoredLibraries.MOVIES, StoredLibraries.SHOWS), libraries.map { it.id })
        assertEquals(listOf("Movies", "TV Shows"), libraries.map { it.name })
    }

    /** An image as ImagesDownloaderWorker saves it with a download. */
    private fun storeImage(itemId: ItemId, image: String) {
        File(context.filesDir, "images/${itemId.asString()}/$image").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }
    }

    private suspend fun artwork() =
        repository.getLibraries().items.associate { it.id to it.images.primary }

    @Test
    fun `a stored library shows the artwork of its newest download that has some`() = runBlocking {
        storeMovie(id(1), "Older", downloadedAt = 1_000L)
        storeMovie(id(2), "Newer", downloadedAt = 2_000L)
        storeEpisode()
        // Nothing stored with the downloads: the blank card, as before.
        assertEquals(
            mapOf(StoredLibraries.MOVIES to null, StoredLibraries.SHOWS to null),
            artwork(),
        )

        // Only the older one has a backdrop: that one. The show has only its poster.
        storeImage(id(1), "backdrop")
        storeImage(id(2), "primary")
        storeImage(showId, "primary")
        assertEquals(
            mapOf(
                StoredLibraries.MOVIES to "/images/${id(1).asString()}/backdrop",
                StoredLibraries.SHOWS to "/images/${showId.asString()}/primary",
            ),
            artwork(),
        )

        // A backdrop of the newest: that one.
        storeImage(id(2), "backdrop")
        assertEquals("/images/${id(2).asString()}/backdrop", artwork()[StoredLibraries.MOVIES])
    }

    @Test
    fun `an empty image file is no artwork`() = runBlocking {
        storeMovie(id(1), "Movie", downloadedAt = 1_000L)
        File(context.filesDir, "images/${id(1).asString()}/backdrop").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf())
        }

        assertEquals(null, artwork()[StoredLibraries.MOVIES])
    }

    @Test
    fun `a stored library sorts and pages its downloads`() = runBlocking {
        storeMovie(id(1), "banana", rating = 7f, premiere = LocalDateTime.of(2001, 1, 1, 0, 0))
        storeMovie(id(2), "Apple", rating = 9f, premiere = LocalDateTime.of(1999, 1, 1, 0, 0))
        storeMovie(id(3), "cherry", rating = null, premiere = LocalDateTime.of(2010, 1, 1, 0, 0))
        storeMovie(id(4), "Elsewhere", serverId = SERVER_B)

        val byName = page(StoredLibraries.MOVIES)
        assertEquals(listOf("Apple", "banana", "cherry"), byName.items.map { it.name })
        assertEquals(3, byName.totalRecordCount)

        assertEquals(
            listOf("cherry", "banana", "Apple"),
            page(StoredLibraries.MOVIES, sortOrder = SortOrder.DESCENDING).items.map { it.name },
        )
        assertEquals(
            listOf("cherry", "banana", "Apple"),
            page(StoredLibraries.MOVIES, sortBy = SortBy.IMDB_RATING).items.map { it.name },
        )
        assertEquals(
            listOf("Apple", "banana", "cherry"),
            page(StoredLibraries.MOVIES, sortBy = SortBy.RELEASE_DATE).items.map { it.name },
        )

        val second = page(StoredLibraries.MOVIES, startIndex = 1, limit = 1)
        assertEquals(listOf("banana"), second.items.map { it.name })
        assertEquals(3, second.totalRecordCount)
    }

    @Test
    fun `a stored library sorts by download and play dates`() = runBlocking {
        storeMovie(id(1), "Old download", downloadedAt = 1_000L)
        storeMovie(id(2), "New download", downloadedAt = 3_000L)
        storeMovie(id(3), "Middle download", downloadedAt = 2_000L)
        userData(id(1), lastPlayed = 30L)
        userData(id(2), lastPlayed = 10L)
        userData(id(3), lastPlayed = 20L)
        userData(id(3), user = otherUserId, lastPlayed = 99L)

        assertEquals(
            listOf("New download", "Middle download", "Old download"),
            page(StoredLibraries.MOVIES, SortBy.DATE_ADDED, SortOrder.DESCENDING).items.map {
                it.name
            },
        )
        assertEquals(
            listOf("New download", "Middle download", "Old download"),
            page(StoredLibraries.MOVIES, SortBy.DATE_PLAYED).items.map { it.name },
        )
    }

    @Test
    fun `a stored show sorts by the last of its episodes played`() = runBlocking {
        storeEpisode()
        userData(episodeId, lastPlayed = 50L)
        userData(showId)

        val shows = page(StoredLibraries.SHOWS, SortBy.SERIES_DATE_PLAYED)
        assertEquals(listOf(showId), shows.items.map { it.id })
    }

    @Test
    fun `a stored library filters on the current user's played state`() = runBlocking {
        storeMovie(id(1), "Seen")
        storeMovie(id(2), "Unseen")
        storeMovie(id(3), "Seen by someone else")
        userData(id(1), played = true)
        userData(id(3), user = otherUserId, played = true)

        val unplayed = page(StoredLibraries.MOVIES, filters = setOf(ItemFilter.IS_UNPLAYED))
        assertEquals(listOf("Seen by someone else", "Unseen"), unplayed.items.map { it.name })
        assertEquals(2, unplayed.totalRecordCount)
        assertEquals(
            2,
            repository.getItemCount(
                parentId = StoredLibraries.MOVIES,
                filters = setOf(ItemFilter.IS_UNPLAYED),
            ),
        )
    }

    @Test
    fun `the letter counts of a stored library match its name order`() = runBlocking {
        storeMovie(id(1), "12 Angry Men")
        storeMovie(id(2), "Amélie")
        storeMovie(id(3), "brave")
        storeMovie(id(4), "Coco")

        assertEquals(
            1,
            repository.getItemCount(parentId = StoredLibraries.MOVIES, nameLessThan = "a"),
        )
        assertEquals(
            2,
            repository.getItemCount(parentId = StoredLibraries.MOVIES, nameLessThan = "b"),
        )
        assertEquals(
            3,
            repository.getItemCount(parentId = StoredLibraries.MOVIES, nameLessThan = "c"),
        )
        assertEquals(4, repository.getItemCount(parentId = StoredLibraries.MOVIES))
    }

    @Test
    fun `the library pagers' reads page a stored library`() = runBlocking {
        storeMovie(id(1), "Bingo")
        storeMovie(id(2), "Bandit")
        storeMovie(id(3), "Chilli")

        // The indexed pager's first page (#968: the pagers are in modes/film): with the total.
        val first =
            repository.getItemsPage(
                parentId = StoredLibraries.MOVIES,
                startIndex = 0,
                limit = 2,
                includeTotal = true,
            )
        assertEquals(listOf("Bandit", "Bingo"), first.items.map { it.name })
        assertEquals(3, first.totalRecordCount)
        // A later page, without the count.
        val next =
            repository.getItemsPage(
                parentId = StoredLibraries.MOVIES,
                startIndex = 2,
                limit = 2,
                includeTotal = false,
            )
        assertEquals(listOf("Chilli"), next.items.map { it.name })
        assertEquals(null, next.totalRecordCount)

        // The plain pager's read.
        val plain =
            repository.getItems(
                parentId = StoredLibraries.MOVIES,
                sortBy = SortBy.NAME,
                sortOrder = SortOrder.DESCENDING,
                startIndex = 1,
                limit = 2,
            )
        assertEquals(listOf("Bingo", "Bandit"), plain.map { it.name })
    }

    @Test
    fun `an unfinished movie is left out of every stored list`() = runBlocking {
        storeMovie(id(1), "Bluey Done")
        storeMovie(id(2), "Bluey Writing", download = Download.WRITING)
        storeMovie(id(3), "Bluey Abandoned", download = Download.NO_SOURCE)
        for (movie in listOf(id(1), id(2), id(3))) userData(movie, favorite = true)

        assertEquals(listOf(id(1)), repository.getFavoriteItems().items.map { it.id })
        assertEquals(listOf(id(1)), repository.getSearchItems("bluey", null).items.map { it.id })
        assertEquals(listOf(id(1)), page(StoredLibraries.MOVIES).items.map { it.id })
        assertEquals(1, repository.getItemCount(parentId = StoredLibraries.MOVIES))
    }

    @Test
    fun `a show whose only episode is unfinished is left out, and so is its library`() =
        runBlocking {
            storeMovie(id(1), "Movie")
            storeEpisode(name = "Bluey's Birthday", download = Download.WRITING)
            userData(showId, favorite = true)
            userData(episodeId, favorite = true)

            assertEquals(
                listOf(StoredLibraries.MOVIES),
                repository.getLibraries().items.map { it.id },
            )
            assertEquals(emptyList(), page(StoredLibraries.SHOWS).items.map { it.id })
            assertEquals(0, repository.getItemCount(parentId = StoredLibraries.SHOWS))
            // No finished favourite: none downloaded, which Favorites says (#775).
            assertEquals(emptyList(), repository.getFavoriteItems().items.map { it.id })
            assertTrue(
                fails { repository.getSearchItems("bluey", listOf(ItemKind.SERIES)).items },
                "no finished show",
            )
            assertTrue(
                fails { repository.getSearchItems("bluey", listOf(ItemKind.EPISODE)).items },
                "no finished episode",
            )
        }

    @Test
    fun `a library with only unfinished downloads does not appear`() = runBlocking {
        storeMovie(id(1), "Writing", download = Download.WRITING)
        storeMovie(id(2), "Abandoned", download = Download.NO_SOURCE)
        assertTrue(fails { repository.getLibraries().items })
    }

    @Test
    fun `a real library still asks the server`() = runBlocking {
        storeMovie(id(1), "Movie")
        assertTrue(fails { page(UUID.randomUUID().toString().toItemId()) })
        assertTrue(
            fails { repository.getItemCount(parentId = UUID.randomUUID().toString().toItemId()) }
        )
    }

    @Test
    fun `search finds the downloads of the asked kinds by name`() = runBlocking {
        storeMovie(id(1), "Bluey: The Movie")
        storeMovie(id(2), "Bluey elsewhere", serverId = SERVER_B)
        storeMovie(id(3), "Cars")
        storeEpisode(name = "Bluey's Birthday")

        assertEquals(
            listOf(id(1), showId),
            repository.getSearchItems("bluey", null).items.map { it.id },
        )
        assertEquals(
            listOf(episodeId),
            repository.getSearchItems("bluey", listOf(ItemKind.EPISODE)).items.map { it.id },
        )
        assertEquals(
            listOf(id(1)),
            repository.getSearchItems("BLUEY", listOf(ItemKind.MOVIE)).items.map { it.id },
        )
        // LIKE's wildcards are taken literally.
        assertTrue(fails { repository.getSearchItems("%", null).items })
        assertTrue(fails { repository.getSearchItems("nothing", null).items })
    }

    @Test
    fun `a server known to be away is skipped and asked about`() = runBlocking {
        storeMovie(id(1), "Movie")
        userData(id(1), favorite = true)
        knownServerState.unreachable = true

        assertEquals(listOf(id(1)), repository.getFavoriteItems().items.map { it.id })
        assertEquals(listOf(StoredLibraries.MOVIES), repository.getLibraries().items.map { it.id })
        assertEquals(listOf(id(1)), repository.getSearchItems("mov", null).items.map { it.id })
        assertEquals(3, knownServerState.skipped)

        // Nothing stored: the server is asked anyway, and its error surfaces.
        assertTrue(fails { repository.getSearchItems("nothing", null).items })
        assertEquals(3, knownServerState.skipped)

        // CE (#779): the answers say that Room gave them.
        assertTrue(repository.getFavoriteItems().fromStore)
        assertTrue(repository.getLibraries().fromStore)
        assertTrue(repository.getSearchItems("mov", null).fromStore)
    }

    private companion object {
        const val SERVER_A = "server-a"
        const val SERVER_B = "server-b"
    }
}
