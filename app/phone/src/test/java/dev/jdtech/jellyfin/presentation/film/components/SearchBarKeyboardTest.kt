package dev.jdtech.jellyfin.presentation.film.components

import android.app.Application
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import dev.jdtech.jellyfin.film.presentation.search.SearchState
import dev.jdtech.jellyfin.presentation.utils.KeyboardShortcuts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #430: with a hardware keyboard, Tab onto the collapsed search bar on My media opened the
 * full-screen search, so the library cards were never reached, and Esc did not close it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
class SearchBarKeyboardTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var expanded by mutableStateOf(false)
    private lateinit var inputModeManager: InputModeManager

    private fun setBar() {
        rule.setContent {
            inputModeManager = LocalInputModeManager.current
            FilmSearchBar(
                state = SearchState(),
                expanded = expanded,
                onExpand = { expanded = it },
                onAction = {},
            )
        }
    }

    private fun focusFromKeyboard() {
        rule.runOnIdle { inputModeManager.requestInputMode(InputMode.Keyboard) }
        rule.onNode(hasSetTextAction()).requestFocus()
        rule.waitForIdle()
    }

    private val field
        get() = rule.onNode(hasSetTextAction())

    @Test
    fun keyboardFocusLeavesTheBarClosed() {
        setBar()
        focusFromKeyboard()
        rule.runOnIdle { assertFalse(expanded) }
    }

    @Test
    fun enterOpensTheFocusedBar() {
        setBar()
        focusFromKeyboard()
        field.performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle { assertTrue(expanded) }
    }

    @Test
    fun typingOpensTheFocusedBar() {
        setBar()
        focusFromKeyboard()
        field.performTextInput("dra")
        rule.runOnIdle { assertTrue(expanded) }
    }

    @Test
    fun aTapOpensTheBar() {
        setBar()
        field.performTouchInput { click() }
        rule.runOnIdle { assertTrue(expanded) }
    }

    @Test
    fun aTapAfterTabOpensTheBar() {
        setBar()
        focusFromKeyboard()
        field.performTouchInput { click() }
        rule.runOnIdle { assertTrue(expanded) }
    }

    @Test
    fun aMouseClickAfterTabOpensTheBar() {
        setBar()
        focusFromKeyboard()
        field.performMouseInput { click() }
        rule.runOnIdle { assertTrue(expanded) }
    }

    @Test
    fun escapeIsTakenOnlyWhileAScreenListens() {
        var open by mutableStateOf(true)
        rule.setContent { if (open) KeyboardShortcuts.OnEscape { open = false } }
        rule.waitForIdle()

        assertTrue(KeyboardShortcuts.onKeyEvent(escape(AndroidKeyEvent.ACTION_DOWN)))
        assertTrue(KeyboardShortcuts.onKeyEvent(escape(AndroidKeyEvent.ACTION_UP)))
        rule.runOnIdle { assertFalse(open) }

        // Nothing listens once the search is closed, so Esc goes on to the views.
        assertFalse(KeyboardShortcuts.onKeyEvent(escape(AndroidKeyEvent.ACTION_DOWN)))
    }

    private fun escape(action: Int) = AndroidKeyEvent(action, AndroidKeyEvent.KEYCODE_ESCAPE)
}
