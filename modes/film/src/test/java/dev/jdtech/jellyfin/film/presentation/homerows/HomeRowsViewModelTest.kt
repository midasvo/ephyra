package dev.jdtech.jellyfin.film.presentation.homerows

import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.film.domain.homerows.HomeRowsStore
import dev.jdtech.jellyfin.film.domain.homerows.MemorySharedPreferences
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.repository.DataChangeEvents
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.repository.ReloadPolicy
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #431: a change in Home rows makes Home load again when it comes back into view. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeRowsViewModelTest {
    private val movies =
        BaseItemDto(
            id = UUID.randomUUID(),
            type = BaseItemKind.COLLECTION_FOLDER,
            collectionType = CollectionType.MOVIES,
        )
    private val events = DataChangeEvents()

    /** Home's own policy, loaded just now: within the 60 s it would not load on return. */
    private val home = ReloadPolicy(events).apply { complete(begin()) }

    private lateinit var viewModel: HomeRowsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val prefs = AppPreferences(MemorySharedPreferences())
        prefs.setValue(prefs.currentServer, "server")
        val store = HomeRowsStore(prefs, dao(Server("server", "Server", null, UUID.randomUUID())))
        viewModel = HomeRowsViewModel(repository(), store, events)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `opening Home rows without a change leaves Home as it is`() {
        assertFalse(viewModel.state.value.isLoading)
        assertFalse(home.shouldReload())
    }

    @Test
    fun `hiding a row makes Home load again`() {
        viewModel.onAction(HomeRowsAction.OnVisibleChange("suggestions", visible = false))

        assertTrue(home.shouldReload())
    }

    @Test
    fun `moving a row makes Home load again`() {
        viewModel.onAction(HomeRowsAction.OnMoveUp("next_up"))
        assertTrue(home.shouldReload())

        home.complete(home.begin())
        viewModel.onAction(HomeRowsAction.OnMoveDown("next_up"))
        assertTrue(home.shouldReload())
    }

    private fun repository(): JellyfinRepository =
        Proxy.newProxyInstance(
            JellyfinRepository::class.java.classLoader,
            arrayOf(JellyfinRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getUserViews" -> listOf(movies)
                else -> throw NotImplementedError("JellyfinRepository.${method.name}")
            }
        } as JellyfinRepository

    private fun dao(server: Server): ServerDatabaseDao =
        Proxy.newProxyInstance(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getServer" -> server.takeIf { it.id == args!![0] }
                else -> throw NotImplementedError("ServerDatabaseDao.${method.name}")
            }
        } as ServerDatabaseDao
}
