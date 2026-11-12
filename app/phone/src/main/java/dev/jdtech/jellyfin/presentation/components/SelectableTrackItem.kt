package dev.jdtech.jellyfin.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR

/**
 * Selectable list row displaying track name, characteristic badges, checkmark indicator, and
 * unified rounded card styling used across media player and Chromecast controls.
 *
 * @param title Primary text description of the selection item.
 * @param chips List of badge labels (e.g. language or codec chips).
 * @param isSelected Whether this row is currently chosen.
 * @param onClick Callback when the row is tapped.
 * @param modifier Optional modifier for the row. Defaults to [Modifier].
 * @param shape Corner [Shape] of the row. Defaults to `MaterialTheme.shapes.medium`.
 */
@Composable
fun SelectableTrackItem(
    title: String,
    chips: List<String>,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
) {
    // A selection is a state, not something alive: the nav indicator's roles, not cyan
    // (VISION §3.4).
    val containerColor =
        if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    val contentColor =
        if (isSelected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }

    Surface(
        selected = isSelected,
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = shape,
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isSelected) {
                Icon(
                    painter = painterResource(id = CoreR.drawable.ic_check),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = contentColor,
                )
            } else {
                Spacer(modifier = Modifier.size(20.dp))
            }

            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            if (chips.isNotEmpty()) {
                val scrollState = rememberScrollState()
                Row(
                    modifier =
                        Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                            .drawWithContent {
                                drawContent()
                                // An alpha mask (DstIn) that fades the chip row's edges, not a colour.
                                val edgeWidth = 14.dp.toPx()
                                if (scrollState.value > 0) {
                                    drawRect(
                                        brush =
                                            Brush.horizontalGradient(
                                                colors = listOf(Color.Transparent, Color.Black),
                                                startX = 0f,
                                                endX = edgeWidth,
                                            ),
                                        blendMode = BlendMode.DstIn,
                                    )
                                }
                                if (scrollState.value < scrollState.maxValue) {
                                    drawRect(
                                        brush =
                                            Brush.horizontalGradient(
                                                colors = listOf(Color.Black, Color.Transparent),
                                                startX = size.width - edgeWidth,
                                                endX = size.width,
                                            ),
                                        blendMode = BlendMode.DstIn,
                                    )
                                }
                            }
                            .horizontalScroll(scrollState),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (chip in chips) {
                        Box(
                            modifier =
                                Modifier.clip(MaterialTheme.shapes.extraSmall)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = chip,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
