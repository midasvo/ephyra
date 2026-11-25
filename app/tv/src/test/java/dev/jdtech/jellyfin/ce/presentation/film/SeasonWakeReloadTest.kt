package dev.jdtech.jellyfin.ce.presentation.film

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadQueue
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.core.presentation.dummy.dummySeason
import dev.jdtech.jellyfin.di.AppPreferencesDownloadSettings
import dev.jdtech.jellyfin.film.domain.VideoMetadataParser
import dev.jdtech.jellyfin.film.presentation.episode.EpisodeViewModel
import dev.jdtech.jellyfin.film.presentation.season.SeasonViewModel
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.Ticks
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.repository.DataChangeEvents
import dev.jdtech.jellyfin.repository.DownloadSources
import dev.jdtech.jellyfin.repository.ItemCatalog
import dev.jdtech.jellyfin.repository.UserDataUpdates
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.testing.FakeRoles
import dev.jdtech.jellyfin.testing.asItemId
import dev.jdtech.jellyfin.testing.asItemIdOrNull
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import dev.jdtech.jellyfin.utils.CoreDownloadTexts
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CE (#660): the TV season page left open while the TV sleeps loads again when it wakes, if it is
 * out of date: the season (the cards) first, then the details panel, each once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp-television")
class SeasonWakeReloadTest {
    @get:Rule val rule = createComposeRule()

    private val season =
        dummySeason.copy(id = UUID(6, 1).toString().toItemId(), name = "Season 1", indexNumber = 1)
    private val season2 =
        dummySeason.copy(id = UUID(6, 2).toString().toItemId(), name = "Season 2", indexNumber = 2)

    // Episode 1 is 4 minutes into its 12; episode 2 not started. No sources: the episode's load
    // reads a source's video metadata on Dispatchers.Default, which waitForIdle() doesn't wait for.
    private var serverEpisodes: List<Episode> =
        (1..2).map {
            dummyEpisode.copy(
                id = UUID(6, 10L + it).toString().toItemId(),
                sources = emptyList(),
                name = "Episode $it",
                indexNumber = it,
                seasonId = season.id,
                seriesId = season.seriesId,
                runtimeTicks = 12 * Ticks.PER_MINUTE,
                playbackPositionTicks = if (it == 1) 4 * Ticks.PER_MINUTE else 0,
                played = false,
            )
        }

    // Season 2 has one episode, not started.
    private val season2Episodes: List<Episode> =
        listOf(
            serverEpisodes[1].copy(
                id = UUID(6, 21).toString().toItemId(),
                name = "Episode 2.1",
                seasonId = season2.id,
            )
        )

    /**
     * The server's calls, in order: "season" for a load of season 1, "episode" for the panel with
     * its episode 1; "season 2" and "episode 2.1" for season 2.
     */
    private val calls = mutableListOf<String>()

    private val dataChanges = DataChangeEvents()
    private val app = TestLifecycleOwner()

    private fun setPage() {
        val repository = repository()
        val seasonViewModel =
            SeasonViewModel(
                catalog = repository.role(),
                userData = repository.role(),
                capabilities = { ServerCapabilities.ALL },
                downloader = unused(),
                downloadQueue =
                    DownloadQueue(
                        downloader = unused(),
                        settings = AppPreferencesDownloadSettings(appPreferences()),
                        downloadSources = unused<DownloadSources>(),
                        context = ContextWrapper(null) as Context,
                        texts = CoreDownloadTexts,
                    ),
                dataChanges = dataChanges,
            )
        val episodeViewModel =
            EpisodeViewModel(
                catalog = repository.role(),
                userData = repository.role(),
                capabilities = { ServerCapabilities.ALL },
                appPreferences = appPreferences(),
                videoMetadataParser = VideoMetadataParser,
                dataChanges = dataChanges,
            )
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides app) {
                FindroidTheme {
                    SeasonScreen(
                        seasonId = season.id,
                        episodeId = null,
                        navigateToPlayer = { _, _, _ -> },
                        seriesId = season.seriesId,
                        seasonViewModel = seasonViewModel,
                        episodeViewModel = episodeViewModel,
                    )
                }
            }
        }
        // The panel loads once focus has rested on the card.
        rule.waitUntil(timeoutMillis = 5_000) { "episode" in calls }
        // A wake reloads the panel only when there is one, so it must be on the page, not only
        // asked for.
        idleUntil { episodeViewModel.state.value.episode != null }
    }

    /**
     * Lets the main thread run until [condition] holds. The fake server answers on the main
     * thread's message queue and the page goes on in coroutines, so one waitForIdle() is not always
     * enough (seen in CI).
     */
    private fun idleUntil(condition: () -> Boolean) {
        repeat(IDLE_ROUNDS) {
            rule.waitForIdle()
            if (condition()) return
        }
        throw AssertionError("Still not there after $IDLE_ROUNDS rounds of waitForIdle()")
    }

    /** The TV goes to standby (or the screensaver starts) and comes back. */
    private fun sleepAndWake() {
        rule.runOnIdle {
            for (event in
                listOf(
                    Lifecycle.Event.ON_PAUSE,
                    Lifecycle.Event.ON_STOP,
                    Lifecycle.Event.ON_START,
                    Lifecycle.Event.ON_RESUME,
                )) {
                app.registry.handleLifecycleEvent(event)
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `the page loads once on entry`() {
        setPage()

        assertEquals(listOf("season", "episode"), calls)
        rule.onNodeWithText("Resume").assertExists()
        // Part of the episode's info line ("S1 E1 · … · 8 mins left · Ends at …").
        rule.onNodeWithText("8 mins left", substring = true).assertExists()
    }

    @Test
    fun `a wake with the page up to date loads nothing`() {
        setPage()
        calls.clear()

        sleepAndWake()

        assertEquals(emptyList(), calls)
    }

    @Test
    fun `a wake with the page out of date loads the season, then the panel, once each`() {
        setPage()
        calls.clear()

        // Finished on another device while the TV slept. Something else in the app changed too,
        // so the page is out of date (as it is after 60 s).
        serverEpisodes = serverEpisodes.mapIndexed { i, e ->
            if (i == 0) e.copy(playbackPositionTicks = 0, played = true) else e
        }
        dataChanges.onChanged()
        holdSeason = true
        sleepAndWake()

        // The panel waits for the season: loaded alongside it, it would land first with a played
        // mark its card doesn't have yet, and the page would load the season a second time.
        assertEquals(listOf("season"), calls)
        releaseSeason()

        idleUntil { calls.size >= 2 }
        assertEquals(listOf("season", "episode"), calls)
        idleUntil { rule.onAllNodesWithText("Play").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `a second wake while the first is still loading gets one panel, after its own season`() {
        setPage()
        calls.clear()

        serverEpisodes = serverEpisodes.mapIndexed { i, e ->
            if (i == 0) e.copy(playbackPositionTicks = 0, played = true) else e
        }
        dataChanges.onChanged()
        holdSeason = true
        sleepAndWake()
        // Another pause and resume before the first season load has answered: its own season
        // load replaces the first one.
        sleepAndWake()
        assertEquals(listOf("season", "season"), calls)

        // The first load's requests end, as a cancelled request does at once. The first wake must
        // not go on to the panel now: the second season load is still on its way.
        releaseFirst(heldPerLoad)
        assertEquals(listOf("season", "season"), calls)

        releaseSeason()
        idleUntil { calls.size >= 3 }
        assertEquals(listOf("season", "season", "episode"), calls)
    }

    @Test
    fun `a season picked during the wake's reload gets no panel from the season it left`() {
        setPage()
        calls.clear()

        serverEpisodes = serverEpisodes.mapIndexed { i, e ->
            if (i == 0) e.copy(playbackPositionTicks = 0, played = true) else e
        }
        dataChanges.onChanged()
        holdSeason = true
        sleepAndWake()
        assertEquals(listOf("season"), calls)

        // Season 2, picked before season 1 has answered: its load replaces the wake's.
        // The chip's click, as OK on it does (a test key press doesn't reach a tv-material chip).
        rule.onNodeWithText("Season 2").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        // Season 1's request ends first, as a cancelled request does at once, while season 2's is
        // still on its way and season 1's cards are still on screen.
        releaseSeason(season.id)
        assertEquals(listOf("season", "season 2"), calls)
        releaseSeason()
        // The panel follows focus to season 2's episode once it has rested there.
        rule.waitUntil(timeoutMillis = 5_000) { "episode 2.1" in calls }

        // Not season 1's episode 1, whose card was still on screen: with a played mark the card
        // didn't have yet, it would have loaded season 1 again over season 2.
        assertEquals(listOf("season", "season 2", "episode 2.1"), calls)
    }

    /**
     * While true, the season's answers wait for [releaseSeason]: a server slow to list a season,
     * slower than it is to answer for the panel's episode.
     */
    private var holdSeason = false
    private val heldSeason = mutableListOf<Pair<ItemId, () -> Unit>>()

    /**
     * Held calls per season load: the season and its episodes (the series' seasons aren't held).
     */
    private val heldPerLoad = 2

    /** Answers the first [count] held calls, in the order they were made. */
    private fun releaseFirst(count: Int) {
        val answers = heldSeason.take(count)
        heldSeason.removeAll(answers)
        rule.runOnIdle { answers.forEach { it.second() } }
        rule.waitForIdle()
    }

    /** Answers the held calls for season [only], or for every season. */
    private fun releaseSeason(only: ItemId? = null) {
        while (true) {
            val answers = heldSeason.filter { only == null || it.first == only }
            if (answers.isEmpty()) break
            heldSeason.removeAll(answers)
            rule.runOnIdle { answers.forEach { it.second() } }
            rule.waitForIdle()
        }
    }

    /** A server that takes a moment, as a real one does: every call suspends. */
    private fun repository(): FakeRoles =
        FakeRoles(ItemCatalog::class, UserDataUpdates::class) { _, method, args ->
            when (method.kotlinName) {
                "getSeason" -> {
                    val id = args!![0].asItemId()
                    calls += if (id == season2.id) "season 2" else "season"
                    later(args, if (id == season2.id) season2 else season, id.takeIf { holdSeason })
                }
                "getSeasons" -> later(args, listOf(season, season2), heldFor = null)
                "getEpisodes" -> {
                    val offline = args!![args.size - 2] as Boolean
                    val seasonId = args[1].asItemIdOrNull()
                    val episodes = if (seasonId == season2.id) season2Episodes else serverEpisodes
                    if (offline) emptyList<Episode>()
                    else later(args, episodes, seasonId.takeIf { holdSeason })
                }
                "getEpisode" -> {
                    val id = args!![0].asItemId()
                    val episode = (serverEpisodes + season2Episodes).first { it.id == id }
                    calls += if (episode in season2Episodes) "episode 2.1" else "episode"
                    later(args, episode, heldFor = null)
                }
                else ->
                    throw NotImplementedError(
                        "${method.declaringClass.simpleName}.${method.kotlinName}"
                    )
            }
        }

    /**
     * Suspends the call (its last argument is the continuation) and answers it on a later turn of
     * the main thread, or, when [heldFor] names a season, once [releaseSeason] releases it.
     */
    private fun later(args: Array<Any?>?, answer: Any?, heldFor: ItemId?): Any {
        @Suppress("UNCHECKED_CAST") val continuation = args!!.last() as Continuation<Any?>
        val resume = { continuation.resume(answer) }
        if (heldFor != null) heldSeason += heldFor to resume
        else Handler(Looper.getMainLooper()).post(resume)
        return COROUTINE_SUSPENDED
    }

    private fun appPreferences(): AppPreferences {
        val preferences =
            fakeProxy(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.kotlinName) {
                    // Cast and crew off: the episode's load would sort its cast on
                    // Dispatchers.Default too. With neither, it stays on the main thread.
                    "getBoolean" ->
                        if (args!![0] == "pref_display_cast_and_crew") false else args[1]
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

private const val IDLE_ROUNDS = 50

/** A TV app in the foreground, moved by hand. */
private class TestLifecycleOwner : LifecycleOwner {
    // Unsafe: the test moves it from the test thread.
    val registry: LifecycleRegistry =
        LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle
        get() = registry
}
