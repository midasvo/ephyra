package dev.jdtech.jellyfin.film.presentation.downloads

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toMediaSourceId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.repository.CurrentAccount
import dev.jdtech.jellyfin.repository.DownloadDeleter
import dev.jdtech.jellyfin.repository.DownloadList
import dev.jdtech.jellyfin.repository.DownloadQueuePort
import dev.jdtech.jellyfin.repository.DownloadSources
import dev.jdtech.jellyfin.repository.ItemLists
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.testing.FakeRoles
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
 * #866: Downloads stays on its stack across a user switch and reloads in place on return. The same
 * downloads carry another user's played state and progress, and the previous user's stayed on
 * screen until that reload ended.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelUserSwitchTest {
    private val alice = UUID.randomUUID().toString().toUserId()
    private val bob = UUID.randomUUID().toString().toUserId()
    private val movieId = UUID.randomUUID().toString().toItemId()

    /** One download, as each user sees it: Alice is halfway, Bob has not started. */
    private val aliceMovie =
        dummyMovie.copy(
            id = movieId,
            played = false,
            playbackPositionTicks = 50,
            runtimeTicks = 100,
        )
    private val bobMovie = aliceMovie.copy(playbackPositionTicks = 0)

    /** The user the client acts for. */
    private var sessionUser: UserId? = alice

    /**
     * #921: Main and the ViewModel's IO work on one test scheduler. On the real IO threads a load
     * could still be running when the test ended, and its return to Main, which tearDown had reset,
     * failed the next test in the module.
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
    fun `the screen shows the new user's progress after a switch, never the previous user's`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            // The screen's first ON_RESUME, which leaves the load to the ViewModel's own.
            viewModel.onScreenResumed()
            advanceUntilIdle()
            assertEquals(listOf<Item>(aliceMovie), items(viewModel.state.value))

            // Home's profile sheet, a login or the Users screen: the client acts for Bob now.
            sessionUser = bob

            // The screen comes back (DownloadsScreen's ON_RESUME).
            viewModel.onScreenResumed()
            // Before Bob's user data is read: none of Alice's progress, and a loading list.
            assertEquals(emptyList(), items(viewModel.state.value))
            assertTrue(viewModel.state.value.isLoading)

            advanceUntilIdle()
            assertEquals(bob, viewModel.state.value.userId)
            assertEquals(listOf<Item>(bobMovie), items(viewModel.state.value))
        }

    @Test
    fun `a load while no one is signed in does not hide the next switch`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.onScreenResumed()
            advanceUntilIdle()
            // Without a real Context the storage walk fails, which ends each load with an error.
            assertNotNull(viewModel.state.value.error)
            assertEquals(listOf<Item>(aliceMovie), items(viewModel.state.value))

            // The session is cleared (token revoked, current user removed), and a load runs in
            // that gap, such as the queue's reload when a download finishes. It fails without a
            // user.
            sessionUser = null
            viewModel.loadItems()
            advanceUntilIdle()
            assertNotNull(viewModel.state.value.error)

            // Bob signs in and opens Downloads.
            sessionUser = bob
            viewModel.onScreenResumed()
            // None of Alice's progress, as after a switch without the gap.
            assertEquals(emptyList(), items(viewModel.state.value))
            assertEquals(bob, viewModel.state.value.userId)

            advanceUntilIdle()
            assertEquals(listOf<Item>(bobMovie), items(viewModel.state.value))
            assertNotNull(viewModel.state.value.error)
        }

    @Test
    fun `the screen shows nothing of another user's progress`() {
        val aliceState =
            DownloadsState(
                sections =
                    listOf(
                        CollectionSection(0, UiText.DynamicString("Movies"), listOf(aliceMovie))
                    ),
                storageUsedBytes = 7,
                userId = alice,
            )

        val forBob = aliceState.shownTo(bob)

        assertEquals(emptyList(), forBob.sections)
        assertTrue(forBob.isLoading)
        // The storage and the queue belong to no user.
        assertEquals(7, forBob.storageUsedBytes)
        assertEquals(aliceState, aliceState.shownTo(alice))
        // No session at hand (previews), or a list from before the user was known.
        assertEquals(aliceState, aliceState.shownTo(null))
        val unknown = aliceState.copy(userId = null)
        assertEquals(unknown, unknown.shownTo(bob))
    }

    private fun items(state: DownloadsState): List<Item> = state.sections.flatMap { it.items }

    private fun viewModel(): DownloadsViewModel {
        val preferences = appPreferences()
        val downloadStore = downloadStore()
        val context = ContextWrapper(null) as Context
        return DownloadsViewModel(
            context = context,
            lists = repository.role(),
            account = repository.role(),
            appPreferences = preferences,
            downloadQueue = DownloadQueuePort.None,
            downloadDeleter = DownloadDeleter.None,
            downloadList = downloadStore.role(),
            downloadSources = downloadStore.role(),
            ioDispatcher = dispatcher,
        )
    }

    private val repository: FakeRoles =
        FakeRoles(ItemLists::class, CurrentAccount::class) { _, method, _ ->
            when (method.kotlinName) {
                "getUserId" -> sessionUser ?: throw IllegalStateException("No user")
                // Room's copy of the downloads, with the user data of the user the client acts for.
                "getDownloads" ->
                    if (sessionUser == null) {
                        throw IllegalStateException("No user")
                    } else if (sessionUser == bob) {
                        listOf(bobMovie)
                    } else {
                        listOf(aliceMovie)
                    }
                else ->
                    throw NotImplementedError(
                        "${method.declaringClass.simpleName}.${method.kotlinName}"
                    )
            }
        }

    private fun downloadStore(): FakeRoles =
        FakeRoles(DownloadList::class, DownloadSources::class) { _, method, args ->
            when (method.kotlinName) {
                "activeDownloadSources" -> emptyList<Any>()
                // A finished download of every item asked for.
                "localSourcesOfItems" ->
                    (args!![0] as Collection<*>).associate { id ->
                        id as ItemId to
                            listOf(
                                ItemSource(
                                    id = "source-$id".toMediaSourceId(),
                                    name = "source",
                                    type = ItemSourceType.LOCAL,
                                    path = "/nowhere/$id.mkv",
                                    size = 0,
                                    mediaStreams = emptyList(),
                                )
                            )
                    }
                else ->
                    throw NotImplementedError(
                        "${method.declaringClass.simpleName}.${method.kotlinName}"
                    )
            }
        }

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
