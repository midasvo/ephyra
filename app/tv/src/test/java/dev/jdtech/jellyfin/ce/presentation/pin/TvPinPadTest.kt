package dev.jdtech.jellyfin.ce.presentation.pin

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.setup.presentation.pin.PinPrompt
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CE (#409): typing the PIN with the remote's number keys and then pressing OK submits it. OK used
 * to press the "1" key, where focus rested since the pad opened, and added a digit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TvPinPadTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val pin = mutableStateOf("")
    private val submitted = mutableListOf<String>()

    private fun setUpPad() {
        rule.setContent {
            FindroidTheme {
                TvPinPad(
                    prompt = PinPrompt(userId = UUID.randomUUID(), userName = "second"),
                    pin = pin,
                    onSubmit = { submitted += it },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun press(keys: List<Key>) {
        keys.forEach { key ->
            rule.onNode(isFocused()).performKeyInput { pressKey(key) }
            rule.waitForIdle()
        }
    }

    @Test
    fun `OK after the number keys submits the PIN`() {
        setUpPad()

        press(listOf(Key.One, Key.Two, Key.Three, Key.Four))
        rule.onNodeWithTag("ce_pin_ok").assertIsFocused()

        press(listOf(Key.DirectionCenter))
        assertEquals("1234", pin.value)
        assertEquals(listOf("1234"), submitted)
    }

    @Test
    fun `OK after too few number keys neither submits nor adds a digit`() {
        setUpPad()

        press(listOf(Key.One, Key.Two, Key.Three, Key.DirectionCenter))
        assertEquals("123", pin.value)
        assertEquals(emptyList<String>(), submitted)
    }

    @Test
    fun `delete and the D-pad still work after the number keys`() {
        setUpPad()

        press(listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Backspace))
        assertEquals("123", pin.value)

        // From OK, Left is the 0 key.
        press(listOf(Key.DirectionLeft, Key.DirectionCenter))
        assertEquals("1230", pin.value)
        assertEquals(emptyList<String>(), submitted)
    }
}
