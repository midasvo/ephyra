package dev.jdtech.jellyfin.film.presentation.home

import android.content.SharedPreferences
import dev.jdtech.jellyfin.film.R as FilmR
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.HomeItem
import dev.jdtech.jellyfin.models.HomeSection
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemImages
import dev.jdtech.jellyfin.models.Library
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.ServerInfo
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.View
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.repository.CurrentAccount
import dev.jdtech.jellyfin.repository.ItemCatalog
import dev.jdtech.jellyfin.repository.ItemLists
import dev.jdtech.jellyfin.repository.StoredServers
import dev.jdtech.jellyfin.repository.StoredUsers
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.testing.FakeRoles
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Offline / unreachable Home must never keep showing cached or previously loaded online rows:
 * - Cold start while offline with [HomeCache] populated clears cached rows on failure (or skips
 *   reading the cache when already known away) so `ErrorState` is shown.
 * - Warm transition to offline (`onServerAwayChanged(true)` or a failing reload) clears existing
 *   online rows instead of keeping them under an error card, and reloads when the server returns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelOfflineRowsTest {
    private val serverId = SERVER_ID.toServerId()
    private val userId = USER_ID.toUserId()
    private val server = Server(id = serverId, name = "Demo", null, userId)
    private val user = User(id = userId, name = "Alice", serverId = serverId)
    private val library =
        Library(
            id = UUID.randomUUID().toString().toItemId(),
            name = "Movies",
            type = CollectionType.Movies,
            images = ItemImages(),
        )
    private var libraries = listOf(library)
    private var offline = false
    private val calls = mutableListOf<String>()

    private val cachedItem = item("Cached movie")
    private val cachedRows =
        HomeCache.Rows(
            suggestions =
                HomeItem.Suggestions(
                    id = UUID.randomUUID().toString().toItemId(),
                    items = listOf(cachedItem),
                ),
            resume =
                HomeItem.Section(
                    HomeSection(
                        id = UUID.randomUUID().toString().toItemId(),
                        name = UiText.StringResource(FilmR.string.ce_continue_watching),
                        items = listOf(cachedItem),
                    )
                ),
            nextUp =
                HomeItem.Section(
                    HomeSection(
                        id = UUID.randomUUID().toString().toItemId(),
                        name = UiText.StringResource(FilmR.string.next_up),
                        items = listOf(cachedItem),
                    )
                ),
            views =
                listOf(
                    HomeItem.ViewItem(
                        View(
                            id = library.id,
                            name = library.name,
                            items = listOf(cachedItem),
                            type = library.type,
                        )
                    )
                ),
            rowOrder = listOf("suggestions", "resume", "nextup", library.id.toString()),
        )

    private var readCount = 0
    private var writeCount = 0
    private var pendingWrite: CompletableDeferred<Unit>? = null
    private val fakeCache =
        object : HomeCache(File(".")) {
            override suspend fun read(serverId: ServerId, userId: UserId): Rows? {
                readCount++
                return cachedRows
            }

            override suspend fun write(serverId: ServerId, userId: UserId, state: HomeState) {
                writeCount++
                pendingWrite?.await()
            }

            override suspend fun clear() = Unit
        }

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
    fun `cold start while offline clears cached rows when all rows fail`() =
        runTest(dispatcher) {
            offline = true
            val viewModel = viewModel()

            viewModel.onServerAwayChanged(false)
            viewModel.loadData(silent = true)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(1, readCount)
            assertEquals(0, writeCount)
            assertFalse(state.isLoading)
            assertTrue(state.isEmpty)
            assertNull(state.suggestionsSection)
            assertNull(state.resumeSection)
            assertNull(state.nextUpSection)
            assertTrue(state.views.isEmpty())
            assertNotNull(state.error)
            assertEquals(server, state.server)
            assertEquals(user, state.user)
        }

    @Test
    fun `cold start skips reading HomeCache when server is already known away`() =
        runTest(dispatcher) {
            offline = true
            val viewModel = viewModel()

            viewModel.onServerAwayChanged(true)
            viewModel.loadData(silent = true)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(0, readCount)
            assertFalse(state.isLoading)
            assertTrue(state.isEmpty)
            assertNotNull(state.error)
        }

    @Test
    fun `warm transition to offline clears online rows and reloads when server returns`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.onServerAwayChanged(false)
            viewModel.loadData(silent = true)
            advanceUntilIdle()
            assertFalse(viewModel.state.value.isEmpty)
            assertNull(viewModel.state.value.error)

            // Network drops while Home has rows on screen (and cachedLibraries is populated).
            offline = true
            viewModel.onServerAwayChanged(true)
            advanceUntilIdle()

            val awayState = viewModel.state.value
            assertFalse(awayState.isLoading)
            assertTrue(awayState.isEmpty)
            assertNull(awayState.suggestionsSection)
            assertNull(awayState.resumeSection)
            assertNull(awayState.nextUpSection)
            assertTrue(awayState.views.isEmpty())
            assertNotNull(awayState.error)

            // Server comes back: Home reloads and restores the online rows.
            offline = false
            viewModel.onServerAwayChanged(false)
            advanceUntilIdle()

            val backState = viewModel.state.value
            assertFalse(backState.isLoading)
            assertFalse(backState.isEmpty)
            assertNull(backState.error)
        }

    @Test
    fun `a failing silent reload clears existing online rows`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.onServerAwayChanged(false)
            viewModel.loadData(silent = true)
            advanceUntilIdle()
            assertFalse(viewModel.state.value.isEmpty)

            offline = true
            viewModel.loadData(silent = true)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertFalse(state.isLoading)
            assertTrue(state.isEmpty)
            assertNotNull(state.error)
        }

    @Test
    fun `warm transition to offline with empty cached libraries still fails and clears rows`() =
        runTest(dispatcher) {
            libraries = emptyList()
            val viewModel = viewModel()
            viewModel.onServerAwayChanged(false)
            viewModel.loadData(silent = true)
            advanceUntilIdle()
            assertNotNull(viewModel.state.value.resumeSection)

            offline = true
            viewModel.onServerAwayChanged(true)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue(state.isEmpty)
            assertNotNull(state.error)
        }

    @Test
    fun `cold start failure before reachability flips does not refire on away and still recovers`() =
        runTest(dispatcher) {
            offline = true
            val viewModel = viewModel()
            viewModel.onServerAwayChanged(false)
            viewModel.loadData(silent = true)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.isEmpty)
            assertNotNull(viewModel.state.value.error)
            val callsAfterColdFailure = calls.size

            // Reachability probe finishes and flips away = true: already empty with error, no
            // refire.
            viewModel.onServerAwayChanged(true)
            advanceUntilIdle()
            assertEquals(callsAfterColdFailure, calls.size)

            // Server comes back: ReloadWhenServerAwayChanges + ErrorState RetryWhenServerReturns
            // fire together.
            offline = false
            viewModel.onServerAwayChanged(false)
            viewModel.onAction(HomeAction.OnRetryClick)
            advanceUntilIdle()

            val recovered = viewModel.state.value
            assertFalse(recovered.isEmpty)
            assertNull(recovered.error)
            assertEquals(1, calls.count { it == "getResumeItems" } - 1)
        }

    @Test
    fun `pull to refresh while HomeCache write is suspended starts a fresh load and resets isLoading`() =
        runTest(dispatcher) {
            val writeGate = CompletableDeferred<Unit>()
            pendingWrite = writeGate
            val viewModel = viewModel()
            viewModel.onServerAwayChanged(false)
            val firstLoad = viewModel.loadData()
            runCurrent()

            // Rows have loaded (isLoading = false) and saveRows is now suspended inside write().
            assertFalse(viewModel.state.value.isLoading)
            assertFalse(firstLoad.isCompleted)
            assertEquals(1, writeCount)

            // User pulls to refresh while saveRows is still suspended.
            pendingWrite = null
            val refresh = viewModel.loadData()
            advanceUntilIdle()

            assertTrue(refresh.isCompleted)
            assertFalse(viewModel.state.value.isLoading)
            assertEquals(2, calls.count { it == "getResumeItems" })
            writeGate.complete(Unit)
        }

    private fun viewModel() =
        HomeViewModel(
            lists = repository.role(),
            catalog = repository.role(),
            account = repository.role(),
            appPreferences = AppPreferences(sharedPreferences()),
            storedServers = database.role(),
            storedUsers = database.role(),
            homeCache = fakeCache,
            loadDispatcher = dispatcher,
        )

    private val repository: FakeRoles =
        FakeRoles(ItemLists::class, ItemCatalog::class, CurrentAccount::class) { _, method, _ ->
            if (method.kotlinName == "getUserId") return@FakeRoles userId
            if (offline) {
                calls += method.kotlinName
                throw IOException("offline: ${method.kotlinName}")
            }
            calls += method.kotlinName
            when (method.kotlinName) {
                "getSuggestions",
                "getResumeItems",
                "getNextUp",
                "getLatestMedia" -> listOf(item(method.kotlinName))
                "getUserViews" -> libraries
                "refreshCurrentUserImage" -> Unit
                "getServerInfo" -> ServerInfo(serverId, "Demo", null, null)
                else ->
                    throw NotImplementedError(
                        "${method.declaringClass.simpleName}.${method.kotlinName}"
                    )
            }
        }

    private val database: FakeRoles =
        FakeRoles(StoredServers::class, StoredUsers::class) { _, method, _ ->
            when (method.kotlinName) {
                "server" -> server
                "currentUser" -> user
                "observeCurrentUser" -> flowOf(user)
                else ->
                    throw NotImplementedError(
                        "${method.declaringClass.simpleName}.${method.kotlinName}"
                    )
            }
        }

    private fun sharedPreferences(): SharedPreferences =
        fakeProxy(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.kotlinName) {
                "getString" -> if (args!![0] == "pref_current_server") SERVER_ID else args[1]
                "getBoolean",
                "getInt",
                "getLong" -> args!![1]
                "registerOnSharedPreferenceChangeListener",
                "unregisterOnSharedPreferenceChangeListener" -> Unit
                else ->
                    throw UnsupportedOperationException("SharedPreferences.${method.kotlinName}")
            }
        }
            as SharedPreferences

    private fun item(name: String): Item =
        fakeProxy(Item::class.java.classLoader, arrayOf(Item::class.java)) { proxy, method, args ->
            when (method.kotlinName) {
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> name
                else -> throw NotImplementedError("Item.${method.kotlinName}")
            }
        }
            as Item

    private companion object {
        const val SERVER_ID = "0123456789abcdef0123456789abcdef"
        const val USER_ID = "0f8fad5b-d9cb-469f-a165-70867728950e"
    }
}
