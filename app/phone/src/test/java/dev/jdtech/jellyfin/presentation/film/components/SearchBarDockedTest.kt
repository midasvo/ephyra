package dev.jdtech.jellyfin.presentation.film.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.film.presentation.search.SearchState
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.utils.useDockedSearch
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #439: on a tablet or an unfolded fold the open search stays docked: the field keeps the
 * collapsed bar's place and at most 720 dp (docs/tablet/4-design.md §3 and §5), the results use
 * the whole width beside it. A phone keeps the full-screen search in both orientations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SearchBarDockedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val movies =
        List(8) { index -> dummyMovie.copy(id = UUID.randomUUID(), name = "Movie ${index + 1}") }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun `docked, the open field is capped and the results use the width`() {
        show(docked = true)

        val field = rule.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
        assertEquals(24f, field.left.value, 1f)
        assertTrue("field is ${field.width} wide", field.width <= 720.dp + 1.dp)

        // The sixth poster of a row of 180 dp cells lies well past the field's end.
        val sixth = rule.onNodeWithText("Movie 6").getUnclippedBoundsInRoot()
        assertTrue("sixth result starts at ${sixth.left}", sixth.left > field.right)
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-port")
    fun `full screen, the open field spans the window`() {
        show(docked = false)

        val window = rule.onRoot().getUnclippedBoundsInRoot()
        val field = rule.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
        assertEquals(window.width.value, field.width.value, 1f)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun `a 10 inch tablet docks the search`() = assertDocked(true)

    @Test
    @Config(qualifiers = "w640dp-h1024dp-port")
    fun `an 8 inch tablet in portrait docks the search`() = assertDocked(true)

    @Test
    @Config(qualifiers = "w841dp-h701dp-land")
    fun `a fold docks the search`() = assertDocked(true)

    @Test
    @Config(qualifiers = "w411dp-h914dp-port")
    fun `a phone keeps the full-screen search`() = assertDocked(false)

    @Test
    @Config(qualifiers = "w914dp-h411dp-land")
    fun `a phone in landscape keeps the full-screen search`() = assertDocked(false)

    private fun assertDocked(expected: Boolean) {
        var docked: Boolean? = null
        rule.setContent { docked = useDockedSearch() }
        rule.runOnIdle {
            if (expected) assertTrue(docked == true) else assertFalse(docked == true)
        }
    }

    private fun show(docked: Boolean) {
        rule.setContent {
            FindroidTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    FilmSearchBar(
                        state = SearchState(items = movies),
                        expanded = true,
                        onExpand = {},
                        onAction = {},
                        paddingStart = 24.dp,
                        paddingEnd = 24.dp,
                        docked = docked,
                    )
                }
            }
        }
        rule.waitForIdle()
    }
}
