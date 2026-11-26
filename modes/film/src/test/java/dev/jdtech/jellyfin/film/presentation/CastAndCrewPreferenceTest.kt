package dev.jdtech.jellyfin.film.presentation

import android.content.SharedPreferences
import dev.jdtech.jellyfin.film.domain.VideoMetadataParser
import dev.jdtech.jellyfin.film.presentation.episode.EpisodeState
import dev.jdtech.jellyfin.film.presentation.episode.EpisodeViewModel
import dev.jdtech.jellyfin.film.presentation.movie.MovieState
import dev.jdtech.jellyfin.film.presentation.movie.MovieViewModel
import dev.jdtech.jellyfin.film.presentation.show.ShowState
import dev.jdtech.jellyfin.film.presentation.show.ShowViewModel
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.ItemImages
import dev.jdtech.jellyfin.models.ItemPerson
import dev.jdtech.jellyfin.models.ItemPersonImage
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.PersonKind
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Drives the real detail ViewModels with the "Display cast and crew" setting on and off. The phone
 * and TV screens both hide a section when its state is empty, so the state is what decides.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CastAndCrewPreferenceTest {
    private val itemId = "11111111-1111-1111-1111-111111111111".toItemId()

    private val actor = person("Actor", PersonKind.ACTOR)
    private val director = person("Director", PersonKind.DIRECTOR)
    private val writer = person("Writer", PersonKind.WRITER)
    private val people = listOf(actor, director, writer)

    /** #809: Main and the ViewModels' cast and crew work on one test scheduler, no real thread. */
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
    fun `movie shows the cast by default, and the director and writers once asked for`() =
        runTest(dispatcher) {
            val byDefault = loadMovie(appPreferences(stored = null))

            assertEquals(listOf(actor), byDefault.actors)
            // #510: the credits under the overview are off until turned on.
            assertNull(byDefault.director)
            assertTrue(byDefault.writers.isEmpty())

            val withCredits = loadMovie(appPreferences(stored = null, credits = true))

            assertEquals(listOf(actor), withCredits.actors)
            assertEquals(director, withCredits.director)
            assertEquals(listOf(writer), withCredits.writers)
        }

    @Test
    fun `movie credits stay hidden while cast and crew is off`() =
        runTest(dispatcher) {
            val state = loadMovie(appPreferences(stored = false, credits = true))

            assertTrue(state.actors.isEmpty())
            assertNull(state.director)
            assertTrue(state.writers.isEmpty())
        }

    @Test
    fun `movie hides cast and crew when turned off`() =
        runTest(dispatcher) {
            val state = loadMovie(appPreferences(stored = false))

            assertTrue(state.actors.isEmpty())
            assertNull(state.director)
            assertTrue(state.writers.isEmpty())
        }

    @Test
    fun `show shows the cast by default, and the director and writers once asked for`() =
        runTest(dispatcher) {
            val byDefault = loadShow(appPreferences(stored = null))

            assertEquals(listOf(actor), byDefault.actors)
            assertNull(byDefault.director)
            assertTrue(byDefault.writers.isEmpty())

            val withCredits = loadShow(appPreferences(stored = null, credits = true))

            assertEquals(listOf(actor), withCredits.actors)
            assertEquals(director, withCredits.director)
            assertEquals(listOf(writer), withCredits.writers)
        }

    @Test
    fun `show hides cast and crew when turned off`() =
        runTest(dispatcher) {
            val state = loadShow(appPreferences(stored = false))

            assertTrue(state.actors.isEmpty())
            assertNull(state.director)
            assertTrue(state.writers.isEmpty())
        }

    @Test
    fun `episode shows actors by default`() =
        runTest(dispatcher) {
            assertEquals(listOf(actor), loadEpisode(appPreferences(stored = null)).actors)
        }

    @Test
    fun `episode hides actors when turned off`() =
        runTest(dispatcher) {
            assertTrue(loadEpisode(appPreferences(stored = false)).actors.isEmpty())
        }

    private fun TestScope.loadMovie(preferences: AppPreferences): MovieState {
        val repository = repository()
        val viewModel =
            MovieViewModel(
                repository.role(),
                repository.role(),
                repository.role(),
                { ServerCapabilities.ALL },
                preferences,
                VideoMetadataParser,
                defaultDispatcher = dispatcher,
            )
        viewModel.loadMovie(itemId)
        advanceUntilIdle()
        return viewModel.state.value.also { assertNotNull(it.movie) }
    }

    private fun TestScope.loadShow(preferences: AppPreferences): ShowState {
        val repository = repository()
        val viewModel =
            ShowViewModel(
                catalog = repository.role(),
                userData = repository.role(),
                extras = repository.role(),
                capabilities = { ServerCapabilities.ALL },
                appPreferences = preferences,
                downloadDeleter = DownloadDeleter.None,
                downloadQueue = DownloadQueuePort.None,
                downloadList = noDownloads(),
                defaultDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            )
        viewModel.loadShow(itemId)
        advanceUntilIdle()
        return viewModel.state.value.also { assertNotNull(it.show) }
    }

    private fun TestScope.loadEpisode(preferences: AppPreferences): EpisodeState {
        val repository = repository()
        val viewModel =
            EpisodeViewModel(
                repository.role(),
                repository.role(),
                { ServerCapabilities.ALL },
                preferences,
                VideoMetadataParser,
                defaultDispatcher = dispatcher,
            )
        viewModel.loadEpisode(itemId)
        advanceUntilIdle()
        return viewModel.state.value.also { assertNotNull(it.episode) }
    }

    private fun person(name: String, type: PersonKind) =
        ItemPerson(
            id = UUID.nameUUIDFromBytes(name.toByteArray()).toString().toItemId(),
            name = name,
            type = type,
            role = "",
            image = ItemPersonImage(uri = null, blurHash = null),
        )

    private fun repository(): FakeRoles =
        FakeRoles(ItemCatalog::class, UserDataUpdates::class, ItemExtras::class) { _, method, _ ->
            // #769: Jellyfin can do everything.
            when (method.kotlinName) {
                "getMovie" -> movie()
                "getShow" -> show()
                "getEpisode" -> episode()
                "getNextUp",
                "getSeasons",
                "getEpisodes" -> emptyList<Any>()
                else ->
                    throw NotImplementedError(
                        "${method.declaringClass.simpleName}.${method.kotlinName}"
                    )
            }
        }

    private fun movie() =
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
            people = people,
            genres = emptyList(),
            communityRating = null,
            officialRating = null,
            status = "",
            productionYear = null,
            endDate = null,
            trailer = null,
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
            canDownload = false,
            unplayedItemCount = null,
            genres = emptyList(),
            people = people,
            runtimeTicks = 0L,
            communityRating = null,
            officialRating = null,
            status = "",
            productionYear = null,
            endDate = null,
            trailer = null,
            images = ItemImages(),
        )

    private fun episode() =
        Episode(
            id = itemId,
            name = "Episode",
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
            seriesId = itemId,
            seriesName = "Show",
            seasonId = itemId,
            seasonName = null,
            communityRating = null,
            people = people,
            missing = false,
            images = ItemImages(),
            chapters = emptyList(),
            trickplayInfo = null,
        )

    /** Preferences where only the cast and crew switch may be stored; null means never touched. */
    private fun appPreferences(stored: Boolean?, credits: Boolean? = null): AppPreferences {
        val preferences =
            fakeProxy(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.kotlinName) {
                    "getBoolean" ->
                        when {
                            args!![0] == "pref_display_cast_and_crew" && stored != null -> stored
                            args[0] == "pref_detail_credits" && credits != null -> credits
                            else -> args[1]
                        }
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

    /** A download store with nothing downloaded, for the show page's download counts. */
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

    private inline fun <reified T : Any> unused(): T =
        fakeProxy(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            throw NotImplementedError("${T::class.java.simpleName}.${method.kotlinName}")
        }
            as T
}
