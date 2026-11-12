package dev.jdtech.jellyfin.film.presentation.library

import android.content.SharedPreferences
import androidx.paging.PagingData
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * CE (#371): the library screen's first load is decided by the ViewModel, not by a flag saved in
 * the screen. After process death the saved flag came back as "loaded" while the ViewModel was new,
 * and the grid spun forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryLoadOnceTest {
    private val movies = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val shows = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val requests = mutableListOf<UUID?>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a new ViewModel loads, as after process death`() {
        val viewModel = viewModel()
        viewModel.setup(parentId = movies, libraryType = CollectionType.Movies)
        viewModel.loadItemsIfNeeded()

        assertEquals(listOf<UUID?>(movies), requests)
    }

    @Test
    fun `coming back to the screen does not load again`() {
        val viewModel = viewModel()
        repeat(3) {
            viewModel.setup(parentId = movies, libraryType = CollectionType.Movies)
            viewModel.loadItemsIfNeeded()
        }

        assertEquals(listOf<UUID?>(movies), requests)
    }

    @Test
    fun `another library on the same ViewModel loads`() {
        val viewModel = viewModel()
        viewModel.setup(parentId = movies, libraryType = CollectionType.Movies)
        viewModel.loadItemsIfNeeded()
        viewModel.setup(parentId = shows, libraryType = CollectionType.TvShows)
        viewModel.loadItemsIfNeeded()

        assertEquals(listOf<UUID?>(movies, shows), requests)
    }

    @Test
    fun `a sort change still loads again`() {
        val viewModel = viewModel()
        viewModel.setup(parentId = movies, libraryType = CollectionType.Movies)
        viewModel.loadItemsIfNeeded()
        viewModel.onAction(LibraryAction.ChangeSorting(SortBy.DATE_ADDED, SortOrder.DESCENDING))
        viewModel.loadItemsIfNeeded()

        assertEquals(listOf<UUID?>(movies, movies), requests)
    }

    private fun viewModel() = LibraryViewModel(repository(), appPreferences())

    private fun repository(): JellyfinRepository =
        Proxy.newProxyInstance(
            JellyfinRepository::class.java.classLoader,
            arrayOf(JellyfinRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getItemsPaging" -> {
                    requests.add(args!![0] as UUID?)
                    flowOf(PagingData.empty<FindroidItem>())
                }
                else -> throw NotImplementedError("JellyfinRepository.${method.name}")
            }
        } as JellyfinRepository

    private fun appPreferences(): AppPreferences {
        lateinit var editor: SharedPreferences.Editor
        editor =
            Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "apply" -> Unit
                    "commit" -> true
                    else -> editor
                }
            } as SharedPreferences.Editor
        val preferences =
            Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getBoolean",
                    "getString",
                    "getInt",
                    "getLong",
                    "getFloat",
                    "getStringSet" -> args!![1]
                    "edit" -> editor
                    else -> throw UnsupportedOperationException("SharedPreferences.${method.name}")
                }
            } as SharedPreferences
        return AppPreferences(preferences)
    }
}
