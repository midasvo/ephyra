package dev.jdtech.jellyfin.ce.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.jdtech.jellyfin.ce.ui.focus.ephyraFocusedBorder
import kotlinx.coroutines.launch

/**
 * A text cut to [maxLines], or to the height it is given, such as an overview or a biography (#508,
 * #545). Only when it is cut, it takes focus with the Ephyra ring and OK opens the full text in a
 * [TvTextDialog] titled [title]; Back from the dialog puts focus on it again. A text that fits
 * takes no focus.
 */
@Composable
fun TvOverviewText(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    testTag: String = TV_OVERVIEW_TAG,
) {
    var cut by remember(text) { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    // The colour of the page, not the Surface's, so the text looks the same focusable or not.
    val textColor = if (color.isSpecified) color else LocalContentColor.current
    val content =
        @Composable {
            Text(
                text = text,
                style = style,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                color = textColor,
                onTextLayout = { cut = it.hasVisualOverflow },
            )
        }
    if (cut) {
        Surface(
            onClick = { open = true },
            shape = ClickableSurfaceDefaults.shape(shape = MaterialTheme.shapes.small),
            colors =
                ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = textColor,
                    focusedContainerColor = Color.Transparent,
                    focusedContentColor = textColor,
                    pressedContainerColor = Color.Transparent,
                    pressedContentColor = textColor,
                ),
            // No ring while the dialog is open: the page keeps its focus behind the dialog, and the
            // ring's left end showed beside the 800 dp dialog.
            border =
                if (open) ClickableSurfaceDefaults.border()
                else ClickableSurfaceDefaults.border(focusedBorder = ephyraFocusedBorder()),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = modifier.focusRequester(focusRequester).testTag(testTag),
        ) {
            content()
        }
    } else {
        Box(modifier = modifier.testTag(testTag)) { content() }
    }
    if (open) {
        TvTextDialog(
            title = title,
            text = text,
            onDismiss = {
                open = false
                // The dialog's window has to go before focus can come back to the page.
                scope.launch {
                    repeat(FOCUS_RETURN_ATTEMPTS) {
                        withFrameNanos {}
                        if (focusRequester.requestFocus()) return@launch
                    }
                }
            },
        )
    }
}

internal const val TV_OVERVIEW_TAG = "ce_tv_overview"

private const val FOCUS_RETURN_ATTEMPTS = 10
