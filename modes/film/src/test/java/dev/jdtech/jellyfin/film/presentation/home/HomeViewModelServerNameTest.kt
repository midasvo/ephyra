package dev.jdtech.jellyfin.film.presentation.home

import android.content.SharedPreferences
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #543: a server renamed on the server is renamed in the app on the next Home load. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelServerNameTest {
    private val stored = Server(id = SERVER_ID, name = "Old name", null, null)
    private val updated = mutableListOf<Server>()
    private var systemInfo: () -> PublicSystemInfo = { info(SERVER_ID, "New name") }

    @Before
    fun setUp() {
        // viewModelScope is built with Dispatchers.Main.immediate; provide one.
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the rule renames the same server only`() {
        assertEquals("New name", stored.renamedTo(info(SERVER_ID, "New name"))?.name)
        assertNull(stored.renamedTo(info(SERVER_ID, "Old name")))
        assertNull(stored.renamedTo(info(SERVER_ID, " ")))
        assertNull(stored.renamedTo(info(SERVER_ID, null)))
        assertNull(stored.renamedTo(info("another-server", "New name")))
    }

    @Test
    fun `a Home load stores and shows the new name`() {
        val state = load { it.server?.name == "New name" }

        assertEquals("New name", state.server?.name)
        assertEquals(listOf(stored.copy(name = "New name")), updated)
    }

    @Test
    fun `an unreachable server keeps the stored name`() {
        systemInfo = { throw IOException("no connection") }

        val state = load { it.server != null }

        assertEquals("Old name", state.server?.name)
        assertTrue(updated.isEmpty())
    }

    private fun load(done: (HomeState) -> Boolean): HomeState {
        val viewModel =
            HomeViewModel(
                repository = repository(),
                appPreferences = AppPreferences(sharedPreferences()),
                database = database(),
            )
        viewModel.loadData()
        // loadData() runs on Dispatchers.Default; wait until it has finished.
        return runBlocking {
            withTimeout(5_000) {
                while (with(viewModel.state.value) { isLoading || !done(this) }) delay(10)
                viewModel.state.value
            }
        }
    }

    private fun info(id: String?, name: String?) = PublicSystemInfo(id = id, serverName = name)

    private fun repository(): JellyfinRepository =
        Proxy.newProxyInstance(
            JellyfinRepository::class.java.classLoader,
            arrayOf(JellyfinRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPublicSystemInfo" -> systemInfo()
                "refreshCurrentUserImage" -> Unit
                "getSuggestions",
                "getResumeItems",
                "getNextUp",
                "getUserViews" -> emptyList<Any>()
                else -> throw NotImplementedError("JellyfinRepository.${method.name}")
            }
        } as JellyfinRepository

    private fun database(): ServerDatabaseDao =
        Proxy.newProxyInstance(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getServer" -> stored
                "getServerCurrentUser" -> null
                "updateServer" -> updated.add(args!![0] as Server).let { Unit }
                else -> throw NotImplementedError("ServerDatabaseDao.${method.name}")
            }
        } as ServerDatabaseDao

    private fun sharedPreferences(): SharedPreferences =
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getString" -> if (args!![0] == "pref_current_server") SERVER_ID else args[1]
                "getBoolean",
                "getInt",
                "getLong" -> args!![1]
                else -> throw UnsupportedOperationException("SharedPreferences.${method.name}")
            }
        } as SharedPreferences

    private companion object {
        const val SERVER_ID = "0123456789abcdef0123456789abcdef"
    }
}
