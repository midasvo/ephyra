package dev.jdtech.jellyfin.presentation.film.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.utils.WidthClass
import java.util.UUID
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #438: the Home carousel shows 1, 2 or 3 pages by the window's width class
 * (docs/tablet/4-design.md §12). It counted from the pager's own width, the window minus the rail
 * and the margins, so an 8 inch tablet in portrait and a fold showed a page fewer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeCarouselPagesTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val movies =
        List(5) { index -> dummyMovie.copy(id = UUID.randomUUID(), name = "Movie ${index + 1}") }

    @Test
    fun `one page per width class`() {
        assertEquals(1, carouselPageCount(WidthClass.Compact, compactHeight = false))
        assertEquals(2, carouselPageCount(WidthClass.Medium, compactHeight = false))
        assertEquals(3, carouselPageCount(WidthClass.Expanded, compactHeight = false))
    }

    @Test
    fun `a window of compact height keeps two pages`() {
        assertEquals(1, carouselPageCount(WidthClass.Compact, compactHeight = true))
        assertEquals(2, carouselPageCount(WidthClass.Medium, compactHeight = true))
        assertEquals(2, carouselPageCount(WidthClass.Expanded, compactHeight = true))
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-port")
    fun `a phone shows one page`() = assertPages(1)

    @Test
    @Config(qualifiers = "w914dp-h411dp-land")
    fun `a phone in landscape shows two pages`() = assertPages(2)

    @Test
    @Config(qualifiers = "w640dp-h1024dp-port")
    fun `an 8 inch tablet in portrait shows two pages`() = assertPages(2)

    @Test
    @Config(qualifiers = "w701dp-h841dp-port")
    fun `a fold in portrait shows two pages`() = assertPages(2)

    @Test
    @Config(qualifiers = "w841dp-h701dp-land")
    fun `a fold in landscape shows three pages`() = assertPages(3)

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun `a 10 inch tablet in landscape shows three pages`() = assertPages(3)

    private fun assertPages(expected: Int) {
        // As on Home at medium width and up: the 80 dp rail beside it, 24 dp margins.
        val rail = 80.dp
        val margin = 24.dp
        rule.setContent {
            FindroidTheme {
                Row {
                    Spacer(Modifier.width(rail))
                    HomeCarousel(
                        items = movies,
                        itemsPadding = PaddingValues(horizontal = margin),
                        onAction = {},
                    )
                }
            }
        }
        val window = rule.onRoot().getUnclippedBoundsInRoot().width
        val page = rule.onAllNodes(hasClickAction()).onFirst().getUnclippedBoundsInRoot().width
        // The pages share the pager's width less the 16 dp spacing between them.
        val pager = window - rail - margin * 2
        val expectedPage: Dp = (pager - 16.dp * (expected - 1)) / expected
        assertTrue(
            "page is $page in a $window window, expected $expectedPage",
            abs((page - expectedPage).value) <= 1f,
        )
    }
}
