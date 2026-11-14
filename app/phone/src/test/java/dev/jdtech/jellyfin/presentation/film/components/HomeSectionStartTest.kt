package dev.jdtech.jellyfin.presentation.film.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.core.presentation.ephyra.EphyraMotion
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.HomeSection
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #393: an item that lands at the front of a Home row (something just watched joins Continue
 * Watching) sat half off the left edge, because the row kept its old first card in place.
 *
 * #640: the cards animate into their new places instead of snapping there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h914dp")
class HomeSectionStartTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun movie(name: String): FindroidMovie =
        dummyMovie.copy(id = UUID.randomUUID(), name = name)

    private val sectionId = UUID.randomUUID()
    private var items by mutableStateOf(listOf<FindroidItem>())

    private fun section() = HomeSection(sectionId, UiText.DynamicString("Row"), items)

    private fun setRow(initial: List<FindroidItem>) {
        items = initial
        rule.setContent {
            FindroidTheme {
                HomeSection(
                    section = section(),
                    itemsPadding = PaddingValues(horizontal = 24.dp),
                    onAction = {},
                )
            }
        }
        rule.waitForIdle()
    }

    /** The row as Home shows it: an item of Home's own list, which animates its items too. */
    private fun setRowInHome(initial: List<FindroidItem>) {
        items = initial
        rule.setContent {
            FindroidTheme {
                LazyColumn {
                    items(listOf(section()), key = { it.id }) { section ->
                        HomeSection(
                            section = section,
                            itemsPadding = PaddingValues(horizontal = 24.dp),
                            onAction = {},
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun leftOf(text: String): Float =
        rule.onNodeWithText(text).fetchSemanticsNode().positionInRoot.x

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
    fun anItemAlreadyInTheRowThatMovesToTheFrontShowsAtTheStart() {
        val a = movie("Movie A")
        val b = movie("Movie B")
        val c = movie("Movie C")
        setRowInHome(listOf(a, b, c))
        rule.onNodeWithText("Movie A").assertLeftPositionInRootIsEqualTo(24.dp)

        // Watched further elsewhere: the same item, with new progress, now first.
        items = listOf(b.copy(name = "Movie B", playbackPositionTicks = 1), a, c)
        rule.waitForIdle()

        rule.onNodeWithText("Movie B").assertLeftPositionInRootIsEqualTo(24.dp)
    }

    @Test
    fun aNewFirstItemShowsAtTheStartOfARowInHome() {
        val old = movie("Old movie")
        setRowInHome(listOf(old))

        items = listOf(movie("New movie"), old)
        rule.waitForIdle()

        rule.onNodeWithText("New movie").assertLeftPositionInRootIsEqualTo(24.dp)
    }

    @Test
    fun theCardsSlideToTheirNewPlaces() {
        val a = movie("Movie A")
        val b = movie("Movie B")
        setRow(listOf(a, b))
        val start = leftOf("Movie A")
        val next = leftOf("Movie B")

        rule.mainClock.autoAdvance = false
        items = listOf(movie("New movie"), a, b)
        // Frame by frame: the change lands a few frames in, then the slide runs its course.
        val frames = EphyraMotion.DURATION_MEDIUM / 2 / 16 + 3
        repeat(frames) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }
        val halfway = leftOf("Movie A")
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        // Halfway through, the old first card is on its way to the second place, not there yet.
        assertTrue("$halfway should be past $start", halfway > start)
        assertTrue("$halfway should be short of $next", halfway < next)
        assertEquals(next, leftOf("Movie A"), 1f)
        rule.onNodeWithText("New movie").assertLeftPositionInRootIsEqualTo(24.dp)
    }

    @Test
    fun aRowTheUserScrolledStaysWhereItIs() {
        val old = (1..6).map { movie("Movie $it") }
        setRow(old)
        rule.onNodeWithText("Movie 1").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        val shown = leftOf("Movie 3")

        items = listOf(movie("New movie")) + old
        rule.waitForIdle()

        // The cards the user scrolled to stay put; the new one is off to the left, out of view.
        assertNotEquals(0f, shown)
        assertEquals(shown, leftOf("Movie 3"), 1f)
    }
}
