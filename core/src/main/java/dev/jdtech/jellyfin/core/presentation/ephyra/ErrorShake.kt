package dev.jdtech.jellyfin.core.presentation.ephyra

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * CE (#299): a short sideways shake each time [trigger] goes up, for a rejected PIN. It settles
 * back to rest with shrinking swings and no overshoot past the first one. Off under
 * [reducedMotion]; the error text next to it carries the state either way.
 */
@Composable
fun Modifier.errorShake(trigger: Int): Modifier {
    val offset = remember { Animatable(0f) }
    val reduced = reducedMotion()
    LaunchedEffect(trigger) {
        if (trigger == 0 || reduced) return@LaunchedEffect
        offset.snapTo(0f)
        for (target in listOf(10f, -8f, 5f, -3f, 0f)) {
            offset.animateTo(target, tween(durationMillis = 50))
        }
    }
    return graphicsLayer { translationX = offset.value * density }
}
