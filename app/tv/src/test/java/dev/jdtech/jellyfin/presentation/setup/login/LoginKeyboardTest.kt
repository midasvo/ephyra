package dev.jdtech.jellyfin.presentation.setup.login

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.setup.presentation.login.LoginState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CE: on the TV login screen moving focus onto a text field doesn't make it editable (which opens
 * the keyboard); OK does. Text typed stays when focus moves away and back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp-television")
class LoginKeyboardTest {
    @get:Rule val rule = createComposeRule()

    private fun field(label: String) =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText) and hasText(label)

    private val username = field("Username")
    private val password = field("Password")
    private val editable = hasSetTextAction()

    private fun setLogin() {
        rule.setContent {
            FindroidTheme {
                LoginScreenLayout(state = LoginState(serverName = "Ephyra Test"), onAction = {})
            }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onNode(isFocused()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    /**
     * Focus moves as with the D-pad. Robolectric's key events don't come from a D-pad, and a text
     * field moves its cursor on arrow keys from a keyboard instead of moving focus.
     */
    private fun focus(matcher: SemanticsMatcher) {
        rule.onNode(matcher).performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
    }

    private fun assertReadOnly(matcher: SemanticsMatcher) =
        rule.onNode(matcher).assert(!editable)

    @Test
    fun `focus on a field doesn't make it editable, OK does`() {
        setLogin()
        rule.onNode(username).assertIsFocused()
        assertReadOnly(username)

        focus(password)
        rule.onNode(password).assertIsFocused()
        assertReadOnly(password)
        focus(username)
        rule.onNode(username).assertIsFocused()
        assertReadOnly(username)

        press(Key.DirectionCenter)
        rule.onNode(username).assertIsFocused().assert(editable)
    }

    @Test
    fun `typed text stays when focus moves away and back`() {
        setLogin()
        press(Key.DirectionCenter)
        rule.onNode(username).performTextInput("second")

        focus(password)
        rule.onNode(password).assertIsFocused()
        assertReadOnly(password)
        assertReadOnly(username.and(hasText("second")))

        focus(username)
        rule.onNode(username).assertIsFocused().assert(hasText("second"))
        assertReadOnly(username)
    }

    @Test
    fun `Next on Username moves to an editable Password`() {
        setLogin()
        press(Key.DirectionCenter)
        rule.onNode(username).performTextInput("second")

        rule.onNode(username).performImeAction()
        rule.waitForIdle()

        rule.onNode(password).assertIsFocused().assert(editable)
        assertReadOnly(username)
    }

    @Test
    fun `a letter typed on a hardware keyboard reaches the read-only field`() {
        setLogin()
        assertReadOnly(username)

        // In one go, as a keyboard sends them: the second arrives before the field is editable.
        rule.onNode(username).performKeyInput {
            pressKey(Key.S)
            pressKey(Key.E)
            pressKey(Key.C)
        }
        rule.waitForIdle()

        rule.onNode(username).assertIsFocused().assert(editable).assert(hasText("sec"))
    }
}
