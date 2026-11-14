package dev.jdtech.jellyfin.film.presentation

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadQueue
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.film.domain.VideoMetadataParser
import dev.jdtech.jellyfin.film.presentation.movie.MovieViewModel
import dev.jdtech.jellyfin.film.presentation.show.ShowViewModel
import dev.jdtech.jellyfin.models.FindroidBoxSet
import dev.jdtech.jellyfin.models.FindroidImages
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CE (#332): the extras, collections and "More like this" rows are requested only when the page
 * asks for them after it is shown, once per item, and a failing request only leaves its row empty.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelatedItemsLoadingTest {
    private val itemId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val calls = mutableListOf<String>()
    private val extraArgs = mutableListOf<Pair<Boolean, Boolean>>()
    private val failing = mutableSetOf<String>()
    /** Boolean preferences the user turned off; every other preference has its default. */
    private val switchedOff = mutableSetOf<String>()

    private val extra = movie(UUID.randomUUID(), "Behind the scenes")
    private val similar = movie(UUID.randomUUID(), "Similar")
    private val collection =
        FindroidBoxSet(id = UUID.randomUUID(), name = "Saga", images = FindroidImages())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loading the movie asks for none of the related rows`() = runBlocking {
        val viewModel =
            movieViewModel(movie(itemId, "Movie", localTrailers = 1, specialFeatures = 2))
        viewModel.loadMovie(itemId)
        withTimeout(5_000) { viewModel.state.first { it.movie != null } }

        assertEquals(listOf("getMovie"), calls)
        assertEquals(RelatedItems(), viewModel.state.value.related)
    }

    @Test
    fun `the movie's related rows load once, each from its own request`() = runBlocking {
        val viewModel =
            movieViewModel(movie(itemId, "Movie", localTrailers = 1, specialFeatures = 2))
        viewModel.loadMovie(itemId)
        withTimeout(5_000) { viewModel.state.first { it.movie != null } }

        viewModel.loadRelated(itemId)
        val related =
            withTimeout(5_000) {
                    viewModel.state.first {
                        it.related.extras.isNotEmpty() &&
                            it.related.collections.isNotEmpty() &&
                            it.related.similar.isNotEmpty()
                    }
                }
                .related

        assertEquals(listOf(extra), related.extras)
        assertEquals(listOf(collection), related.collections)
        assertEquals(listOf(similar), related.similar)
        assertEquals(listOf(true to true), extraArgs)

        calls.clear()
        viewModel.loadRelated(itemId)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `no extras request when the movie has none`() = runBlocking {
        val viewModel =
            movieViewModel(movie(itemId, "Movie", localTrailers = 0, specialFeatures = 0))
        viewModel.loadMovie(itemId)
        withTimeout(5_000) { viewModel.state.first { it.movie != null } }

        viewModel.loadRelated(itemId)
        withTimeout(5_000) { viewModel.state.first { it.related.similar.isNotEmpty() } }

        assertTrue("getExtras" !in calls)
    }

    @Test
    fun `a failing request leaves only its own row empty`() = runBlocking {
        failing += "getSimilarItems"
        failing += "getItemCollections"
        val viewModel =
            movieViewModel(movie(itemId, "Movie", localTrailers = 1, specialFeatures = null))
        viewModel.loadMovie(itemId)
        withTimeout(5_000) { viewModel.state.first { it.movie != null } }

        viewModel.loadRelated(itemId)
        val state = withTimeout(5_000) { viewModel.state.first { it.related.extras.isNotEmpty() } }

        assertTrue(state.related.similar.isEmpty())
        assertTrue(state.related.collections.isEmpty())
        assertNull(state.error)
        // An unknown special feature count still asks for them.
        assertEquals(listOf(true to true), extraArgs)
    }

    @Test
    fun `nothing is asked before the movie is there`() {
        val viewModel = movieViewModel(movie(itemId, "Movie"))
        viewModel.loadRelated(itemId)

        assertTrue(calls.isEmpty())
    }

    @Test
    fun `the show asks for extras and similar items, not collections`() = runBlocking {
        val viewModel = showViewModel()
        viewModel.loadShow(itemId)
        withTimeout(5_000) { viewModel.state.first { it.show != null } }
        calls.clear()

        viewModel.loadRelated(itemId)
        val related =
            withTimeout(5_000) {
                    viewModel.state.first {
                        it.related.similar.isNotEmpty() && it.related.extras.isNotEmpty()
                    }
                }
                .related

        assertEquals(listOf(similar), related.similar)
        assertTrue(related.collections.isEmpty())
        assertTrue("getItemCollections" !in calls)
        assertEquals(listOf(false to true), extraArgs)
    }

    @Test
    fun `rows switched off in the settings are never requested`() = runBlocking {
        switchedOff += listOf("pref_detail_extras", "pref_detail_similar")
        val viewModel =
            movieViewModel(movie(itemId, "Movie", localTrailers = 1, specialFeatures = 2))
        viewModel.loadMovie(itemId)
        withTimeout(5_000) { viewModel.state.first { it.movie != null } }

        viewModel.loadRelated(itemId)
        val related =
            withTimeout(5_000) { viewModel.state.first { it.related.collections.isNotEmpty() } }
                .related

        assertEquals(listOf("getMovie", "getItemCollections"), calls)
        assertTrue(related.extras.isEmpty() && related.similar.isEmpty())
    }

    @Test
    fun `metadata switched off in the settings is cleared before the page gets it`() = runBlocking {
        switchedOff += listOf("pref_detail_tagline", "pref_detail_watch_history")
        val viewModel =
            movieViewModel(
                movie(itemId, "Movie")
                    .copy(tagline = "In space…", criticRating = 98f, playCount = 3)
            )
        viewModel.loadMovie(itemId)
        val movie = withTimeout(5_000) { viewModel.state.first { it.movie != null } }.movie!!

        assertNull(movie.tagline)
        assertEquals(0, movie.playCount)
        assertEquals(98f, movie.criticRating)
    }

    @Test
    fun `a row switched off goes away when the page reloads`() = runBlocking {
        val viewModel = movieViewModel(movie(itemId, "Movie"))
        viewModel.loadMovie(itemId)
        withTimeout(5_000) { viewModel.state.first { it.movie != null } }
        viewModel.loadRelated(itemId)
        withTimeout(5_000) {
            viewModel.state.first {
                it.related.similar.isNotEmpty() && it.related.collections.isNotEmpty()
            }
        }

        switchedOff += "pref_detail_similar"
        calls.clear()
        viewModel.loadMovie(itemId)
        val related =
            withTimeout(5_000) { viewModel.state.first { it.related.similar.isEmpty() } }.related

        assertEquals(listOf(collection), related.collections)
        assertTrue("getSimilarItems" !in calls)
    }

    @Test
    fun `a row switched on is requested when the page reloads`() = runBlocking {
        switchedOff += "pref_detail_similar"
        val viewModel = movieViewModel(movie(itemId, "Movie"))
        viewModel.loadMovie(itemId)
        withTimeout(5_000) { viewModel.state.first { it.movie != null } }
        viewModel.loadRelated(itemId)
        withTimeout(5_000) { viewModel.state.first { it.related.collections.isNotEmpty() } }

        switchedOff.clear()
        viewModel.loadMovie(itemId)
        val related =
            withTimeout(5_000) { viewModel.state.first { it.related.similar.isNotEmpty() } }.related

        assertEquals(listOf(similar), related.similar)
    }

    @Test
    fun `a reload with the same switches asks for no row again`() = runBlocking {
        val viewModel = movieViewModel(movie(itemId, "Movie"))
        viewModel.loadMovie(itemId)
        withTimeout(5_000) { viewModel.state.first { it.movie != null } }
        viewModel.loadRelated(itemId)
        withTimeout(5_000) { viewModel.state.first { it.related.similar.isNotEmpty() } }

        calls.clear()
        viewModel.loadMovie(itemId)
        withTimeout(5_000) {
            viewModel.state.first { "getMovie" in synchronized(calls) { calls.toList() } }
        }

        assertEquals(listOf("getMovie"), calls)
    }

    private fun movieViewModel(movie: FindroidMovie) =
        MovieViewModel(repository(movie), appPreferences(), VideoMetadataParser)

    private fun showViewModel(): ShowViewModel {
        val repository = repository(movie(itemId, "unused"))
        val preferences = appPreferences()
        return ShowViewModel(
            repository = repository,
            appPreferences = preferences,
            downloader = unused(),
            downloadQueue =
                DownloadQueue(
                    downloader = unused(),
                    appPreferences = preferences,
                    repositoryProvider = { repository },
                    database = unused<ServerDatabaseDao>(),
                    context = ContextWrapper(null) as Context,
                ),
            database = noDownloads(),
        )
    }

    private fun repository(movie: FindroidMovie): JellyfinRepository =
        Proxy.newProxyInstance(
            JellyfinRepository::class.java.classLoader,
            arrayOf(JellyfinRepository::class.java),
        ) { _, method, args ->
            synchronized(calls) { calls.add(method.name) }
            if (method.name in failing) throw IOException("${method.name} failed")
            when (method.name) {
                "getMovie" -> movie
                "getShow" -> show()
                "getNextUp",
                "getSeasons",
                "getEpisodes" -> emptyList<Any>()
                "getExtras" -> {
                    synchronized(calls) {
                        extraArgs.add(args!![1] as Boolean to args[2] as Boolean)
                    }
                    listOf(extra)
                }
                "getItemCollections" -> listOf(collection)
                // The server may return the item itself; the row leaves it out.
                "getSimilarItems" -> listOf(similar, movie(itemId, "Self"))
                else -> throw NotImplementedError("JellyfinRepository.${method.name}")
            }
        } as JellyfinRepository

    private fun noDownloads(): ServerDatabaseDao =
        Proxy.newProxyInstance(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getSeasonEpisodeCounts" -> emptyList<Any>()
                else -> throw NotImplementedError("ServerDatabaseDao.${method.name}")
            }
        } as ServerDatabaseDao

    private fun movie(id: UUID, name: String, localTrailers: Int = 0, specialFeatures: Int? = 0) =
        FindroidMovie(
            id = id,
            name = name,
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
            people = emptyList(),
            genres = emptyList(),
            communityRating = null,
            officialRating = null,
            status = "",
            productionYear = null,
            endDate = null,
            trailer = null,
            localTrailerCount = localTrailers,
            specialFeatureCount = specialFeatures,
            images = FindroidImages(),
            chapters = emptyList(),
            trickplayInfo = null,
        )

    private fun show() =
        FindroidShow(
            id = itemId,
            name = "Show",
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            seasons = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = true,
            unplayedItemCount = null,
            genres = emptyList(),
            people = emptyList(),
            runtimeTicks = 0L,
            communityRating = null,
            officialRating = null,
            status = "",
            productionYear = null,
            endDate = null,
            trailer = null,
            specialFeatureCount = 3,
            images = FindroidImages(),
        )

    private fun appPreferences(): AppPreferences {
        val preferences =
            Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getBoolean" -> if (args!![0] in switchedOff) false else args[1]
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
