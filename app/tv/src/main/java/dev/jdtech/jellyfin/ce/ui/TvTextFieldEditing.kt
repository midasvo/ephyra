package dev.jdtech.jellyfin.ce.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.launch

/**
 * CE: a TV text field that opens the keyboard on OK, not when focus moves onto it. The IME shows
 * itself whenever an input session starts, also with `showKeyboardOnFocus = false`, so the field is
 * read-only (no session) until OK. Pass `readOnly = !editing.editing` to the field and put
 * [tvTextFieldEditing] on its modifier. Leaving the field makes it read-only again; the text is the
 * caller's, so it stays.
 */
@Stable
class TvTextFieldEditing internal constructor() {
    var editing by mutableStateOf(false)
        private set

    internal var focused = false

    /**
     * Makes the field editable, so the keyboard opens when it is focused: for the IME's Next, before
     * it moves focus here.
     */
    fun startEditing() {
        editing = true
    }

    internal fun stopEditing() {
        editing = false
    }
}

@Composable
fun rememberTvTextFieldEditing(): TvTextFieldEditing = remember { TvTextFieldEditing() }

/**
 * OK on the focused read-only field makes it editable and opens the keyboard. A letter typed on a
 * hardware keyboard does the same and then reaches the field.
 */
@Composable
fun Modifier.tvTextFieldEditing(state: TvTextFieldEditing): Modifier {
    val keyboardController = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    // Keys typed while the field turns editable, sent again once it is. Plain fields: no state.
    val held = remember { mutableListOf<android.view.KeyEvent>() }
    val replaying = remember { booleanArrayOf(false) }
    LaunchedEffect(state.editing) { if (state.editing && state.focused) keyboardController?.show() }
    return this.onFocusChanged {
            // It also reports "not focused" when attached: only act on a loss.
            if (state.focused && !it.isFocused) state.stopEditing()
            // Made editable before focus came here (the IME's Next): keep the keyboard up.
            if (!state.focused && it.isFocused && state.editing) keyboardController?.show()
            state.focused = it.isFocused
        }
        .onPreviewKeyEvent { event ->
            when {
                replaying[0] -> false
                // The field isn't editable yet: these follow the first typed key.
                held.isNotEmpty() -> {
                    held += event.nativeKeyEvent
                    true
                }
                state.editing -> false
                event.key.isOk() -> {
                    if (event.type == KeyEventType.KeyUp) state.startEditing()
                    true
                }
                event.type == KeyEventType.KeyDown && event.isTyped() -> {
                    // The field takes no text while it is read-only: send the keys again once it
                    // is editable.
                    state.startEditing()
                    held += event.nativeKeyEvent
                    scope.launch {
                        withFrameNanos {}
                        withFrameNanos {}
                        val keys = held.toList()
                        held.clear()
                        replaying[0] = true
                        keys.forEach { view.dispatchKeyEvent(it) }
                        replaying[0] = false
                    }
                    true
                }
                else -> false
            }
        }
}

private fun Key.isOk() = this == Key.DirectionCenter || this == Key.Enter || this == Key.NumPadEnter

private fun androidx.compose.ui.input.key.KeyEvent.isTyped(): Boolean =
    utf16CodePoint != 0 && !isCtrlPressed && !isAltPressed && !isMetaPressed && !key.isOk()
