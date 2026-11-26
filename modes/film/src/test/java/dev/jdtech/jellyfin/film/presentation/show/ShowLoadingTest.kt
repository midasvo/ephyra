package dev.jdtech.jellyfin.film.presentation.show

import android.content.SharedPreferences
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.EpisodeField
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemImages
import dev.jdtech.jellyfin.models.Season
import dev.jdtech.jellyfin.models.SeasonEpisodeCounts
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.Show
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.repository.DownloadDeleter
import dev.jdtech.jellyfin.repository.DownloadList
import dev.jdtech.jellyfin.repository.DownloadQueuePort
import dev.jdtech.jellyfin.repository.ItemCatalog
import dev.jdtech.jellyfin.repository.ItemExtras
import dev.jdtech.jellyfin.repository.UserDataUpdates
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.testing.FakeRoles
import dev.jdtech.jellyfin.testing.asItemIdOrNull
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * CE (#252): the show page asks for the series' episodes once, not once per season, and takes the
 * downloaded counts from Room. A favourite toggle doesn't load the page again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShowLoadingTest {
    private val showId = "11111111-1111-1111-1111-111111111111".toItemId()
    private val season1 = "22222222-2222-2222-2222-222222222221".toItemId()
    private val season2 = "22222222-2222-2222-2222-222222222222".toItemId()
    private val calls = mutableListOf<String>()
    private val episodeSeasonIds = mutableListOf<ItemId?>()
    private val episodeFields = mutableListOf<Set<EpisodeField>?>()

    /** What the server allows per season (#373). */
    private var season1CanDownload = true
    private var season2CanDownload = true
    private var episodesFail = false

    /**
     * #809: Main and the ViewModel's cast and crew and Room reads on one test scheduler, so no load
     * is still on a real thread when the test ends.
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
    fun `download counts come from one series request and Room`() =
        runTest(dispatcher) {
            val viewModel = viewModel(SeasonEpisodeCounts(season1, total = 1, downloaded = 1))
            val state = loaded(viewModel)

            assertEquals(listOf<ItemId?>(null), episodeSeasonIds)
            assertEquals(
                SeasonDownloadInfo(downloadedCount = 1, totalCount = 3, canDownload = true),
                state.seasonDownloadInfo[season1],
            )
            assertEquals(
                SeasonDownloadInfo(downloadedCount = 0, totalCount = 2, canDownload = true),
                state.seasonDownloadInfo[season2],
            )
            assertTrue(state.hasDownloads)
            assertNull(state.error)
        }

    @Test
    fun `nothing downloaded`() =
        runTest(dispatcher) {
            val state = loaded(viewModel())

            assertEquals(false, state.hasDownloads)
            assertEquals(0, state.seasonDownloadInfo[season1]?.downloadedCount)
        }

    @Test
    fun `favourite toggles in place`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            loaded(viewModel)
            calls.clear()

            viewModel.onAction(ShowAction.MarkAsFavorite)
            advanceUntilIdle()
            val state = viewModel.state.value

            assertEquals(true, state.show?.favorite)
            assertEquals(listOf("markAsFavorite"), calls)
            assertEquals(2, state.seasons.size)
        }

    @Test
    fun `the series request asks for CanDownload`() =
        runTest(dispatcher) {
            loaded(viewModel())

            assertEquals(1, episodeFields.size)
            assertTrue(episodeFields.single().orEmpty().contains(EpisodeField.CAN_DOWNLOAD))
        }

    @Test
    fun `all allowed offers Download for every season`() =
        runTest(dispatcher) {
            val state = loaded(viewModel())

            assertTrue(state.canDownload)
            assertEquals(listOf(season1, season2), state.downloadableSeasons.map { it.id })
        }

    @Test
    fun `none allowed offers no Download, and Delete still shows what is downloaded`() =
        runTest(dispatcher) {
            season1CanDownload = false
            season2CanDownload = false
            val state = loaded(viewModel(SeasonEpisodeCounts(season1, total = 1, downloaded = 1)))

            assertEquals(false, state.canDownload)
            assertEquals(emptyList<ItemId>(), state.downloadableSeasons.map { it.id })
            assertTrue(state.hasDownloads)
            assertEquals(1, state.seasonDownloadInfo[season1]?.downloadedCount)
        }

    @Test
    fun `mixed offers only the allowed seasons`() =
        runTest(dispatcher) {
            season1CanDownload = false
            val state = loaded(viewModel())

            assertTrue(state.canDownload)
            assertEquals(listOf(season2), state.downloadableSeasons.map { it.id })
        }

    @Test
    fun `a failed series request offers no Download and shows the page`() =
        runTest(dispatcher) {
            episodesFail = true
            val viewModel = viewModel(SeasonEpisodeCounts(season1, total = 1, downloaded = 1))
            viewModel.loadShow(showId)
            // The counts request runs alongside: the whole load, its failure included.
            advanceUntilIdle()
            val state = viewModel.state.value

            assertNotNull(state.show)
            assertEquals(false, state.canDownload)
            assertTrue(state.hasDownloads)
            assertNull(state.error)
            assertEquals(2, state.seasons.size)
        }

    @Test
    fun `a reload keeps Download on screen`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.loadShow(showId)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.canDownload)
            val seen = mutableListOf<Boolean>()
            // Unconfined, so it sees every state the reload goes through.
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.state.collect { seen.add(it.canDownload) }
            }
            calls.clear()

            viewModel.loadShow(showId)
            advanceUntilIdle()

            assertTrue("getEpisodes" in calls)
            assertTrue(seen.isNotEmpty())
            assertTrue(seen.all { it }, message = "Download vanished during a reload: $seen")
        }

    /** Loads the show to its end, the download counts included. */
    private fun TestScope.loaded(viewModel: ShowViewModel): ShowState {
        viewModel.loadShow(showId)
        advanceUntilIdle()
        val state = viewModel.state.value
        assertTrue(state.seasonDownloadInfo.isNotEmpty())
        return state
    }

    private fun viewModel(vararg counts: SeasonEpisodeCounts): ShowViewModel {
        val repository = repository()
        val preferences = appPreferences()
        return ShowViewModel(
            catalog = repository.role(),
            userData = repository.role(),
            extras = repository.role(),
            capabilities = { ServerCapabilities.ALL },
            appPreferences = preferences,
            downloadDeleter = DownloadDeleter.None,
            downloadQueue = DownloadQueuePort.None,
            downloadList = downloadStore(counts.toList()),
            defaultDispatcher = dispatcher,
            ioDispatcher = dispatcher,
        )
    }

    private fun repository(): FakeRoles =
        FakeRoles(ItemCatalog::class, UserDataUpdates::class, ItemExtras::class) { _, method, args
            ->
            // #769: Jellyfin can do everything.
            synchronized(calls) { calls.add(method.kotlinName) }
            when (method.kotlinName) {
                "getShow" -> show()
                "getNextUp" -> emptyList<Any>()
                "getSeasons" -> listOf(season(season1, 1), season(season2, 2))
                "getEpisodes" -> {
                    @Suppress("UNCHECKED_CAST")
                    synchronized(calls) {
                        episodeSeasonIds.add(args!![1].asItemIdOrNull())
                        episodeFields.add(args[2] as Set<EpisodeField>?)
                    }
                    if (episodesFail) throw IllegalStateException("No connection")
                    List(3) { episode(season1, season1CanDownload) } +
                        List(2) { episode(season2, season2CanDownload) }
                }
                "markAsFavorite" -> Unit
                else ->
                    throw NotImplementedError(
                        "${method.declaringClass.simpleName}.${method.kotlinName}"
                    )
            }
        }

    private fun downloadStore(counts: List<SeasonEpisodeCounts>): DownloadList =
        fakeProxy(DownloadList::class.java.classLoader, arrayOf(DownloadList::class.java)) {
            _,
            method,
            _ ->
            when (method.kotlinName) {
                "seasonEpisodeCounts" -> counts
                else -> throw NotImplementedError("DownloadList.${method.kotlinName}")
            }
        }
            as DownloadList

    private fun show() =
        Show(
            id = showId,
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
            images = ItemImages(),
        )

    private fun season(id: ItemId, index: Int) =
        Season(
            id = id,
            name = "Season $index",
            seriesId = showId,
            seriesName = "Show",
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            indexNumber = index,
            episodes = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = true,
            unplayedItemCount = null,
            images = ItemImages(),
        )

    private fun episode(seasonId: ItemId, canDownload: Boolean = true) =
        Episode(
            id = UUID.randomUUID().toString().toItemId(),
            name = "Episode",
            originalTitle = null,
            overview = "",
            indexNumber = 1,
            indexNumberEnd = null,
            parentIndexNumber = 1,
            sources = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = canDownload,
            runtimeTicks = 0L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            seriesId = showId,
            seriesName = "Show",
            seasonId = seasonId,
            seasonName = null,
            communityRating = null,
            people = emptyList(),
            missing = false,
            images = ItemImages(),
            chapters = emptyList(),
            trickplayInfo = null,
        )

    private fun appPreferences(): AppPreferences {
        val preferences =
            fakeProxy(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                when {
                    // CE (#718): the stored episode counts are the current server's.
                    method.kotlinName == "getString" && args!![0] == "pref_current_server" ->
                        "server"
                    else -> defaultValue(method.kotlinName, args)
                }
            }
                as SharedPreferences
        return AppPreferences(preferences)
    }

    private fun defaultValue(name: String, args: Array<Any?>?): Any? =
        when (name) {
            "getBoolean",
            "getString",
            "getInt",
            "getLong",
            "getFloat",
            "getStringSet" -> args!![1]
            else -> throw UnsupportedOperationException("SharedPreferences.$name")
        }

    private inline fun <reified T : Any> unused(): T =
        fakeProxy(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            throw NotImplementedError("${T::class.java.simpleName}.${method.kotlinName}")
        }
            as T
}
