package dev.jdtech.jellyfin.presentation.film.components

import android.app.Application
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.core.content.getSystemService
import dev.jdtech.jellyfin.utils.copyOnLongClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #442: a text that can only be copied is not a Tab stop, so Tab on an item page moves between
 * the controls. It is still copied by a long press, and a screen reader still finds it with its
 * copy action. A text that also does something on a click (the overview that expands) stays one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalComposeUiApi::class)
class CopyOnLongClickFocusTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var focusManager: FocusManager
    private lateinit var inputModeManager: InputModeManager

    private fun setTexts() {
        rule.setContent {
            focusManager = LocalFocusManager.current
            inputModeManager = LocalInputModeManager.current
            Column {
                Button(onClick = {}) { Text("Play") }
                Text("1940", modifier = Modifier.copyOnLongClick("1940"))
                Text("Overview", modifier = Modifier.copyOnLongClick("Overview", onClick = {}))
                Button(onClick = {}) { Text("Favorite") }
            }
        }
        rule.runOnIdle { inputModeManager.requestInputMode(InputMode.Keyboard) }
    }

    private fun next() {
        rule.runOnIdle { focusManager.moveFocus(FocusDirection.Next) }
        rule.waitForIdle()
    }

    @Test
    fun `Tab skips a text that only copies and stops on one that does more`() {
        setTexts()
        rule.onNodeWithText("Play").requestFocus()

        next()
        rule.onNodeWithText("1940").assertIsNotFocused()
        rule.onNodeWithText("Overview").assertIsFocused()

        next()
        rule.onNodeWithText("Favorite").assertIsFocused()
    }

    @Test
    fun `a long press still copies the text`() {
        setTexts()

        rule.onNodeWithText("1940").performTouchInput { longClick() }
        rule.waitForIdle()

        val clipboard = rule.activity.getSystemService<ClipboardManager>()!!
        assertEquals("1940", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `a screen reader still finds the text and its copy action`() {
        setTexts()

        rule
            .onNodeWithText("1940")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
    }
}
