package dev.jdtech.jellyfin.ce.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import dev.jdtech.jellyfin.ce.ui.TvSafeArea
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import kotlinx.coroutines.launch

/**
 * CE (#508): a long text read in full, such as a biography. The text has focus: Up and Down scroll
 * it a third of its height at a time, Back closes the dialog. The caller puts focus back on what
 * opened it.
 */
@Composable
fun TvTextDialog(title: String, text: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            // Inside the TV safe area at any font scale; the text takes what the title leaves. Wide
            // enough to cover a focus ring on the page behind it (the person page's biography).
            modifier =
                Modifier.width(800.dp)
                    .heightIn(max = TvTextDialogMaxHeight)
                    .testTag(TV_TEXT_DIALOG_TAG),
            shape = RoundedCornerShape(12.dp),
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(MaterialTheme.spacings.large)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))

                val scrollState = rememberScrollState()
                val scope = rememberCoroutineScope()
                val focusRequester = remember { FocusRequester() }
                var viewportHeight by remember { mutableIntStateOf(0) }
                LaunchedEffect(Unit) { focusRequester.requestFocus() }

                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier =
                        Modifier.weight(1f, fill = false)
                            .onSizeChanged { viewportHeight = it.height }
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) {
                                    return@onPreviewKeyEvent false
                                }
                                val direction =
                                    when (event.key.nativeKeyCode) {
                                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> 1
                                        AndroidKeyEvent.KEYCODE_DPAD_UP -> -1
                                        else -> return@onPreviewKeyEvent false
                                    }
                                scope.launch {
                                    scrollState.animateScrollBy(direction * viewportHeight / 3f)
                                }
                                true
                            }
                            .focusable()
                            .verticalScroll(scrollState)
                            .testTag(TV_TEXT_DIALOG_TEXT_TAG),
                )
            }
        }
    }
}

internal const val TV_TEXT_DIALOG_TEXT_TAG = "ce_tv_text_dialog_text"
internal const val TV_TEXT_DIALOG_TAG = "ce_tv_text_dialog"

/** A 1080p TV's height (540 dp) without the safe area above and below. */
private val TvTextDialogMaxHeight = 540.dp - TvSafeArea.vertical * 2

@Preview(device = "id:tv_1080p")
@Composable
private fun TvTextDialogPreview() {
    FindroidTheme {
        TvTextDialog(
            title = "Cary Grant",
            text = "Cary Grant was a British-American actor. ".repeat(30),
            onDismiss = {},
        )
    }
}
