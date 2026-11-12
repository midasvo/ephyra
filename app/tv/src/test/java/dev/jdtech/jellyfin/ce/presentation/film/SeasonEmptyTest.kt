package dev.jdtech.jellyfin.ce.presentation.film

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.jdtech.jellyfin.core.presentation.dummy.dummySeason
import dev.jdtech.jellyfin.film.presentation.episode.EpisodeState
import dev.jdtech.jellyfin.film.presentation.season.SeasonState
import dev.jdtech.jellyfin.models.FindroidSeason
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A TV season without episodes says so instead of a spinner, and its chip takes focus (#505). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp-television")
class SeasonEmptyTest {
    @get:Rule val rule = createComposeRule()

    private val seasons: List<FindroidSeason> =
        (1..3).map { dummySeason.copy(id = UUID(4, it.toLong()), name = "Season $it") }

    private fun setSeason(state: SeasonState) {
        rule.setContent {
            FindroidTheme {
                SeasonScreenLayout(
                    state = state,
                    loadError = state.error,
                    onRetry = {},
                    episodes = state.episodes,
                    selection = EpisodeSelection(null),
                    episodeState = { EpisodeState() },
                    onSelectSeason = {},
                    onPlay = { _, _, _ -> },
                    onAction = {},
                )
            }
        }
        rule.waitForIdle()
    }

    private fun spinnerCount(): Int =
        rule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .fetchSemanticsNodes()
            .size

    @Test
    fun `a loaded season without episodes says so and focuses its chip`() {
        setSeason(SeasonState(season = seasons[2], seriesSeasons = seasons, isLoading = false))

        rule.onNodeWithTag(SEASON_EMPTY_TAG).assertExists()
        rule.onNodeWithText("Nothing here yet").assertExists()
        assertEquals(0, spinnerCount())
        rule.onNode(isFocused()).assertExists()
        rule.onNodeWithText("Season 3").assertIsFocused()
    }

    @Test
    fun `a season still loading shows the spinner`() {
        setSeason(SeasonState(season = seasons[2], seriesSeasons = seasons, isLoading = true))

        rule.onNodeWithTag(SEASON_EMPTY_TAG).assertDoesNotExist()
        assertEquals(1, spinnerCount())
    }
}
