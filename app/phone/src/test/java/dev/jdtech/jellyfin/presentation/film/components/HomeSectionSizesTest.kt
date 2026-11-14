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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #640 at the five window sizes of docs/tablet/6-test-matrix.md (card widths follow the width
 * class): a card already in a Home row that moves to the front shows at the start of the row, and
 * the cards slide to their new places instead of jumping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeSectionSizesTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun movie(name: String): FindroidMovie =
        dummyMovie.copy(id = UUID.randomUUID(), name = name)

    private val sectionId = UUID.randomUUID()
    private var items by mutableStateOf(listOf<FindroidItem>())

    private fun leftOf(text: String): Float =
        rule.onNodeWithText(text).fetchSemanticsNode().positionInRoot.x

    private fun check() {
        val a = movie("Movie A")
        val b = movie("Movie B")
        val c = movie("Movie C")
        items = listOf(a, b, c)
        rule.setContent {
            FindroidTheme {
                LazyColumn {
                    items(
                        listOf(HomeSection(sectionId, UiText.DynamicString("Row"), items)),
                        key = { it.id },
                    ) { section ->
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
        val start = leftOf("Movie A")
        val second = leftOf("Movie B")

        // C, already in the row, moves to the front.
        rule.mainClock.autoAdvance = false
        items = listOf(c.copy(playbackPositionTicks = 1), a, b)
        repeat(EphyraMotion.DURATION_MEDIUM / 2 / 16 + 3) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }
        val halfway = leftOf("Movie A")
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        assertTrue("$halfway should be past $start", halfway > start)
        assertTrue("$halfway should be short of $second", halfway < second)
        assertEquals(second, leftOf("Movie A"), 1f)
        rule.onNodeWithText("Movie C").assertLeftPositionInRootIsEqualTo(24.dp)
    }

    @Test @Config(qualifiers = "w412dp-h915dp") fun phonePortrait() = check()

    @Test @Config(qualifiers = "w915dp-h412dp") fun phoneLandscape() = check()

    @Test @Config(qualifiers = "w1024dp-h640dp") fun tablet8() = check()

    @Test @Config(qualifiers = "w1280dp-h800dp") fun tablet10Landscape() = check()

    @Test @Config(qualifiers = "w800dp-h1280dp") fun tablet10Portrait() = check()

    @Test @Config(qualifiers = "w841dp-h701dp") fun foldUnfolded() = check()

    @Test @Config(qualifiers = "w1600dp-h900dp") fun chromebook13() = check()
}
