package dev.jdtech.jellyfin.film.presentation.season

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadQueue
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.FindroidImages
import dev.jdtech.jellyfin.models.FindroidSeason
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CE (#505): the season page can tell a season without episodes from one that is still loading.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeasonLoadingTest {
    private val seriesId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val seasonId = UUID.fromString("22222222-2222-2222-2222-222222222223")

    private var episodesFail = false

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a season without episodes ends loaded with no episodes`() = runBlocking {
        val viewModel = viewModel()
        viewModel.loadSeason(seasonId, seriesId = seriesId)
        val state = withTimeout(5_000) { viewModel.state.first { !it.isLoading } }

        assertNotNull(state.season)
        assertTrue(state.episodes.isEmpty())
        assertEquals(null, state.error)
    }

    @Test
    fun `the season is loading until its episodes have come`() = runBlocking {
        val viewModel = viewModel()
        val seen = mutableListOf<SeasonState>()
        val collector = launch(Dispatchers.Unconfined) { viewModel.state.collect { seen.add(it) } }
        viewModel.loadSeason(seasonId, seriesId = seriesId)
        withTimeout(5_000) { viewModel.state.first { !it.isLoading && it.season != null } }
        collector.cancel()

        // Every state before the last one, the season shown early included, is still loading.
        val loading = seen.drop(1).dropLast(1)
        assertTrue("no state while loading: $seen", loading.isNotEmpty())
        assertTrue("not loading before the episodes came: $seen", loading.all { it.isLoading })
        assertFalse(seen.last().isLoading)
    }

    @Test
    fun `a failed load is no longer loading`() = runBlocking {
        episodesFail = true
        val viewModel = viewModel()
        viewModel.loadSeason(seasonId, seriesId = seriesId)
        val state = withTimeout(5_000) { viewModel.state.first { it.error != null } }

        assertFalse(state.isLoading)
    }

    private fun viewModel(): SeasonViewModel {
        val repository = repository()
        val preferences = appPreferences()
        return SeasonViewModel(
            repository = repository,
            downloader = unused(),
            downloadQueue =
                DownloadQueue(
                    downloader = unused(),
                    appPreferences = preferences,
                    repositoryProvider = { repository },
                    database = unused<ServerDatabaseDao>(),
                    context = ContextWrapper(null) as Context,
                ),
        )
    }

    private fun repository(): JellyfinRepository =
        Proxy.newProxyInstance(
            JellyfinRepository::class.java.classLoader,
            arrayOf(JellyfinRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getSeason" -> season()
                "getEpisodes" -> {
                    val offline = args!![args.size - 2] as Boolean
                    if (!offline && episodesFail) throw IllegalStateException("No connection")
                    emptyList<Any>()
                }
                else -> throw NotImplementedError("JellyfinRepository.${method.name}")
            }
        } as JellyfinRepository

    private fun season() =
        FindroidSeason(
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
            images = FindroidImages(),
        )

    private fun appPreferences(): AppPreferences {
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
                    else -> throw UnsupportedOperationException("SharedPreferences.${method.name}")
                }
            } as SharedPreferences
        return AppPreferences(preferences)
    }

    private inline fun <reified T : Any> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            throw NotImplementedError("${T::class.java.simpleName}.${method.name}")
        } as T
}
