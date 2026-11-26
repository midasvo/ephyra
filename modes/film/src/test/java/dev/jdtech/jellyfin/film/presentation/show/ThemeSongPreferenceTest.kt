package dev.jdtech.jellyfin.film.presentation.show

import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.repository.DownloadDeleter
import dev.jdtech.jellyfin.repository.DownloadQueuePort
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.testing.ContractLibrary
import dev.jdtech.jellyfin.testing.FakeLibrary
import dev.jdtech.jellyfin.testing.FakeMediaServer
import dev.jdtech.jellyfin.testing.FakeRepositoryStores
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * CE (#141): the show ViewModel only asks the server for a theme song when the setting is on.
 *
 * #771: over the real repository and a [FakeMediaServer] with a theme song for the contract
 * library's series, instead of a reflection fake of the repository. The fake lists the port calls,
 * so "not asked" is that no `themeSongUrl` call reached the server.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThemeSongPreferenceTest {
    private val showId = ContractLibrary.series.id
    private val url = "https://jellyfin.test/Audio/2/stream?static=true"
    private val library =
        object : FakeLibrary by ContractLibrary {
            override val themeSongs = mapOf(showId to url)
        }

    private lateinit var server: FakeMediaServer

    private val lookups
        get() = server.calls.count { it == "themeSongUrl" }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `off by default, so no lookup`() {
        val viewModel = viewModel(appPreferences(stored = null))
        viewModel.loadThemeSongAndWait()

        assertEquals(0, lookups)
        assertNull(viewModel.state.value.themeSongUrl)
    }

    @Test
    fun `turned on, the song lands in the state`() {
        val viewModel = viewModel(appPreferences(stored = true))
        viewModel.loadThemeSongAndWait()

        assertEquals(1, lookups)
        assertEquals(url, viewModel.state.value.themeSongUrl)
    }

    @Test
    fun `#769 turned on, a server without theme songs is not asked`() {
        val viewModel =
            viewModel(
                appPreferences(stored = true),
                ServerCapabilities.ALL.copy(themeSongs = false),
            )
        viewModel.loadThemeSongAndWait()

        assertEquals(0, lookups)
        assertNull(viewModel.state.value.themeSongUrl)
    }

    /**
     * Loads the theme song and waits for what that started in the ViewModel: the repository asks on
     * the IO dispatcher, so without the wait a lookup could land after the assertions.
     */
    private fun ShowViewModel.loadThemeSongAndWait() {
        val job = viewModelScope.coroutineContext.job
        val before = job.children.toSet()
        loadThemeSong(showId)
        runBlocking {
            withTimeout(5_000) { job.children.filterNot { it in before }.forEach { it.join() } }
        }
    }

    private fun viewModel(
        preferences: AppPreferences,
        capabilities: ServerCapabilities = ServerCapabilities.ALL,
    ): ShowViewModel {
        server = FakeMediaServer(library, capabilities)
        // #966: stores that hold nothing, every setting the repository reads at its default.
        val repository = FakeRepositoryStores().repository(server)
        return ShowViewModel(
            catalog = repository,
            userData = repository,
            extras = repository,
            capabilities = { server.capabilities },
            appPreferences = preferences,
            downloadDeleter = DownloadDeleter.None,
            downloadQueue = DownloadQueuePort.None,
            downloadList = unused(),
        )
    }

    /** Preferences where only the theme music switch may be stored; null means never touched. */
    private fun appPreferences(stored: Boolean?): AppPreferences {
        val preferences =
            fakeProxy(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.kotlinName) {
                    "getBoolean" ->
                        if (args!![0] == "pref_tv_theme_music" && stored != null) stored
                        else args[1]
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
