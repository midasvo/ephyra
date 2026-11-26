package dev.jdtech.jellyfin.film.presentation.season

import android.content.SharedPreferences
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.models.ItemImages
import dev.jdtech.jellyfin.models.Season
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.repository.DownloadDeleter
import dev.jdtech.jellyfin.repository.DownloadQueuePort
import dev.jdtech.jellyfin.repository.ItemCatalog
import dev.jdtech.jellyfin.repository.ItemExtras
import dev.jdtech.jellyfin.repository.UserDataUpdates
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.testing.FakeRoles
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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

/** CE (#505): the season page can tell a season without episodes from one that is still loading. */
@OptIn(ExperimentalCoroutinesApi::class)
class SeasonLoadingTest {
    private val seriesId = "11111111-1111-1111-1111-111111111111".toItemId()
    private val seasonId = "22222222-2222-2222-2222-222222222223".toItemId()

    private var episodesFail = false
    private var storedEpisodes = emptyList<Any>()

    /** #955: Main on a test scheduler, so the test waits for the whole load, and no real thread. */
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
    fun `a season without episodes ends loaded with no episodes`() =
        runTest(dispatcher) {
            val state = loaded { it.loadSeason(seasonId, seriesId = seriesId) }

            assertFalse(state.isLoading)
            assertNotNull(state.season)
            assertTrue(state.episodes.isEmpty())
            assertEquals(null, state.error)
        }

    @Test
    fun `the season is loading until its episodes have come`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val seen = mutableListOf<SeasonState>()
            // Unconfined, so it sees every state the load goes through.
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.state.collect { seen.add(it) }
            }
            viewModel.loadSeason(seasonId, seriesId = seriesId)
            advanceUntilIdle()
            assertNotNull(viewModel.state.value.season)

            // Every state before the last one, the season shown early included, is still loading.
            val loading = seen.drop(1).dropLast(1)
            assertTrue(loading.isNotEmpty(), message = "no state while loading: $seen")
            assertTrue(
                loading.all { it.isLoading },
                message = "not loading before the episodes came: $seen",
            )
            assertFalse(seen.last().isLoading)
        }

    @Test
    fun `a failed load is no longer loading`() =
        runTest(dispatcher) {
            episodesFail = true
            val state = loaded { it.loadSeason(seasonId, seriesId = seriesId) }

            assertNotNull(state.error)
            assertFalse(state.isLoading)
        }

    @Test
    fun `episodes from the server are not stored only`() =
        runTest(dispatcher) {
            storedEpisodes = listOf(dummyEpisode)
            val state = loaded { it.loadSeason(seasonId, seriesId = seriesId) }

            assertFalse(state.isLoading)
            assertFalse(state.storedOnly, message = "the server's list keeps a removed download")
        }

    @Test
    fun `the stored episodes stand in for a server that fails, and say so`() =
        runTest(dispatcher) {
            // #281: without Offline Mode, a removed download leaves such a list at once.
            storedEpisodes = listOf(dummyEpisode)
            episodesFail = true
            val state = loaded { it.loadSeason(seasonId, seriesId = seriesId) }

            assertFalse(state.isLoading)
            assertEquals(listOf(dummyEpisode.id), state.episodes.map { it.id })
            assertTrue(state.storedOnly, message = "the list is the stored episodes")
        }

    @Test
    fun `the downloads-only page is stored only`() =
        runTest(dispatcher) {
            storedEpisodes = listOf(dummyEpisode)
            val state = loaded {
                it.loadSeason(seasonId, downloadsOnly = true, seriesId = seriesId)
            }

            assertFalse(state.isLoading)
            assertTrue(state.storedOnly)
        }

    /** Runs [load] on a new view model and waits until it has ended. */
    private fun TestScope.loaded(load: (SeasonViewModel) -> Unit): SeasonState {
        val viewModel = viewModel()
        load(viewModel)
        advanceUntilIdle()
        return viewModel.state.value
    }

    private fun viewModel(): SeasonViewModel {
        val repository = repository()
        return SeasonViewModel(
            catalog = repository.role(),
            userData = repository.role(),
            capabilities = { ServerCapabilities.ALL },
            downloadDeleter = DownloadDeleter.None,
            downloadQueue = DownloadQueuePort.None,
        )
    }

    private fun repository(): FakeRoles =
        FakeRoles(ItemCatalog::class, UserDataUpdates::class, ItemExtras::class) { _, method, args
            ->
            // #769: Jellyfin can do everything.
            when (method.kotlinName) {
                "getSeason" -> season()
                "getEpisodes" -> {
                    val offline = args!![args.size - 2] as Boolean
                    if (!offline && episodesFail) throw IllegalStateException("No connection")
                    if (offline) storedEpisodes else emptyList<Any>()
                }
                else ->
                    throw NotImplementedError(
                        "${method.declaringClass.simpleName}.${method.kotlinName}"
                    )
            }
        }

    private fun season() =
        Season(
            id = seasonId,
            name = "Season 3",
            seriesId = seriesId,
            seriesName = "Test Show",
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            indexNumber = 3,
            episodes = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            unplayedItemCount = null,
            images = ItemImages(),
        )

    private fun appPreferences(): AppPreferences {
        val preferences =
            fakeProxy(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.kotlinName) {
                    "getBoolean",
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
