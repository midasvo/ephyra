package dev.jdtech.jellyfin.film.presentation

import android.content.SharedPreferences
import dev.jdtech.jellyfin.film.domain.VideoMetadataParser
import dev.jdtech.jellyfin.film.presentation.movie.MovieViewModel
import dev.jdtech.jellyfin.models.FindroidImages
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.ratings.ExternalRating
import dev.jdtech.jellyfin.ratings.ExternalRatingsSource
import dev.jdtech.jellyfin.ratings.RatingSource
import dev.jdtech.jellyfin.ratings.RatingsLookup
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * CE (#331): the movie page keeps room for the external ratings on its first load, asks for them
 * only once it is shown (loadRelated), once per movie, and never while they are off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalRatingsLoadingTest {
    private val itemId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val switchedOff = mutableSetOf<String>()

    private val imdb = ExternalRating(RatingSource.IMDB, 7.8f)
    private val tomatoes = ExternalRating(RatingSource.ROTTEN_TOMATOES, 91f)

    private inner class FakeSource : ExternalRatingsSource {
        var on = true
        /** False while MDBList may not be asked (a failure, a 429, a rejected key). */
        var allowed = true
        var answer = listOf(imdb, tomatoes)
        /** Runs while a lookup is under way, before it answers. */
        var during: () -> Unit = {}
        val asked: MutableList<RatingsLookup> = Collections.synchronizedList(mutableListOf())

        override fun isOn() = on

        override fun canRequest(lookup: RatingsLookup) = on && allowed

        override fun mightHaveRatings(lookup: RatingsLookup) = on

        override suspend fun ratings(lookup: RatingsLookup): List<ExternalRating> {
            asked += lookup
            during()
            return if (on && allowed) answer else emptyList()
        }
    }

    private val source = FakeSource()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun loaded(movie: FindroidMovie = movie()): MovieViewModel = runBlocking {
        val viewModel =
            MovieViewModel(
                repository(movie),
                appPreferences(),
                VideoMetadataParser,
                externalRatings = source,
            )
        viewModel.loadMovie(itemId)
        withTimeout(5_000) { viewModel.state.first { it.movie != null } }
        viewModel
    }

    @Test
    fun `switched off while the lookup runs, its answer is dropped`() {
        source.during = { source.on = false }
        val viewModel = loaded()
        viewModel.loadRelated(itemId)
        assertEquals(ExternalRatingsState.Hidden, viewModel.state.value.externalRatings)
        assertEquals(1, source.asked.size)
    }

    @Test
    fun `a lookup MDBList couldn't answer is tried again on a reload`() = runBlocking {
        source.allowed = false
        val viewModel = loaded()
        viewModel.loadRelated(itemId)
        assertEquals(ExternalRatingsState.Hidden, viewModel.state.value.externalRatings)

        source.allowed = true
        viewModel.loadMovie(itemId)
        val state =
            withTimeout(5_000) {
                viewModel.state.first { it.externalRatings is ExternalRatingsState.Shown }
            }
        assertEquals(ExternalRatingsState.Shown(listOf(imdb, tomatoes)), state.externalRatings)
        assertEquals(2, source.asked.size)
    }

    @Test
    fun `a title MDBList has nothing for is not asked again on a reload`() = runBlocking {
        source.answer = emptyList()
        val viewModel = loaded()
        viewModel.loadRelated(itemId)
        viewModel.loadMovie(itemId)
        withTimeout(5_000) { viewModel.state.first { it.movie != null } }
        assertEquals(1, source.asked.size)
        assertEquals(ExternalRatingsState.Hidden, viewModel.state.value.externalRatings)
    }

    @Test
    fun `the Rotten Tomatoes switch applies again on a reload`() = runBlocking {
        val viewModel = loaded()
        viewModel.loadRelated(itemId)
        withTimeout(5_000) {
            viewModel.state.first { it.externalRatings is ExternalRatingsState.Shown }
        }

        switchedOff += "pref_detail_critic_rating"
        viewModel.loadMovie(itemId)
        withTimeout(5_000) {
            viewModel.state.first { it.externalRatings == ExternalRatingsState.Shown(listOf(imdb)) }
        }

        switchedOff.clear()
        viewModel.loadMovie(itemId)
        withTimeout(5_000) {
            viewModel.state.first {
                it.externalRatings == ExternalRatingsState.Shown(listOf(imdb, tomatoes))
            }
        }
        assertEquals(1, source.asked.size)
    }

    @Test
    fun `the first load keeps room and asks for nothing`() {
        val viewModel = loaded()
        assertEquals(ExternalRatingsState.Pending, viewModel.state.value.externalRatings)
        assertTrue(source.asked.isEmpty())
    }

    @Test
    fun `the ratings come once the page is shown, once per movie`() = runBlocking {
        val viewModel = loaded()
        viewModel.loadRelated(itemId)
        val state =
            withTimeout(5_000) {
                viewModel.state.first { it.externalRatings is ExternalRatingsState.Shown }
            }
        assertEquals(ExternalRatingsState.Shown(listOf(imdb, tomatoes)), state.externalRatings)
        assertEquals(listOf(RatingsLookup("tmdb", "movie", "603")), source.asked.toList())

        viewModel.loadRelated(itemId)
        viewModel.loadMovie(itemId)
        withTimeout(5_000) { viewModel.state.first { it.movie != null } }
        assertEquals(1, source.asked.size)
        assertEquals(
            ExternalRatingsState.Shown(listOf(imdb, tomatoes)),
            viewModel.state.value.externalRatings,
        )
    }

    @Test
    fun `off, nothing is asked and nothing is kept`() {
        source.on = false
        val viewModel = loaded()
        viewModel.loadRelated(itemId)
        assertEquals(ExternalRatingsState.Hidden, viewModel.state.value.externalRatings)
        assertTrue(source.asked.isEmpty())
    }

    @Test
    fun `a movie without an IMDb or TMDB id asks for nothing`() {
        val viewModel = loaded(movie(providerIds = mapOf("tvdb" to "1")))
        viewModel.loadRelated(itemId)
        assertEquals(ExternalRatingsState.Hidden, viewModel.state.value.externalRatings)
        assertTrue(source.asked.isEmpty())
    }

    @Test
    fun `no ratings hides the row`() = runBlocking {
        source.answer = emptyList()
        val viewModel = loaded()
        viewModel.loadRelated(itemId)
        withTimeout(5_000) {
            viewModel.state.first { it.externalRatings == ExternalRatingsState.Hidden }
        }
        assertEquals(1, source.asked.size)
    }

    @Test
    fun `the server's own Rotten Tomatoes score isn't shown twice`() = runBlocking {
        val viewModel = loaded(movie().copy(criticRating = 90f))
        viewModel.loadRelated(itemId)
        val state =
            withTimeout(5_000) {
                viewModel.state.first { it.externalRatings is ExternalRatingsState.Shown }
            }
        assertEquals(ExternalRatingsState.Shown(listOf(imdb)), state.externalRatings)
    }

    @Test
    fun `Rotten Tomatoes switched off hides MDBList's score too`() = runBlocking {
        switchedOff += "pref_detail_critic_rating"
        val viewModel = loaded()
        viewModel.loadRelated(itemId)
        val state =
            withTimeout(5_000) {
                viewModel.state.first { it.externalRatings is ExternalRatingsState.Shown }
            }
        assertEquals(ExternalRatingsState.Shown(listOf(imdb)), state.externalRatings)
    }

    @Test
    fun `switched off later, a reload hides the row`() = runBlocking {
        val viewModel = loaded()
        viewModel.loadRelated(itemId)
        withTimeout(5_000) {
            viewModel.state.first { it.externalRatings is ExternalRatingsState.Shown }
        }
        source.on = false
        viewModel.loadMovie(itemId)
        withTimeout(5_000) {
            viewModel.state.first { it.externalRatings == ExternalRatingsState.Hidden }
        }
        assertEquals(1, source.asked.size)
    }

    private fun repository(movie: FindroidMovie): JellyfinRepository =
        Proxy.newProxyInstance(
            JellyfinRepository::class.java.classLoader,
            arrayOf(JellyfinRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getMovie" -> movie
                "getItemCollections",
                "getSimilarItems",
                "getExtras" -> emptyList<Any>()
                else -> throw NotImplementedError("JellyfinRepository.${method.name}")
            }
        } as JellyfinRepository

    private fun movie(
        providerIds: Map<String, String> = mapOf("tmdb" to "603", "imdb" to "tt0133093")
    ) =
        FindroidMovie(
            id = itemId,
            name = "Movie",
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
            providerIds = providerIds,
            images = FindroidImages(),
            chapters = emptyList(),
            trickplayInfo = null,
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
}
