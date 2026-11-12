package dev.jdtech.jellyfin.ce.presentation.film

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import dev.jdtech.jellyfin.ce.ui.TvSafeArea
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.film.presentation.home.HomeState
import dev.jdtech.jellyfin.film.presentation.library.LibraryState
import dev.jdtech.jellyfin.models.HomeItem
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import java.util.UUID
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #423: text stays above the bottom of the TV safe area (5 %, 27 dp) on a 960 x 540 dp screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp")
class SafeAreaTest {
    @get:Rule val rule = createComposeRule()

    private val safeBottom: Dp = 540.dp - TvSafeArea.vertical

    private val title = "The Great St. Louis Bank Robbery"
    private val movies =
        (0 until 6).map { dummyMovie.copy(id = UUID(0, it.toLong()), name = "$title $it") }

    private fun assertAboveSafeBottom(bottom: Dp, what: String) {
        assertTrue("$what ends at $bottom, below the safe area ($safeBottom)", bottom <= safeBottom)
    }

    /**
     * Robolectric's text metrics are not the device's, so this checks the cause instead of pixels:
     * a Home card's title gets one line (two lines of a poster row end at 525 dp on a 1080p TV,
     * one line at 501 dp; at font scale 1.3, 534 and 507 dp).
     */
    @Test
    fun homeCardTitleIsOneLine() {
        rule.setContent {
            FindroidTheme {
                HomeScreenLayout(
                    state =
                        HomeState(
                            suggestionsSection =
                                HomeItem.Suggestions(id = UUID(1, 0), items = movies),
                            isLoading = false,
                        ),
                    refreshed = true,
                    onAction = {},
                )
            }
        }
        rule.waitForIdle()
        val card = hasAnyAncestor(hasTestTag(homeCardTag(movies[0].id)))
        rule.onNode(card and isFocusable()).assertIsFocused()

        // The poster without an image shows the name too: the title is the one below it.
        val title =
            rule.onAllNodes(card and hasText("$title 0"), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .maxBy { it.boundsInRoot.bottom }
        val layouts = mutableListOf<TextLayoutResult>()
        title.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertEquals(1, layouts.single().layoutInput.maxLines)
    }

    @Test
    fun libraryCounterStaysInTheSafeArea() {
        rule.setContent {
            FindroidTheme {
                LibraryScreenLayout(
                    libraryName = "Movies",
                    state = LibraryState(items = flowOf(PagingData.from(movies))),
                    onAction = {},
                )
            }
        }
        rule.waitForIdle()
        val counter = rule.onNodeWithText("1 / ${movies.size}")
        assertAboveSafeBottom(counter.getBoundsInRoot().bottom, "The position counter")
    }
}
