package dev.jdtech.jellyfin.presentation.utils

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp

/** Width of the phone's keyboard focus ring (#398), the same as the player's (#449). */
val KeyboardFocusRingWidth = 2.dp

/** Gap between the card and its ring, as on TV (VISION.md §5: "+2 dp offset"). */
private val KeyboardFocusRingGap = 2.dp

/**
 * CE (#398): a 2 dp `primary` ring in [shape], 2 dp outside the element, while the clickable after
 * this modifier has keyboard focus. VISION.md: `primary` is for focus, and the glow is only on TV,
 * so there is none here. The default state layer does not show on artwork. A touch never focuses
 * the card, so a tap shows no ring.
 */
@Composable
fun Modifier.keyboardFocusRing(shape: Shape): Modifier {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val color = MaterialTheme.colorScheme.primary
    val show = focused && keyboard
    return this.onFocusChanged { focused = it.hasFocus }
        .drawWithContent {
            drawContent()
            if (show) {
                val stroke = KeyboardFocusRingWidth.toPx()
                val offset = KeyboardFocusRingGap.toPx() + stroke / 2
                val ringSize = Size(size.width + 2 * offset, size.height + 2 * offset)
                val outline = shape.createOutline(ringSize, layoutDirection, this)
                translate(-offset, -offset) {
                    drawOutline(outline, color, style = Stroke(stroke))
                }
            }
        }
}
