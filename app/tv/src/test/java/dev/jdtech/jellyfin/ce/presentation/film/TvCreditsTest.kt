package dev.jdtech.jellyfin.ce.presentation.film

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The credits under the buttons of the TV movie and show pages (#413). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp")
class TvCreditsTest {
    @get:Rule val rule = createComposeRule()

    private val longWriters =
        "Charles Lederer, Ben Hecht, Howard Hawks, Clive Cussler, Thomas Dean Donnelly, " +
            "Joshua Oppenheimer, John C. Richards, James V. Hart"

    private fun setCredits(columns: List<Pair<String, String>>) {
        rule.setContent {
            FindroidTheme {
                // The page's width less its 48 dp margins.
                Box(modifier = Modifier.width(864.dp)) { TvCredits(columns) }
            }
        }
    }

    @Test
    fun `a long writers list leaves the studios column its full width`() {
        setCredits(
            listOf(
                "Genres" to "Comedy, Romance, Crime",
                "Director" to "Howard Hawks",
                "Writers" to longWriters,
                "Studios" to "Columbia Pictures",
            )
        )

        val studios = rule.onNodeWithText("Columbia Pictures").assertIsDisplayed()
        val label = rule.onNodeWithText("Studios").assertIsDisplayed()
        // One line, as wide as the text needs: not squeezed to a few letters per line.
        val bounds = studios.getBoundsInRoot()
        assertTrue("studios is ${bounds.width} wide", bounds.width > 100.dp)
        assertTrue(bounds.right <= 864.dp)
        assertTrue(bounds.height < label.getBoundsInRoot().height * 1.5f)
        rule.onNodeWithText(longWriters).assertIsDisplayed()
    }

    @Test
    fun `columns that fit stay on one line`() {
        setCredits(
            listOf(
                "Genres" to "Action, Science Fiction",
                "Director" to "Lana Wachowski",
                "Writers" to "Lana Wachowski, Lilly Wachowski",
            )
        )

        val genres = rule.onNodeWithText("Action, Science Fiction").getBoundsInRoot()
        val writers = rule.onNodeWithText("Lana Wachowski, Lilly Wachowski").getBoundsInRoot()
        assertEquals(genres.top, writers.top)
    }

    @Test
    fun `an empty value leaves out its column`() {
        setCredits(listOf("Genres" to "", "Director" to "", "Studios" to "UFA"))

        assertEquals(0, rule.onAllNodesWithText("Genres").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Director").fetchSemanticsNodes().size)
        rule.onNodeWithText("UFA").assertIsDisplayed()
    }
}
