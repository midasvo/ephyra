package dev.jdtech.jellyfin.presentation.film

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.film.presentation.episode.EpisodeAction
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #395: the episode page's top bar leads to the show as well as the season. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EpisodeTopBarLinksTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<EpisodeAction>()

    @Test
    fun `the show button opens the show`() {
        rule.setContent {
            FindroidTheme { Row { EpisodeTopBarLinks(dummyEpisode) { actions += it } } }
        }

        rule.onNodeWithText(dummyEpisode.seriesName).performClick()

        assertEquals(listOf(EpisodeAction.NavigateToSeries(dummyEpisode.seriesId)), actions)
    }

    @Test
    fun `the season button still opens the season`() {
        rule.setContent {
            FindroidTheme { Row { EpisodeTopBarLinks(dummyEpisode) { actions += it } } }
        }

        rule.onNodeWithText(dummyEpisode.seasonName!!).performClick()

        assertEquals(
            listOf(EpisodeAction.NavigateToSeason(dummyEpisode.seasonId, dummyEpisode.seriesId)),
            actions,
        )
    }

    @Test
    fun `no show button without a show name`() {
        rule.setContent {
            FindroidTheme {
                Row { EpisodeTopBarLinks(dummyEpisode.copy(seriesName = "")) { actions += it } }
            }
        }

        rule.onAllNodes(hasClickAction()).assertCountEquals(1)
        rule.onNodeWithText(dummyEpisode.seasonName!!).assertHasClickAction()
    }

    @Test
    fun `a long show name leaves the season button whole`() {
        val longName = "A Show With A Name So Long It Can Never Fit In The Top Bar Of A Phone"
        rule.setContent {
            FindroidTheme {
                Column {
                    Row(Modifier.width(260.dp).testTag("short")) {
                        EpisodeTopBarLinks(dummyEpisode) {}
                    }
                    Row(Modifier.width(260.dp).testTag("long")) {
                        EpisodeTopBarLinks(dummyEpisode.copy(seriesName = longName)) {}
                    }
                }
            }
        }

        val season = dummyEpisode.seasonName!!
        val whole =
            rule
                .onNode(hasText(season) and hasAnyAncestor(hasTestTag("short")))
                .getBoundsInRoot()
        val squeezed =
            rule
                .onNode(hasText(season) and hasAnyAncestor(hasTestTag("long")))
                .getBoundsInRoot()
        val row = rule.onNodeWithTag("long").getBoundsInRoot()

        assertEquals(whole.right - whole.left, squeezed.right - squeezed.left)
        assertTrue(squeezed.right <= row.right)
    }
}
