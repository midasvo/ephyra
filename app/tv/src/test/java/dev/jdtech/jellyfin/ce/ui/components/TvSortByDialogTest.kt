package dev.jdtech.jellyfin.ce.ui.components

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The CE sort dialog (#418): one focus stop per option, and OK selects. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp")
class TvSortByDialogTest {
    @get:Rule val rule = createComposeRule()

    private val updates = mutableListOf<Pair<SortBy, SortOrder>>()

    private fun setUp() {
        rule.setContent {
            FindroidTheme {
                TvSortByDialog(
                    currentSortBy = SortBy.NAME,
                    currentSortOrder = SortOrder.ASCENDING,
                    onUpdate = { sortBy, sortOrder -> updates += sortBy to sortOrder },
                    onDismissRequest = {},
                )
            }
        }
        rule.waitForIdle()
    }

    private fun focusableInside(tag: String): SemanticsMatcher =
        hasAnyAncestor(hasTestTag(tag)) and isFocusable()

    @Test
    fun eachOptionIsOneFocusStop() {
        setUp()
        // The radio button inside a row is not a focus stop of its own.
        rule.onAllNodes(focusableInside(sortOptionTag(SortBy.NAME)), useUnmergedTree = true)
            .assertCountEquals(0)

        rule.onNodeWithTag(sortOptionTag(SortBy.NAME)).requestFocus()
        rule.onNodeWithTag(sortOptionTag(SortBy.NAME)).performKeyInput {
            pressKey(Key.DirectionDown)
        }
        rule.waitForIdle()
        rule.onNodeWithTag(sortOptionTag(SortBy.IMDB_RATING)).assertIsFocused()
    }

    @Test
    fun okSelectsTheOptionAndTheOrder() {
        setUp()
        rule.onNodeWithTag(sortOptionTag(SortBy.DATE_ADDED)).requestFocus()
        rule.onNodeWithTag(sortOptionTag(SortBy.DATE_ADDED)).performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        rule.waitForIdle()

        rule.onNodeWithTag(sortOrderTag(SortOrder.DESCENDING)).requestFocus()
        rule.onNodeWithTag(sortOrderTag(SortOrder.DESCENDING)).performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        rule.waitForIdle()

        assertEquals(
            listOf(
                SortBy.DATE_ADDED to SortOrder.ASCENDING,
                SortBy.DATE_ADDED to SortOrder.DESCENDING,
            ),
            updates,
        )
    }
}
