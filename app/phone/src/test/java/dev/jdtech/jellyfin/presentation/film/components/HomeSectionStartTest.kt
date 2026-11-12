package dev.jdtech.jellyfin.presentation.film.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.HomeSection
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #393: an item that lands at the front of a Home row (something just watched joins Continue
 * Watching) sat half off the left edge, because the row kept its old first card in place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h914dp")
class HomeSectionStartTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun movie(name: String): FindroidItem =
        dummyMovie.copy(id = UUID.randomUUID(), name = name)

    private val sectionId = UUID.randomUUID()
    private var items by mutableStateOf(listOf<FindroidItem>())

    private fun setRow(initial: List<FindroidItem>) {
        items = initial
        rule.setContent {
            FindroidTheme {
                HomeSection(
                    section = HomeSection(sectionId, UiText.DynamicString("Row"), items),
                    itemsPadding = PaddingValues(horizontal = 24.dp),
                    onAction = {},
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun aNewFirstItemShowsAtTheStart() {
        val old = movie("Old movie")
        setRow(listOf(old))
        rule.onNodeWithText("Old movie").assertLeftPositionInRootIsEqualTo(24.dp)

        items = listOf(movie("New movie"), old)
        rule.waitForIdle()

        rule.onNodeWithText("New movie").assertLeftPositionInRootIsEqualTo(24.dp)
    }

    @Test
    fun aRowTheUserScrolledStaysWhereItIs() {
        val old = (1..6).map { movie("Movie $it") }
        setRow(old)
        rule.onNodeWithText("Movie 1").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        val shown = rule.onNodeWithText("Movie 3").fetchSemanticsNode().positionInRoot.x

        items = listOf(movie("New movie")) + old
        rule.waitForIdle()

        // The cards the user scrolled to stay put; the new one is off to the left, out of view.
        assertNotEquals(0f, shown)
        assertEquals(shown, rule.onNodeWithText("Movie 3").fetchSemanticsNode().positionInRoot.x, 1f)
    }
}
