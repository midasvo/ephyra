package dev.jdtech.jellyfin.presentation.setup.login

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.AnnotatedString
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.setup.presentation.login.LoginState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Down and Left from the server button in the top corner of the TV login screen go back to the
 * form, to the element that was left (#557).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp-television")
class LoginServerButtonFocusTest {
    @get:Rule val rule = createComposeRule()

    // A text field, also while it is read-only (it takes text after OK).
    private val username =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText) and hasText("Username")
    private val loginButton = hasText("Login") and hasClickAction()
    private val quickConnectButton = hasText("Quick Connect") and hasClickAction()

    private fun setLogin() {
        rule.setContent {
            FindroidTheme {
                LoginScreenLayout(
                    state =
                        LoginState(
                            serverName = "Ephyra Test",
                            quickConnectEnabled = true,
                            disclaimer = AnnotatedString("A disclaimer under the form."),
                        ),
                    onAction = {},
                )
            }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onNode(isFocused()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun focus(matcher: SemanticsMatcher) {
        rule.onNode(matcher).performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
    }

    private fun assertServerButtonFocused() =
        rule.onNodeWithTag(LOGIN_SERVER_BUTTON_TAG).assertIsFocused()

    @Test
    fun `Down from the server button goes to Username the first time`() {
        setLogin()
        rule.onNode(username).assertIsFocused()
        focus(hasTestTag(LOGIN_SERVER_BUTTON_TAG))
        assertServerButtonFocused()

        press(Key.DirectionDown)

        rule.onNode(username).assertIsFocused()
    }

    @Test
    fun `Left from the server button goes to Username the first time`() {
        setLogin()
        focus(hasTestTag(LOGIN_SERVER_BUTTON_TAG))

        press(Key.DirectionLeft)

        rule.onNode(username).assertIsFocused()
    }

    @Test
    fun `Right from Login reaches the server button, and Left and Down return to Login`() {
        setLogin()
        focus(loginButton)

        press(Key.DirectionRight)
        assertServerButtonFocused()
        press(Key.DirectionLeft)
        rule.onNode(loginButton).assertIsFocused()

        press(Key.DirectionRight)
        assertServerButtonFocused()
        press(Key.DirectionDown)
        rule.onNode(loginButton).assertIsFocused()
    }

    @Test
    fun `Right from Quick Connect reaches the server button, and Left returns to it`() {
        setLogin()
        focus(quickConnectButton)

        press(Key.DirectionRight)
        assertServerButtonFocused()
        press(Key.DirectionLeft)

        rule.onNode(quickConnectButton).assertIsFocused()
    }
}
