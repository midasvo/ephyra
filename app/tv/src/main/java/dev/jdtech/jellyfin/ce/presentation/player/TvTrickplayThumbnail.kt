package dev.jdtech.jellyfin.ce.presentation.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.jdtech.jellyfin.player.core.domain.models.Trickplay
import dev.jdtech.jellyfin.player.local.domain.TV_TRICKPLAY_WIDTH_DP
import dev.jdtech.jellyfin.presentation.theme.spacings
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * CE (#134): the trickplay thumbnail for [positionMs], or null while there is none (no trickplay
 * data, not seeking, or the first one still loading). Loads only when the position crosses into
 * another thumbnail, and keeps the previous one on screen while the next loads, so holding a seek
 * key doesn't flicker.
 */
@Composable
fun rememberTrickplayFrame(trickplay: Trickplay?, positionMs: Long?): ImageBitmap? {
    var frame by remember(trickplay) { mutableStateOf<ImageBitmap?>(null) }
    val interval = trickplay?.interval?.takeIf { it > 0 }
    val index = if (interval != null && positionMs != null) positionMs / interval else null
    val position by rememberUpdatedState(positionMs)

    LaunchedEffect(trickplay, index) {
        if (trickplay == null || index == null) {
            frame = null
            return@LaunchedEffect
        }
        val bitmap =
            try {
                trickplay.loader?.tileAt(position ?: return@LaunchedEffect)
                    ?: trickplay.images.getOrNull(index.toInt())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Trickplay tile load failed")
                null
            }
        if (bitmap != null) frame = bitmap.asImageBitmap()
    }
    return frame
}

/** A thumbnail with an optional time below it, sized for the 10-foot UI. */
@Composable
fun TrickplayThumbnail(frame: ImageBitmap, timeText: String?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.extraSmall),
        modifier = modifier,
    ) {
        Image(
            bitmap = frame,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier.width(TV_TRICKPLAY_WIDTH_DP.dp)
                    .aspectRatio(frame.width.toFloat() / frame.height.coerceAtLeast(1))
                    .clip(shape)
                    .background(Color.Black)
                    .border(BorderStroke(2.dp, Color.White.copy(alpha = 0.8f)), shape),
        )
        if (timeText != null) {
            Text(
                text = timeText,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier =
                    Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * Places the element just above the one it's laid out in, centred on [fraction] of its width and
 * kept inside its edges. Takes no height, so the seek bar below doesn't move.
 */
fun Modifier.aboveAt(fraction: Float, gap: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    val width = constraints.maxWidth
    val x = aboveAtX(width, placeable.width, fraction)
    layout(width, 0) { placeable.place(x, -placeable.height - gap.roundToPx()) }
}

/** Where [Modifier.aboveAt] puts the left edge of an element [elementWidth] wide in [width]. */
internal fun aboveAtX(width: Int, elementWidth: Int, fraction: Float): Int =
    (width * fraction.coerceIn(0f, 1f) - elementWidth / 2f)
        .roundToInt()
        .coerceIn(0, (width - elementWidth).coerceAtLeast(0))

/**
 * CE (#464): whether the seek bar's thumbnail is on screen: a position is being picked and there is
 * a thumbnail for it. An item without trickplay never shows one, so nothing gives way for it.
 */
internal fun seekPreviewShown(pendingSeek: Boolean, hasFrame: Boolean): Boolean =
    pendingSeek && hasFrame

/**
 * CE (#464): the alpha of what the seek bar's thumbnail can cover (the title lines, the skip button
 * and the "Up next" card). The thumbnail is drawn above the time row without taking room, so they
 * fade out while it shows and come back when the seek is confirmed or dropped. Alpha only: nothing
 * moves and focus order stays as it is.
 */
@Composable
internal fun rememberGiveWayAlpha(previewShown: Boolean): () -> Float {
    val alpha =
        animateFloatAsState(
            targetValue = if (previewShown) 0f else 1f,
            animationSpec = tween(GIVE_WAY_MS),
            label = "seekPreviewGiveWay",
        )
    return remember(alpha) { { alpha.value } }
}

/** Fades with [rememberGiveWayAlpha]; read while drawing, so a fade does not recompose. */
internal fun Modifier.givesWayToSeekPreview(alpha: () -> Float): Modifier = graphicsLayer {
    this.alpha = alpha()
}

internal const val GIVE_WAY_MS = 150
