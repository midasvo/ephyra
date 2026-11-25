package dev.jdtech.jellyfin.ce.presentation.film

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.core.presentation.dummy.dummySeason
import dev.jdtech.jellyfin.film.presentation.episode.EpisodeState
import dev.jdtech.jellyfin.film.presentation.movie.MovieState
import dev.jdtech.jellyfin.film.presentation.season.SeasonState
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.Ticks
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CE (#660): when the TV wakes, a page that is out of date loads again under the D-pad focus,
 * without being composed again. Focus stays where it was, or, when its button is gone, lands on the
 * next one, so the remote keeps working.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp-television")
class WakeReloadFocusTest {
    @get:Rule val rule = createComposeRule()

    private fun press(key: Key) {
        rule.onNode(isFocused()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun assertAbsent(text: String) =
        assertEquals(0, rule.onAllNodesWithText(text).fetchSemanticsNodes().size)

    // 12 minutes, 4 watched.
    private val partlyWatchedMovie =
        dummyMovie.copy(
            trailer = null,
            runtimeTicks = 12 * Ticks.PER_MINUTE,
            playbackPositionTicks = 4 * Ticks.PER_MINUTE,
            played = false,
        )

    private var movie by mutableStateOf(partlyWatchedMovie)

    private fun setMovie() {
        rule.setContent {
            FindroidTheme { MovieScreenLayout(state = MovieState(movie = movie)) {} }
        }
        rule.waitForIdle()
    }

    @Test
    fun `focus stays on Resume when the movie was watched further elsewhere`() {
        setMovie()
        rule.onNodeWithText("Resume").assertIsFocused()

        movie = partlyWatchedMovie.copy(playbackPositionTicks = 10 * Ticks.PER_MINUTE)
        rule.waitForIdle()

        rule.onNodeWithText("2 mins left").assertExists()
        rule.onNodeWithText("Resume").assertIsFocused()
    }

    @Test
    fun `focus on Restart moves to Play when the movie was finished elsewhere`() {
        setMovie()
        press(Key.DirectionRight)
        rule.onNodeWithText("Restart").assertIsFocused()

        movie = partlyWatchedMovie.copy(playbackPositionTicks = 0, played = true)
        rule.waitForIdle()

        assertAbsent("Restart")
        rule.onNodeWithText("Play").assertIsFocused()
    }

    // Three 12-minute episodes, none started.
    private val episodes: List<Episode> =
        (1..3).map {
            dummyEpisode.copy(
                id = UUID(5, it.toLong()).toString().toItemId(),
                name = "Episode $it",
                indexNumber = it,
                runtimeTicks = 12 * Ticks.PER_MINUTE,
                playbackPositionTicks = 0,
                played = false,
            )
        }

    private var season by
        mutableStateOf(
            SeasonState(
                season = dummySeason,
                episodes = episodes,
                seriesSeasons = listOf(dummySeason),
                isLoading = false,
            )
        )
    private var full by mutableStateOf<Episode?>(null)
    private val selection = EpisodeSelection(null)

    private fun setSeason() {
        rule.setContent {
            FindroidTheme {
                SeasonScreenLayout(
                    state = season,
                    loadError = null,
                    onRetry = {},
                    episodes = season.episodes,
                    selection = selection,
                    episodeState = { EpisodeState(episode = full) },
                    onSelectSeason = {},
                    onPlay = { _, _, _ -> },
                    onAction = {},
                )
            }
        }
        rule.waitForIdle()
    }

    /** What the reload brings: [episodes] with [changed] applied to episode [index]. */
    private fun reloaded(index: Int, changed: (Episode) -> Episode) =
        season.copy(
            episodes = season.episodes.mapIndexed { i, e -> if (i == index) changed(e) else e }
        )

    @Test
    fun `focus stays on the episode card when the season loads again`() {
        setSeason()
        press(Key.DirectionRight)
        assertEquals(episodes[1].id.asString(), selection.id)

        // Episode 1 was finished and episode 2 started on another device.
        season = reloaded(0) { it.copy(played = true) }
        season = reloaded(1) { it.copy(playbackPositionTicks = 4 * Ticks.PER_MINUTE) }
        rule.waitForIdle()

        assertEquals(episodes[1].id.asString(), selection.id)
        rule.onNode(isFocused()).assertExists()
        press(Key.DirectionRight)
        assertEquals(episodes[2].id.asString(), selection.id)
    }

    @Test
    fun `focus on Restart in the details moves to Play when the episode was finished elsewhere`() {
        val started = episodes[0].copy(playbackPositionTicks = 4 * Ticks.PER_MINUTE)
        season = reloaded(0) { started }
        full = started
        setSeason()
        press(Key.DirectionUp)
        rule.onNodeWithText("Resume").assertIsFocused()
        press(Key.DirectionRight)
        rule.onNodeWithText("Restart").assertIsFocused()

        val finished = started.copy(playbackPositionTicks = 0, played = true)
        season = reloaded(0) { finished }
        full = finished
        rule.waitForIdle()

        assertAbsent("Restart")
        rule.onNodeWithText("Play").assertIsFocused()
    }

    @Test
    fun `focus elsewhere stays there when Restart goes`() {
        val started = episodes[0].copy(playbackPositionTicks = 4 * Ticks.PER_MINUTE)
        season = reloaded(0) { started }
        full = started
        setSeason()
        // Restart had focus, then the remote moved down to the episode cards.
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        rule.onNodeWithText("Restart").assertIsFocused()
        press(Key.DirectionDown)
        assertEquals(episodes[0].id.asString(), selection.id)

        val finished = started.copy(playbackPositionTicks = 0, played = true)
        season = reloaded(0) { finished }
        full = finished
        rule.waitForIdle()

        assertAbsent("Restart")
        rule.onNodeWithText("Play").assertIsNotFocused()
        assertEquals(episodes[0].id.asString(), selection.id)
        rule.onNode(isFocused()).assertExists()
    }
}
