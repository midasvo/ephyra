package dev.jdtech.jellyfin.film.presentation

import dev.jdtech.jellyfin.core.presentation.dummy.dummyCollections
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.film.presentation.favorites.FavoritesViewModel
import dev.jdtech.jellyfin.film.presentation.media.MediaViewModel
import dev.jdtech.jellyfin.film.presentation.search.SearchAction
import dev.jdtech.jellyfin.film.presentation.search.SearchViewModel
import dev.jdtech.jellyfin.repository.DataChangeEvents
import dev.jdtech.jellyfin.repository.ItemsResult
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.repository.StoredLibraries
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * CE (#775): Favorites, Media and an open search load again when the server goes away (their lists
 * are the downloads then) and when it comes back, once each time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerAwayReloadTest {
    private val dispatcher = StandardTestDispatcher()
    private val calls = mutableListOf<String>()
    private var fail = false

    /**
     * Whether the fake repository answers from Room, as the real one does while the server is away
     * or a request goes unanswered. The tests set it with what they report.
     */
    private var fromStore = false
    private var favoritesAnswers = ArrayDeque<List<Any>>()
    private var holdNext = false
    private val held = ArrayDeque<Continuation<Any?>>()

    /** Whether a slow answer is waiting, so the answer now is the newer one. */
    private fun heldOnce() = held.isNotEmpty()

    private val storedMovies = dummyCollections[0].copy(id = StoredLibraries.MOVIES)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // The rule itself

    @Test
    fun `the first report and a report before any load load nothing`() {
        val reload = ServerAwayReload()

        assertFalse(reload.onServerAway(false))
        assertFalse(reload.onServerAway(true))
    }

    @Test
    fun `a change after a load loads again, the same state again does not`() {
        val reload = ServerAwayReload()
        reload.onServerAway(false)
        reload.begin()

        assertTrue(reload.onServerAway(true))
        reload.begin()
        assertFalse(reload.onServerAway(true))
        assertTrue(reload.onServerAway(false))
    }

    @Test
    fun `a list loaded before the first report loads again at the first change`() {
        val reload = ServerAwayReload()
        reload.begin()

        assertFalse(reload.onServerAway(false))
        assertTrue(reload.onServerAway(true))
    }

    @Test
    fun `a change back before the reload started needs none`() {
        val reload = ServerAwayReload()
        reload.onServerAway(false)
        reload.begin()

        // A probe that failed once and then answered, both before the screen loaded again.
        reload.onServerAway(true)
        assertFalse(reload.onServerAway(false))
    }

    // Media

    @Test
    fun `media loads the libraries again when the server goes and comes back`() =
        runTest(dispatcher) {
            val viewModel = MediaViewModel(repository())
            fromStore = false
            viewModel.onServerAwayChanged(false)
            viewModel.loadData()
            advanceUntilIdle()
            assertEquals(1, calls.size)

            fromStore = true

            viewModel.onServerAwayChanged(true)
            advanceUntilIdle()
            assertEquals(2, calls.size)

            fromStore = true

            viewModel.onServerAwayChanged(true)
            advanceUntilIdle()
            assertEquals(2, calls.size)

            fromStore = false

            viewModel.onServerAwayChanged(false)
            advanceUntilIdle()
            assertEquals(3, calls.size)
        }

    @Test
    fun `media loads once for a change and a resume together`() =
        runTest(dispatcher) {
            val viewModel = MediaViewModel(repository())
            fromStore = false
            viewModel.onServerAwayChanged(false)
            viewModel.loadData()
            advanceUntilIdle()

            fromStore = true

            viewModel.onServerAwayChanged(true)
            // The resume's own load, or the retry of the card, in the same moment.
            viewModel.loadData()
            advanceUntilIdle()

            assertEquals(2, calls.size)
        }

    // Favorites. Their load hops to Dispatchers.Default, so the tests join the running load
    // (loadItems hands it back) instead of only advancing the test dispatcher.

    @Test
    fun `favorites load again when the server goes and once for the page's own load too`() =
        runTest(dispatcher) {
            val viewModel = FavoritesViewModel(repository())
            fromStore = false
            viewModel.onServerAwayChanged(false)
            viewModel.loadItems().join()
            assertEquals(1, calls.size)

            // Back on the tab after the server went away: the change and the screen's load.
            fromStore = true
            viewModel.onServerAwayChanged(true)
            viewModel.loadItems().join()
            assertEquals(2, calls.size)
        }

    @Test
    fun `favorites are downloads only after a load while away that succeeded`() =
        runTest(dispatcher) {
            val viewModel = FavoritesViewModel(repository())
            fromStore = false
            viewModel.onServerAwayChanged(false)
            viewModel.loadItems().join()
            assertFalse(viewModel.state.value.downloadsOnly)

            fromStore = true

            viewModel.onServerAwayChanged(true)
            viewModel.loadItems().join()
            assertTrue(viewModel.state.value.downloadsOnly)

            // Back online, the server's list again.
            fromStore = false
            viewModel.onServerAwayChanged(false)
            viewModel.loadItems().join()
            assertFalse(viewModel.state.value.downloadsOnly)
        }

    @Test
    fun `away with no favourites downloaded is an empty list, not an error`() =
        runTest(dispatcher) {
            // The repository answers the empty Room list while the server is away.
            val viewModel = FavoritesViewModel(repository())
            fromStore = true
            viewModel.onServerAwayChanged(true)
            viewModel.loadItems().join()

            val state = viewModel.state.value
            assertTrue(state.sections.isEmpty())
            assertTrue(state.downloadsOnly)
            assertEquals(null, state.error)
            assertFalse(state.isLoading)
        }

    @Test
    fun `a failed reload while away keeps the list from the server for what it is`() =
        runTest(dispatcher) {
            val viewModel = FavoritesViewModel(repository())
            fromStore = false
            viewModel.onServerAwayChanged(false)
            viewModel.loadItems().join()

            // Nothing downloaded: Room has nothing and the server's error comes through.
            fail = true
            fromStore = true
            viewModel.onServerAwayChanged(true)
            viewModel.loadItems().join()

            assertFalse(viewModel.state.value.downloadsOnly)
            assertTrue(viewModel.state.value.error != null)
        }

    @Test
    fun `the sync's end loads the favourites again, in place of a load still running`() =
        runTest(dispatcher) {
            // The reload for the server's return asked before the sync sent the favourite set
            // offline: the server had none yet.
            favoritesAnswers = ArrayDeque(listOf(emptyList(), listOf(dummyMovie)))
            val viewModel = FavoritesViewModel(repository())
            fromStore = false
            viewModel.onServerAwayChanged(false)
            viewModel.loadItems()
            runCurrent()

            viewModel.onSynced().join()

            assertEquals(listOf("favorites", "favorites"), calls)
            val state = viewModel.state.value
            assertEquals(listOf(dummyMovie.id), state.sections.flatMap { it.items }.map { it.id })
            assertEquals(null, state.error)
        }

    // An older load that answers last

    @Test
    fun `an older favourites load answering after a newer one changes nothing`() =
        runTest(dispatcher) {
            val viewModel = FavoritesViewModel(repository())
            fromStore = false
            viewModel.onServerAwayChanged(false)
            // Asked the server before it went away; its answer is slow.
            holdNext = true
            viewModel.loadItems()
            runCurrent()

            // The server goes away: the reload from Room answers at once, with nothing.
            fromStore = true
            viewModel.onServerAwayChanged(true)
            viewModel.loadItems().join()
            assertTrue(viewModel.state.value.sections.isEmpty())
            assertTrue(viewModel.state.value.downloadsOnly)

            held.removeFirst().resumeWith(Result.success(ItemsResult(listOf(dummyMovie))))
            advanceUntilIdle()

            assertTrue(viewModel.state.value.sections.isEmpty())
            assertTrue(viewModel.state.value.downloadsOnly)
            assertEquals(null, viewModel.state.value.error)
        }

    @Test
    fun `an older media load answering after a newer one changes nothing`() =
        runTest(dispatcher) {
            val viewModel = MediaViewModel(repository())
            fromStore = false
            viewModel.onServerAwayChanged(false)
            holdNext = true
            viewModel.loadData()
            runCurrent()

            fromStore = true

            viewModel.onServerAwayChanged(true)
            advanceUntilIdle()
            assertEquals(listOf(storedMovies), viewModel.state.value.libraries)
            assertFalse(viewModel.state.value.isLoading)

            held.removeFirst().resumeWith(Result.success(ItemsResult(dummyCollections)))
            advanceUntilIdle()

            assertEquals(listOf(storedMovies), viewModel.state.value.libraries)
            assertEquals(null, viewModel.state.value.error)
            assertFalse(viewModel.state.value.isLoading)
        }

    // Search

    @Test
    fun `results of a search made while away are downloads only`() =
        runTest(dispatcher) {
            val viewModel = SearchViewModel(repository())
            fromStore = false
            viewModel.onServerAwayChanged(false)
            viewModel.onAction(SearchAction.Search("bluey"))
            advanceUntilIdle()
            assertFalse(viewModel.state.value.downloadsOnly)

            fromStore = true

            viewModel.onServerAwayChanged(true)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.downloadsOnly)
        }

    @Test
    fun `an open search asks again when the server goes and comes back`() =
        runTest(dispatcher) {
            val viewModel = SearchViewModel(repository())
            fromStore = false
            viewModel.onServerAwayChanged(false)
            viewModel.onAction(SearchAction.Search("bluey"))
            advanceUntilIdle()
            assertEquals(listOf("bluey"), calls)

            fromStore = true

            viewModel.onServerAwayChanged(true)
            advanceUntilIdle()
            assertEquals(listOf("bluey", "bluey"), calls)

            // The error page's retry on the server's return and the change: one request.
            fromStore = false
            viewModel.onServerAwayChanged(false)
            viewModel.onAction(SearchAction.Search("bluey"))
            advanceUntilIdle()
            assertEquals(listOf("bluey", "bluey", "bluey"), calls)
        }

    @Test
    fun `a closed or empty search asks nothing`() =
        runTest(dispatcher) {
            val viewModel = SearchViewModel(repository())
            fromStore = false
            viewModel.onServerAwayChanged(false)
            viewModel.onAction(SearchAction.Search("bluey"))
            advanceUntilIdle()
            viewModel.onAction(SearchAction.Search(""))
            advanceUntilIdle()

            fromStore = true

            viewModel.onServerAwayChanged(true)
            advanceUntilIdle()

            assertEquals(listOf("bluey"), calls)
        }

    // Room answered before the server was known to be away (#779)

    @Test
    fun `favourites from Room are downloads only before the outage is flagged`() =
        runTest(dispatcher) {
            val viewModel = FavoritesViewModel(repository())
            viewModel.onServerAwayChanged(false)
            // The request failed fast; reachability has not caught up yet.
            fromStore = true
            favoritesAnswers = ArrayDeque(listOf(listOf(dummyMovie)))
            viewModel.loadItems().join()

            val state = viewModel.state.value
            assertEquals(listOf(dummyMovie.id), state.sections.flatMap { it.items }.map { it.id })
            assertTrue(state.downloadsOnly)
        }

    @Test
    fun `no downloaded favourites from Room say so before the outage is flagged`() =
        runTest(dispatcher) {
            val viewModel = FavoritesViewModel(repository())
            viewModel.onServerAwayChanged(false)
            fromStore = true
            viewModel.loadItems().join()

            val state = viewModel.state.value
            assertTrue(state.sections.isEmpty())
            assertTrue(state.downloadsOnly)
            assertEquals(null, state.error)
        }

    @Test
    fun `search results from Room are downloads only before the outage is flagged`() =
        runTest(dispatcher) {
            val viewModel = SearchViewModel(repository())
            viewModel.onServerAwayChanged(false)
            fromStore = true
            viewModel.onAction(SearchAction.Search("bluey"))
            advanceTimeBy(SearchViewModel.SEARCH_DEBOUNCE_MS + 1)
            runCurrent()

            assertTrue(viewModel.state.value.downloadsOnly)
            assertEquals(listOf("bluey"), calls)
        }

    @Test
    fun `favourites from Room ask once more when the server turns out to answer`() =
        runTest(dispatcher) {
            val viewModel = FavoritesViewModel(repository())
            viewModel.onServerAwayChanged(false)
            fromStore = true
            viewModel.loadItems().join()

            // The probe reached the server: reachability never flips.
            fromStore = false
            advanceTimeBy(ServerAwayReload.STORE_RETRY_DELAY_MS + 1)
            runCurrent()
            viewModel.loadItems().join()

            assertEquals(listOf("favorites", "favorites"), calls)
            assertFalse(viewModel.state.value.downloadsOnly)
            advanceTimeBy(10 * ServerAwayReload.STORE_RETRY_DELAY_MS)
            assertEquals(2, calls.size)
        }

    @Test
    fun `the one more try is one`() =
        runTest(dispatcher) {
            val viewModel = FavoritesViewModel(repository())
            viewModel.onServerAwayChanged(false)
            // Every request fails fast and every probe answers: no loop.
            fromStore = true
            viewModel.loadItems().join()
            advanceTimeBy(ServerAwayReload.STORE_RETRY_DELAY_MS + 1)
            runCurrent()
            viewModel.loadItems().join()
            advanceTimeBy(10 * ServerAwayReload.STORE_RETRY_DELAY_MS)
            runCurrent()

            assertEquals(2, calls.size)
            assertTrue(viewModel.state.value.downloadsOnly)
        }

    @Test
    fun `the outage flagged in the meantime takes the place of the one more try`() =
        runTest(dispatcher) {
            val viewModel = MediaViewModel(repository())
            viewModel.onServerAwayChanged(false)
            fromStore = true
            viewModel.loadData()
            runCurrent()
            assertEquals(1, calls.size)

            // The probe failed: the reload for the outage, and no retry after it.
            viewModel.onServerAwayChanged(true)
            advanceTimeBy(10 * ServerAwayReload.STORE_RETRY_DELAY_MS)
            runCurrent()

            assertEquals(2, calls.size)
        }

    @Test
    fun `media and search ask once more too`() =
        runTest(dispatcher) {
            val media = MediaViewModel(repository())
            val search = SearchViewModel(repository())
            media.onServerAwayChanged(false)
            search.onServerAwayChanged(false)
            fromStore = true
            media.loadData()
            search.onAction(SearchAction.Search("bluey"))
            advanceTimeBy(SearchViewModel.SEARCH_DEBOUNCE_MS + 1)
            runCurrent()

            fromStore = false
            advanceUntilIdle()

            assertEquals(2, calls.count { it == "libraries" })
            assertEquals(2, calls.count { it == "bluey" })
            assertFalse(search.state.value.downloadsOnly)
        }

    @Test
    fun `a list from the server, as on TV, asks nothing more`() =
        runTest(dispatcher) {
            // TV reports no reachability and has no downloads: the server's answer or its error.
            val viewModel = FavoritesViewModel(repository())
            viewModel.loadItems().join()
            advanceTimeBy(10 * ServerAwayReload.STORE_RETRY_DELAY_MS)
            runCurrent()

            assertEquals(1, calls.size)
            assertFalse(viewModel.state.value.downloadsOnly)
        }

    // A sync that finished while Favorites was not collecting (#782)

    @Test
    fun `a sync missed in the background makes the next resume load once`() =
        runTest(dispatcher) {
            val dataChanges = DataChangeEvents()
            val viewModel = FavoritesViewModel(repository(), dataChanges)
            viewModel.onServerAwayChanged(false)
            viewModel.loadItems().join()
            // A plain resume: nothing changed.
            assertFalse(viewModel.shouldReload())

            // The sync sent the favourite set offline; nobody collected its event.
            dataChanges.onSynced()

            assertTrue(viewModel.shouldReload())
            viewModel.loadItems().join()
            assertEquals(2, calls.size)
            assertFalse(viewModel.shouldReload())
        }

    @Test
    fun `a sync caught on screen leaves the next resume alone`() =
        runTest(dispatcher) {
            val dataChanges = DataChangeEvents()
            val viewModel = FavoritesViewModel(repository(), dataChanges)
            viewModel.onServerAwayChanged(false)
            viewModel.loadItems().join()

            // ReloadAfterSync saw the event and loaded: the resume after it does not load again.
            dataChanges.onSynced()
            viewModel.onSynced().join()

            assertEquals(2, calls.size)
            assertFalse(viewModel.shouldReload())
        }

    private fun repository(): JellyfinRepository =
        Proxy.newProxyInstance(
            JellyfinRepository::class.java.classLoader,
            arrayOf(JellyfinRepository::class.java),
        ) { _, method, args ->
            if (fail) throw java.net.ConnectException("Failed to connect")
            if (holdNext) {
                // A slow answer: the test resumes it later.
                holdNext = false
                calls += method.name
                @Suppress("UNCHECKED_CAST")
                held += args!!.last() as Continuation<Any?>
                return@newProxyInstance COROUTINE_SUSPENDED
            }
            when (method.name) {
                "getLibraries" -> {
                    calls += "libraries"
                    ItemsResult(if (heldOnce()) listOf(storedMovies) else emptyList(), fromStore)
                }
                "getFavoriteItems" -> {
                    calls += "favorites"
                    ItemsResult(favoritesAnswers.removeFirstOrNull() ?: emptyList(), fromStore)
                }
                "getSearchItems" -> {
                    calls += args!![0] as String
                    ItemsResult(emptyList<Any>(), fromStore)
                }
                else -> throw NotImplementedError("JellyfinRepository.${method.name}")
            }
        } as JellyfinRepository
}
