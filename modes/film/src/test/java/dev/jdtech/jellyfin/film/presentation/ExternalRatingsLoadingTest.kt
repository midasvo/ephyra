package dev.jdtech.jellyfin.film.presentation

import android.content.SharedPreferences
import dev.jdtech.jellyfin.domain.ratings.ExternalIdType
import dev.jdtech.jellyfin.domain.ratings.ExternalRating
import dev.jdtech.jellyfin.domain.ratings.RatedKind
import dev.jdtech.jellyfin.domain.ratings.RatingSource
import dev.jdtech.jellyfin.domain.ratings.RatingsLookup
import dev.jdtech.jellyfin.film.domain.VideoMetadataParser
import dev.jdtech.jellyfin.film.presentation.movie.MovieViewModel
import dev.jdtech.jellyfin.film.presentation.show.ShowViewModel
import dev.jdtech.jellyfin.models.ExternalIds
import dev.jdtech.jellyfin.models.ItemImages
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.Show
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.ratings.ExternalRatings
import dev.jdtech.jellyfin.ratings.RatingsKeyStatus
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
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * CE (#331): the movie and show pages keep room for the external ratings on their first load, ask
 * for them only once they are shown (loadRelated), once per item, and never while they are off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalRatingsLoadingTest {
    private val itemId = "22222222-2222-2222-2222-222222222222".toItemId()
    private val switchedOff = mutableSetOf<String>()

    private val imdb = ExternalRating(RatingSource.IMDB, 7.8f)
    private val tomatoes = ExternalRating(RatingSource.ROTTEN_TOMATOES, 91f)

    private inner class FakeRatings : ExternalRatings {
        var on = true
        /** False while the service may not be asked (a failure, a 429, a rejected key). */
        var allowed = true
        var answer = listOf(imdb, tomatoes)
        /** Runs while a lookup is under way, before it answers. */
        var during: () -> Unit = {}
        /** Awaited by a lookup before it answers, when set. */
        var gate: CompletableDeferred<Unit>? = null
        val asked: MutableList<RatingsLookup> = Collections.synchronizedList(mutableListOf())

        override fun isOn() = on

        override fun canRequest(lookup: RatingsLookup) = on && allowed

        override fun mightHaveRatings(lookup: RatingsLookup) = on

        override suspend fun ratings(lookup: RatingsLookup): List<ExternalRating> {
            asked += lookup
            during()
            gate?.await()
            return if (on && allowed) answer else emptyList()
        }

        override suspend fun checkKey(apiKey: String) = RatingsKeyStatus.VALID
    }

    private val ratings = FakeRatings()

    /**
     * #809: Main and the ViewModels' cast and crew work on one test scheduler. On the real
     * Dispatchers.Default a reload the test did not wait for came back to Main after tearDown had
     * reset it, and failed the next runTest in the module (ServerAwayReloadTest).
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

    private fun TestScope.loaded(movie: Movie = movie()): MovieViewModel {
        val repository = repository(movie)
        val viewModel =
            MovieViewModel(
                repository.role(),
                repository.role(),
                repository.role(),
                { ServerCapabilities.ALL },
                appPreferences(),
                VideoMetadataParser,
                externalRatings = ratings,
                defaultDispatcher = dispatcher,
            )
        viewModel.loadMovie(itemId)
        advanceUntilIdle()
        assertNotNull(viewModel.state.value.movie)
        return viewModel
    }

    @Test
    fun `switched off while the lookup runs, its answer is dropped`() =
        runTest(dispatcher) {
            ratings.during = { ratings.on = false }
            val viewModel = loaded()
            viewModel.loadRelated(itemId)
            advanceUntilIdle()
            assertEquals(ExternalRatingsState.Hidden, viewModel.state.value.externalRatings)
            assertEquals(1, ratings.asked.size)
        }

    @Test
    fun `a lookup the service could not answer is tried again on a reload`() =
        runTest(dispatcher) {
            ratings.allowed = false
            val viewModel = loaded()
            viewModel.loadRelated(itemId)
            advanceUntilIdle()
            assertEquals(ExternalRatingsState.Hidden, viewModel.state.value.externalRatings)

            ratings.allowed = true
            viewModel.loadMovie(itemId)
            advanceUntilIdle()
            assertEquals(
                ExternalRatingsState.Shown(listOf(imdb, tomatoes)),
                viewModel.state.value.externalRatings,
            )
            assertEquals(2, ratings.asked.size)
        }

    @Test
    fun `a title the service has nothing for is not asked again on a reload`() =
        runTest(dispatcher) {
            ratings.answer = emptyList()
            val viewModel = loaded()
            viewModel.loadRelated(itemId)
            advanceUntilIdle()
            viewModel.loadRelated(itemId)
            advanceUntilIdle()
            // #809: the reload runs to its end before the test does, its cast and crew included.
            viewModel.loadMovie(itemId)
            advanceUntilIdle()
            assertNotNull(viewModel.state.value.movie)
            assertEquals(1, ratings.asked.size)
            assertEquals(ExternalRatingsState.Hidden, viewModel.state.value.externalRatings)
        }

    @Test
    fun `the Rotten Tomatoes switch applies again on a reload`() =
        runTest(dispatcher) {
            val viewModel = loaded()
            viewModel.loadRelated(itemId)
            advanceUntilIdle()
            assertIs<ExternalRatingsState.Shown>(viewModel.state.value.externalRatings)

            switchedOff += "pref_detail_critic_rating"
            viewModel.loadMovie(itemId)
            advanceUntilIdle()
            assertEquals(
                ExternalRatingsState.Shown(listOf(imdb)),
                viewModel.state.value.externalRatings,
            )

            switchedOff.clear()
            viewModel.loadMovie(itemId)
            advanceUntilIdle()
            assertEquals(
                ExternalRatingsState.Shown(listOf(imdb, tomatoes)),
                viewModel.state.value.externalRatings,
            )
            assertEquals(1, ratings.asked.size)
        }

    @Test
    fun `a lookup that comes back after a reload uses the reload's Rotten Tomatoes rule`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            ratings.gate = gate
            val updates = mutableListOf<ExternalRatingsState>()
            val loader =
                ExternalRatingsLoader(
                    ratings,
                    CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                ) {
                    updates += it
                }
            val lookup = movie().ratingsLookup()
            val first =
                loader.onItemLoaded(
                    itemId,
                    lookup,
                    ExternalRatingsState.Hidden,
                    rottenTomatoes = true,
                )
            loader.load(itemId, lookup, rottenTomatoes = true)
            // The page is loaded again while the lookup waits, with the score switched off.
            loader.onItemLoaded(itemId, lookup, first, rottenTomatoes = false)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                listOf<ExternalRatingsState>(ExternalRatingsState.Shown(listOf(imdb))),
                updates,
            )
        }

    @Test
    fun `the first load keeps room and asks for nothing`() =
        runTest(dispatcher) {
            val viewModel = loaded()
            assertEquals(ExternalRatingsState.Pending, viewModel.state.value.externalRatings)
            assertTrue(ratings.asked.isEmpty())
        }

    @Test
    fun `the ratings come once the page is shown, once per movie`() =
        runTest(dispatcher) {
            val viewModel = loaded()
            viewModel.loadRelated(itemId)
            advanceUntilIdle()
            assertEquals(
                ExternalRatingsState.Shown(listOf(imdb, tomatoes)),
                viewModel.state.value.externalRatings,
            )
            assertEquals(
                listOf(RatingsLookup(ExternalIdType.TMDB, RatedKind.MOVIE, "603")),
                ratings.asked.toList(),
            )

            viewModel.loadRelated(itemId)
            advanceUntilIdle()
            // #809: the reload runs to its end before the test does, its cast and crew included.
            viewModel.loadMovie(itemId)
            advanceUntilIdle()
            assertNotNull(viewModel.state.value.movie)
            assertEquals(1, ratings.asked.size)
            assertEquals(
                ExternalRatingsState.Shown(listOf(imdb, tomatoes)),
                viewModel.state.value.externalRatings,
            )
        }

    @Test
    fun `off, nothing is asked and nothing is kept`() =
        runTest(dispatcher) {
            ratings.on = false
            val viewModel = loaded()
            viewModel.loadRelated(itemId)
            advanceUntilIdle()
            assertEquals(ExternalRatingsState.Hidden, viewModel.state.value.externalRatings)
            assertTrue(ratings.asked.isEmpty())
        }

    @Test
    fun `a movie without an IMDb or TMDB id asks for nothing`() =
        runTest(dispatcher) {
            val viewModel = loaded(movie(ExternalIds(tvdb = "1")))
            viewModel.loadRelated(itemId)
            advanceUntilIdle()
            assertEquals(ExternalRatingsState.Hidden, viewModel.state.value.externalRatings)
            assertTrue(ratings.asked.isEmpty())
        }

    @Test
    fun `no ratings hides the row`() =
        runTest(dispatcher) {
            ratings.answer = emptyList()
            val viewModel = loaded()
            viewModel.loadRelated(itemId)
            advanceUntilIdle()
            assertEquals(ExternalRatingsState.Hidden, viewModel.state.value.externalRatings)
            assertEquals(1, ratings.asked.size)
        }

    @Test
    fun `the server's own Rotten Tomatoes score is not shown twice`() =
        runTest(dispatcher) {
            val viewModel = loaded(movie().copy(criticRating = 90f))
            viewModel.loadRelated(itemId)
            advanceUntilIdle()
            assertEquals(
                ExternalRatingsState.Shown(listOf(imdb)),
                viewModel.state.value.externalRatings,
            )
        }

    @Test
    fun `Rotten Tomatoes switched off hides the service's score too`() =
        runTest(dispatcher) {
            switchedOff += "pref_detail_critic_rating"
            val viewModel = loaded()
            viewModel.loadRelated(itemId)
            advanceUntilIdle()
            assertEquals(
                ExternalRatingsState.Shown(listOf(imdb)),
                viewModel.state.value.externalRatings,
            )
        }

    @Test
    fun `switched off later, a reload hides the row`() =
        runTest(dispatcher) {
            val viewModel = loaded()
            viewModel.loadRelated(itemId)
            advanceUntilIdle()
            assertIs<ExternalRatingsState.Shown>(viewModel.state.value.externalRatings)
            ratings.on = false
            viewModel.loadMovie(itemId)
            advanceUntilIdle()
            assertEquals(ExternalRatingsState.Hidden, viewModel.state.value.externalRatings)
            assertEquals(1, ratings.asked.size)
        }

    @Test
    fun `a show is looked up as a show, by its TVDB id when it has no other`() =
        runTest(dispatcher) {
            val viewModel = showViewModel()
            viewModel.loadShow(itemId)
            advanceUntilIdle()
            assertNotNull(viewModel.state.value.show)
            assertEquals(ExternalRatingsState.Pending, viewModel.state.value.externalRatings)
            assertTrue(ratings.asked.isEmpty())

            viewModel.loadRelated(itemId)
            advanceUntilIdle()
            assertIs<ExternalRatingsState.Shown>(viewModel.state.value.externalRatings)
            assertEquals(
                listOf(RatingsLookup(ExternalIdType.TVDB, RatedKind.SHOW, "81189")),
                ratings.asked.toList(),
            )
        }

    @Test
    fun `the downloads-only show page asks for nothing`() =
        runTest(dispatcher) {
            val viewModel = showViewModel()
            viewModel.loadShow(itemId, downloadsOnly = true)
            advanceUntilIdle()
            assertNotNull(viewModel.state.value.show)
            viewModel.loadRelated(itemId)
            advanceUntilIdle()
            assertEquals(ExternalRatingsState.Hidden, viewModel.state.value.externalRatings)
            assertTrue(ratings.asked.isEmpty())
        }

    private fun repository(movie: Movie): FakeRoles =
        FakeRoles(ItemCatalog::class, UserDataUpdates::class, ItemExtras::class) { _, method, _ ->
            when (method.kotlinName) {
                "getMovie" -> movie
                "getShow" -> show()
                "getNextUp",
                "getSeasons",
                "getEpisodes",
                "getItemCollections",
                "getSimilarItems",
                "getExtras" -> emptyList<Any>()
                else ->
                    throw NotImplementedError(
                        "${method.declaringClass.simpleName}.${method.kotlinName}"
                    )
            }
        }

    private fun showViewModel(): ShowViewModel {
        val repository = repository(movie())
        val preferences = appPreferences()
        return ShowViewModel(
            catalog = repository.role(),
            userData = repository.role(),
            extras = repository.role(),
            capabilities = { ServerCapabilities.ALL },
            appPreferences = preferences,
            downloadDeleter = DownloadDeleter.None,
            downloadQueue = DownloadQueuePort.None,
            downloadList = noDownloads(),
            externalRatings = ratings,
            defaultDispatcher = dispatcher,
            ioDispatcher = dispatcher,
        )
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

    private fun movie(externalIds: ExternalIds = ExternalIds(tmdb = "603", imdb = "tt0133093")) =
        Movie(
            id = itemId,
            name = "Movie",
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
            externalIds = externalIds,
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
            externalIds = ExternalIds(tvdb = "81189"),
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
