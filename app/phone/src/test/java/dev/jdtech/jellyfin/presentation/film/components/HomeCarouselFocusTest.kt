package dev.jdtech.jellyfin.presentation.film.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #398: on Home's carousel a keyboard only reaches the pages that are fully on screen, never a
 * peeking one, and Left and Right page the carousel with the focus following.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h914dp-port")
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
class HomeCarouselFocusTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val movies =
        List(5) { index -> dummyMovie.copy(id = UUID.randomUUID(), name = "Movie ${index + 1}") }

    private lateinit var focusManager: FocusManager
    private lateinit var inputModeManager: InputModeManager

    private fun page(name: String) = rule.onNode(hasClickAction() and hasText(name))

    private fun setCarousel() {
        rule.setContent {
            focusManager = LocalFocusManager.current
            inputModeManager = LocalInputModeManager.current
            FindroidTheme {
                Column {
                    Button(onClick = {}) { Text("Before") }
                    HomeCarousel(
                        items = movies,
                        itemsPadding = PaddingValues(horizontal = 24.dp),
                        onAction = {},
                    )
                    Button(onClick = {}) { Text("After") }
                }
            }
        }
        rule.runOnIdle { inputModeManager.requestInputMode(InputMode.Keyboard) }
        rule.onNodeWithText("Before").requestFocus()
    }

    private fun next() {
        rule.runOnIdle { focusManager.moveFocus(FocusDirection.Next) }
        rule.waitForIdle()
    }

    @Test
    fun `Tab reaches the page on screen and skips the peeking one`() {
        setCarousel()

        next()
        page("Movie 1").assertIsFocused()
        next()
        rule.onNodeWithText("After").assertIsFocused()
    }

    @Test
    fun `Right and Left page the carousel and the focus follows`() {
        setCarousel()
        next()

        page("Movie 1").performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        page("Movie 2").assertIsFocused()

        page("Movie 2").performKeyInput { pressKey(Key.DirectionLeft) }
        rule.waitForIdle()
        page("Movie 1").assertIsFocused()
    }

    @Test
    fun `the focusable pages are the ones the settled pager shows in full`() {
        assertEquals(0..0, carouselFocusablePages(settledPage = 0, pages = 1, pageCount = 5))
        assertEquals(2..4, carouselFocusablePages(settledPage = 2, pages = 3, pageCount = 5))
        assertEquals(4..4, carouselFocusablePages(settledPage = 4, pages = 3, pageCount = 5))
    }

    @Test
    fun `an arrow moves the focus one page and pages the carousel only when it must`() {
        // One page on screen: the pager follows the focus.
        assertEquals(3 to 3, carouselArrowTarget(2, forward = true, 2, pages = 1, pageCount = 5))
        assertEquals(1 to 1, carouselArrowTarget(2, forward = false, 2, pages = 1, pageCount = 5))
        // Three on screen (0, 1, 2): the focus moves within them, then the pager moves by one.
        assertEquals(1 to 0, carouselArrowTarget(0, forward = true, 0, pages = 3, pageCount = 5))
        assertEquals(3 to 1, carouselArrowTarget(2, forward = true, 0, pages = 3, pageCount = 5))
        // The ends.
        assertNull(carouselArrowTarget(4, forward = true, 4, pages = 1, pageCount = 5))
        assertNull(carouselArrowTarget(0, forward = false, 0, pages = 1, pageCount = 5))
    }
}
