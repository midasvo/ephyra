package dev.jdtech.jellyfin.film.presentation

import android.content.SharedPreferences
import dev.jdtech.jellyfin.film.domain.VideoMetadataParser
import dev.jdtech.jellyfin.film.presentation.movie.MovieViewModel
import dev.jdtech.jellyfin.film.presentation.show.ShowViewModel
import dev.jdtech.jellyfin.models.BoxSet
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemImages
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.Show
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.repository.DownloadDeleter
import dev.jdtech.jellyfin.repository.DownloadList
import dev.jdtech.jellyfin.repository.DownloadQueuePort
import dev.jdtech.jellyfin.repository.ItemCatalog
import dev.jdtech.jellyfin.repository.ItemExtras
import dev.jdtech.jellyfin.repository.UserDataUpdates
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.testing.FakeRoles
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import java.io.IOException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * CE (#332): the extras, collections and "More like this" rows are requested only when the page
 * asks for them after it is shown, once per item, and a failing request only leaves its row empty.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelatedItemsLoadingTest {
    private val itemId = "11111111-1111-1111-1111-111111111111".toItemId()
    private val calls = mutableListOf<String>()
    private val extraArgs = mutableListOf<Pair<Boolean, Boolean>>()
    private val failing = mutableSetOf<String>()
    /** Boolean preferences the user turned off; every other preference has its default. */
    private val switchedOff = mutableSetOf<String>()
    /** What getMovie answers from now on, in place of the movie the view model was made with. */
    @Volatile private var answer: Movie? = null
    /** #769: what the server can do; Jellyfin can do everything. */
    @Volatile private var capabilities = ServerCapabilities.ALL

    private val extra = movie(UUID.randomUUID().toString().toItemId(), "Behind the scenes")
    private val similar = movie(UUID.randomUUID().toString().toItemId(), "Similar")
    private val collection =
        BoxSet(id = UUID.randomUUID().toString().toItemId(), name = "Saga", images = ItemImages())

    /**
     * #809: Main and the ViewModels' own work (the cast and crew, Room) on one test scheduler: no
     * load can still be on a real thread when the test ends and come back to a Main that tearDown
     * has reset.
     */
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loading the movie asks for none of the related rows`() =
        runTest(dispatcher) {
            val viewModel =
                movieViewModel(movie(itemId, "Movie", localTrailers = 1, specialFeatures = 2))
            viewModel.loadMovie(itemId)
            advanceUntilIdle()
            assertNotNull(viewModel.state.value.movie)

            assertEquals(listOf("getMovie"), calls)
            assertEquals(RelatedItems(), viewModel.state.value.related)
        }

    @Test
    fun `the movie's related rows load once, each from its own request`() =
        runTest(dispatcher) {
            val viewModel =
                movieViewModel(movie(itemId, "Movie", localTrailers = 1, specialFeatures = 2))
            viewModel.loadMovie(itemId)
            advanceUntilIdle()
            assertNotNull(viewModel.state.value.movie)

            viewModel.loadRelated(itemId)
            advanceUntilIdle()
            val related = viewModel.state.value.related

            assertEquals(listOf(extra), related.extras)
            assertEquals(listOf(collection), related.collections)
            assertEquals(listOf(similar), related.similar)
            assertEquals(listOf(true to true), extraArgs)

            calls.clear()
            viewModel.loadRelated(itemId)
            advanceUntilIdle()
            assertTrue(calls.isEmpty())
        }

    @Test
    fun `no extras request when the movie has none`() =
        runTest(dispatcher) {
            val viewModel =
                movieViewModel(movie(itemId, "Movie", localTrailers = 0, specialFeatures = 0))
            viewModel.loadMovie(itemId)
            advanceUntilIdle()
            assertNotNull(viewModel.state.value.movie)

            viewModel.loadRelated(itemId)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.related.similar.isNotEmpty())

            assertTrue("getExtras" !in calls)
        }

    @Test
    fun `a failing request leaves only its own row empty`() =
        runTest(dispatcher) {
            failing += "getSimilarItems"
            failing += "getItemCollections"
            val viewModel =
                movieViewModel(movie(itemId, "Movie", localTrailers = 1, specialFeatures = null))
            viewModel.loadMovie(itemId)
            advanceUntilIdle()
            assertNotNull(viewModel.state.value.movie)

            viewModel.loadRelated(itemId)
            advanceUntilIdle()
            val state = viewModel.state.value

            assertTrue(state.related.extras.isNotEmpty())
            assertTrue(state.related.similar.isEmpty())
            assertTrue(state.related.collections.isEmpty())
            assertNull(state.error)
            // An unknown special feature count still asks for them.
            assertEquals(listOf(true to true), extraArgs)
        }

    @Test
    fun `nothing is asked before the movie is there`() =
        runTest(dispatcher) {
            val viewModel = movieViewModel(movie(itemId, "Movie"))
            viewModel.loadRelated(itemId)
            advanceUntilIdle()

            assertTrue(calls.isEmpty())
        }

    @Test
    fun `the show asks for extras and similar items, not collections`() =
        runTest(dispatcher) {
            val viewModel = showViewModel()
            viewModel.loadShow(itemId)
            advanceUntilIdle()
            assertNotNull(viewModel.state.value.show)
            calls.clear()

            viewModel.loadRelated(itemId)
            advanceUntilIdle()
            val related = viewModel.state.value.related

            assertTrue(related.extras.isNotEmpty())
            assertEquals(listOf(similar), related.similar)
            assertTrue(related.collections.isEmpty())
            assertTrue("getItemCollections" !in calls)
            assertEquals(listOf(false to true), extraArgs)
        }

    @Test
    fun `rows switched off in the settings are never requested`() =
        runTest(dispatcher) {
            switchedOff += listOf("pref_detail_extras", "pref_detail_similar")
            val viewModel =
                movieViewModel(movie(itemId, "Movie", localTrailers = 1, specialFeatures = 2))
            viewModel.loadMovie(itemId)
            advanceUntilIdle()
            assertNotNull(viewModel.state.value.movie)

            viewModel.loadRelated(itemId)
            advanceUntilIdle()
            val related = viewModel.state.value.related

            assertTrue(related.collections.isNotEmpty())
            assertEquals(listOf("getMovie", "getItemCollections"), calls)
            assertTrue(related.extras.isEmpty() && related.similar.isEmpty())
        }

    @Test
    fun `#769 rows the server cannot fill are never requested`() =
        runTest(dispatcher) {
            capabilities =
                ServerCapabilities.ALL.copy(
                    extras = false,
                    itemCollections = false,
                    similarItems = false,
                )
            val viewModel =
                movieViewModel(movie(itemId, "Movie", localTrailers = 1, specialFeatures = 2))
            viewModel.loadMovie(itemId)
            advanceUntilIdle()
            assertNotNull(viewModel.state.value.movie)

            viewModel.loadRelated(itemId)
            advanceUntilIdle()

            assertEquals(listOf("getMovie"), calls)
            assertEquals(RelatedItems(), viewModel.state.value.related)
        }

    @Test
    fun `#769 each row follows its own capability`() =
        runTest(dispatcher) {
            capabilities =
                ServerCapabilities.ALL.copy(similarItems = false, cappedDownloadTranscode = false)
            val viewModel = showViewModel()
            viewModel.loadShow(itemId)
            advanceUntilIdle()
            val loaded = viewModel.state.value
            assertNotNull(loaded.show)
            assertEquals(false, loaded.cappedDownloads)
            calls.clear()

            viewModel.loadRelated(itemId)
            advanceUntilIdle()
            val related = viewModel.state.value.related

            assertTrue(related.extras.isNotEmpty())
            assertTrue(related.similar.isEmpty())
            assertTrue("getSimilarItems" !in calls)
        }

    @Test
    fun `#769 the quality dialog offers the cap only where the server can transcode`() =
        runTest(dispatcher) {
            val capped = movieViewModel(movie(itemId, "Movie"))
            capped.loadMovie(itemId)
            advanceUntilIdle()
            assertNotNull(capped.state.value.movie)
            assertTrue(capped.state.value.cappedDownloads)

            capabilities = ServerCapabilities.ALL.copy(cappedDownloadTranscode = false)
            val original = movieViewModel(movie(itemId, "Movie"))
            original.loadMovie(itemId)
            advanceUntilIdle()
            val state = original.state.value

            assertNotNull(state.movie)
            assertEquals(false, state.cappedDownloads)
        }

    @Test
    fun `metadata switched off in the settings is cleared before the page gets it`() =
        runTest(dispatcher) {
            switchedOff += listOf("pref_detail_tagline", "pref_detail_watch_history")
            val viewModel =
                movieViewModel(
                    movie(itemId, "Movie")
                        .copy(tagline = "In space…", criticRating = 98f, playCount = 3)
                )
            viewModel.loadMovie(itemId)
            advanceUntilIdle()
            val movie = assertNotNull(viewModel.state.value.movie)

            assertNull(movie.tagline)
            assertEquals(0, movie.playCount)
            assertEquals(98f, movie.criticRating)
        }

    @Test
    fun `a row switched off goes away when the page reloads`() =
        runTest(dispatcher) {
            val viewModel = movieViewModel(movie(itemId, "Movie"))
            viewModel.loadMovie(itemId)
            advanceUntilIdle()
            assertNotNull(viewModel.state.value.movie)
            viewModel.loadRelated(itemId)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.related.similar.isNotEmpty())
            assertTrue(viewModel.state.value.related.collections.isNotEmpty())

            switchedOff += "pref_detail_similar"
            calls.clear()
            viewModel.loadMovie(itemId)
            // The whole reload, the rows it asks for again included.
            advanceUntilIdle()
            val related = viewModel.state.value.related

            assertTrue(related.similar.isEmpty())
            assertTrue("getItemCollections" in calls)
            assertEquals(listOf(collection), related.collections)
            assertTrue("getSimilarItems" !in calls)
        }

    @Test
    fun `a row switched on is requested when the page reloads`() =
        runTest(dispatcher) {
            switchedOff += "pref_detail_similar"
            val viewModel = movieViewModel(movie(itemId, "Movie"))
            viewModel.loadMovie(itemId)
            advanceUntilIdle()
            assertNotNull(viewModel.state.value.movie)
            viewModel.loadRelated(itemId)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.related.collections.isNotEmpty())

            switchedOff.clear()
            viewModel.loadMovie(itemId)
            advanceUntilIdle()

            assertEquals(listOf(similar), viewModel.state.value.related.similar)
        }

    @Test
    fun `a reload with the same switches asks for no row again`() =
        runTest(dispatcher) {
            val viewModel = movieViewModel(movie(itemId, "Movie"))
            viewModel.loadMovie(itemId)
            advanceUntilIdle()
            assertNotNull(viewModel.state.value.movie)
            viewModel.loadRelated(itemId)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.related.similar.isNotEmpty())

            // The reload's answer can be told apart, so the test sees that the reload ran to its
            // end, its call of loadRelated included (#809).
            answer = movie(itemId, "Movie, reloaded")
            calls.clear()
            viewModel.loadMovie(itemId)
            advanceUntilIdle()
            assertEquals("Movie, reloaded", viewModel.state.value.movie?.name)

            assertEquals(listOf("getMovie"), calls)
        }

    private fun movieViewModel(movie: Movie): MovieViewModel {
        val repository = repository(movie)
        return MovieViewModel(
            repository.role(),
            repository.role(),
            repository.role(),
            { capabilities },
            appPreferences(),
            VideoMetadataParser,
            defaultDispatcher = dispatcher,
        )
    }

    private fun showViewModel(): ShowViewModel {
        val repository = repository(movie(itemId, "unused"))
        val preferences = appPreferences()
        return ShowViewModel(
            catalog = repository.role(),
            userData = repository.role(),
            extras = repository.role(),
            capabilities = { capabilities },
            appPreferences = preferences,
            downloadDeleter = DownloadDeleter.None,
            downloadQueue = DownloadQueuePort.None,
            downloadList = noDownloads(),
            defaultDispatcher = dispatcher,
            ioDispatcher = dispatcher,
        )
    }

    private fun repository(movie: Movie): FakeRoles =
        FakeRoles(ItemCatalog::class, UserDataUpdates::class, ItemExtras::class) { _, method, args
            ->
            // #769: not a request of the server, so not in [calls].
            synchronized(calls) { calls.add(method.kotlinName) }
            if (method.kotlinName in failing) throw IOException("${method.kotlinName} failed")
            when (method.kotlinName) {
                "getMovie" -> answer ?: movie
                "getShow" -> show()
                "getNextUp",
                "getSeasons",
                "getEpisodes" -> emptyList<Any>()
                "getExtras" -> {
                    synchronized(calls) {
                        extraArgs.add(args!![1] as Boolean to args[2] as Boolean)
                    }
                    listOf(extra)
                }
                "getItemCollections" -> listOf(collection)
                // The server may return the item itself; the row leaves it out.
                "getSimilarItems" -> listOf(similar, movie(itemId, "Self"))
                else ->
                    throw NotImplementedError(
                        "${method.declaringClass.simpleName}.${method.kotlinName}"
                    )
            }
        }

    private fun noDownloads(): DownloadList =
        fakeProxy(DownloadList::class.java.classLoader, arrayOf(DownloadList::class.java)) {
            _,
            method,
            _ ->
            when (method.kotlinName) {
                "seasonEpisodeCounts" -> emptyList<Any>()
                else -> throw NotImplementedError("DownloadList.${method.kotlinName}")
            }
        }
            as DownloadList

    private fun movie(id: ItemId, name: String, localTrailers: Int = 0, specialFeatures: Int? = 0) =
        Movie(
            id = id,
            name = name,
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            runtimeTicks = 0L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            people = emptyList(),
            genres = emptyList(),
            communityRating = null,
            officialRating = null,
            status = "",
            productionYear = null,
            endDate = null,
            trailer = null,
            localTrailerCount = localTrailers,
            specialFeatureCount = specialFeatures,
            images = ItemImages(),
            chapters = emptyList(),
            trickplayInfo = null,
        )

    private fun show() =
        Show(
            id = itemId,
            name = "Show",
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            seasons = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = true,
            unplayedItemCount = null,
            genres = emptyList(),
            people = emptyList(),
            runtimeTicks = 0L,
            communityRating = null,
            officialRating = null,
            status = "",
            productionYear = null,
            endDate = null,
            trailer = null,
            specialFeatureCount = 3,
            images = ItemImages(),
        )

    private fun appPreferences(): AppPreferences {
        val preferences =
            fakeProxy(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.kotlinName) {
                    "getBoolean" -> if (args!![0] in switchedOff) false else args[1]
                    "getString",
                    "getInt",
                    "getLong",
                    "getFloat",
                    "getStringSet" -> args!![1]
                    else ->
                        throw UnsupportedOperationException(
                            "SharedPreferences.${method.kotlinName}"
                        )
                }
            }
                as SharedPreferences
        return AppPreferences(preferences)
    }

    private inline fun <reified T : Any> unused(): T =
        fakeProxy(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            throw NotImplementedError("${T::class.java.simpleName}.${method.kotlinName}")
        }
            as T
}
