package dev.jdtech.jellyfin.presentation.setup.pin

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.presentation.ephyra.EphyraMotion
import dev.jdtech.jellyfin.core.presentation.ephyra.errorShake
import dev.jdtech.jellyfin.models.ProfileLockType
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.profilelock.ProfilePattern
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.presentation.pin.PinError
import dev.jdtech.jellyfin.setup.presentation.pin.PinPrompt
import java.util.UUID

/** CE (#330): the message for a rejected pattern. */
@Composable
fun PinError.patternMessage(): String =
    when (this) {
        PinError.WRONG -> stringResource(SetupR.string.ce_profile_pattern_wrong)
        PinError.MISMATCH -> stringResource(SetupR.string.ce_profile_pattern_mismatch)
    }

/**
 * CE (#330): the unlock pattern, a 3×3 grid of dots. Dragging connects the dots it passes (none
 * twice, and a line straight across an unconnected dot takes that dot too); lifting the finger
 * hands the dots to [onPattern] when there are at least [ProfilePattern.MIN_DOTS], and otherwise
 * says so and shakes.
 *
 * The drawn pattern stays on screen while it is checked. A rejection ([PinPrompt.errorCount] going
 * up) turns it `error` and shakes the grid, with the reason under it; the next touch starts over.
 * [stepKey] clears the grid for a new step of the same prompt.
 *
 * Colours (docs/identity/VISION.md §3): dots at rest `onSurfaceVariant`; the line being drawn and
 * its dots `primary`, since they are happening now; a rejected pattern `error`. No glow. Colour
 * changes take `durationShort`, nothing scales.
 */
@Composable
fun PatternGrid(
    prompt: PinPrompt,
    onPattern: (List<Int>) -> Unit,
    modifier: Modifier = Modifier,
    stepKey: Any? = null,
) {
    var dots by remember(prompt.userId, stepKey) { mutableStateOf(emptyList<Int>()) }
    var finger by remember(prompt.userId, stepKey) { mutableStateOf<Offset?>(null) }
    var tooShort by remember(prompt.userId, stepKey) { mutableStateOf(false) }
    var tooShortCount by remember(prompt.userId, stepKey) { mutableIntStateOf(0) }
    // The rejection whose colour the last touch has cleared.
    var seenErrorCount by remember(prompt.userId, stepKey) { mutableIntStateOf(prompt.errorCount) }
    val haptics = LocalHapticFeedback.current
    // The gesture outlives recompositions: it reads the latest prompt and callback through these.
    val currentErrorCount by rememberUpdatedState(prompt.errorCount)
    val currentOnPattern by rememberUpdatedState(onPattern)

    val rejected = tooShort || (prompt.error != null && seenErrorCount != prompt.errorCount)
    val lineColor by
        animateColorAsState(
            targetValue =
                if (rejected) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            animationSpec =
                tween(EphyraMotion.DURATION_SHORT, easing = EphyraMotion.EasingStandard),
            label = "patternColor",
        )
    val restColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridDescription = stringResource(SetupR.string.ce_profile_pattern_grid)

    val message =
        when {
            tooShort -> stringResource(SetupR.string.ce_profile_pattern_too_short)
            else -> prompt.error?.patternMessage()
        }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        modifier = modifier,
    ) {
        Canvas(
            modifier =
                Modifier.widthIn(max = 280.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .errorShake(prompt.errorCount + tooShortCount)
                    .semantics { contentDescription = gridDescription }
                    .testTag("ce_pattern_grid")
                    .pointerInput(prompt.userId, stepKey) {
                        val cell = size.width / ProfilePattern.SIZE.toFloat()
                        val hitRadius = cell * 0.32f

                        fun center(dot: Int) =
                            Offset(
                                (dot % ProfilePattern.SIZE + 0.5f) * cell,
                                (dot / ProfilePattern.SIZE + 0.5f) * cell,
                            )

                        fun dotAt(position: Offset): Int? =
                            (0 until ProfilePattern.DOTS).firstOrNull {
                                (center(it) - position).getDistance() <= hitRadius
                            }

                        // Every dot on the way from [from] to [to], so a fast swipe misses none.
                        fun connectAlong(from: Offset, to: Offset) {
                            val steps = ((to - from).getDistance() / (hitRadius / 2)).toInt() + 1
                            for (i in 1..steps) {
                                val dot = dotAt(from + (to - from) * (i / steps.toFloat()))
                                if (dot != null && dot !in dots) {
                                    dots = ProfilePattern.connect(dots, dot)
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                }
                            }
                        }

                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            dots = emptyList()
                            tooShort = false
                            seenErrorCount = currentErrorCount
                            finger = down.position
                            connectAlong(down.position, down.position)
                            var last = down.position
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                connectAlong(last, change.position)
                                last = change.position
                                finger = change.position
                                change.consume()
                            }
                            finger = null
                            when {
                                dots.isEmpty() -> Unit
                                dots.size < ProfilePattern.MIN_DOTS -> {
                                    tooShort = true
                                    tooShortCount++
                                }
                                else -> currentOnPattern(dots)
                            }
                        }
                    }
        ) {
            val cell = size.width / ProfilePattern.SIZE
            fun center(dot: Int) =
                Offset(
                    (dot % ProfilePattern.SIZE + 0.5f) * cell,
                    (dot / ProfilePattern.SIZE + 0.5f) * cell,
                )

            val lineWidth = 4.dp.toPx()
            val line = lineColor.copy(alpha = 0.7f)
            dots.zipWithNext().forEach { (from, to) ->
                drawLine(line, center(from), center(to), lineWidth, StrokeCap.Round)
            }
            val current = finger
            if (current != null && dots.isNotEmpty()) {
                drawLine(line, center(dots.last()), current, lineWidth, StrokeCap.Round)
            }
            for (dot in 0 until ProfilePattern.DOTS) {
                if (dot in dots) {
                    drawCircle(lineColor, radius = 8.dp.toPx(), center = center(dot))
                    drawCircle(
                        lineColor,
                        radius = 18.dp.toPx(),
                        center = center(dot),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                } else {
                    drawCircle(restColor, radius = 6.dp.toPx(), center = center(dot))
                }
            }
        }
        Text(
            text = message.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            // Room for the message whether or not it shows, so the grid does not jump.
            modifier = Modifier.fillMaxWidth().heightIn(min = 20.dp),
        )
    }
}

/**
 * CE (#330): asks for a pattern in a dialog, the [PinDialog] of a pattern lock. The pattern is
 * taken when the finger lifts, so there is no confirm button. Cancel and Back call [onDismiss].
 */
@Composable
fun PatternDialog(
    prompt: PinPrompt,
    title: String,
    onSubmit: (List<Int>) -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
    footnote: String? = null,
    stepKey: Any? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
            ) {
                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                PatternGrid(
                    prompt = prompt,
                    onPattern = onSubmit,
                    stepKey = stepKey,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (footnote != null) {
                    Text(
                        text = footnote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(SetupR.string.cancel)) }
        },
    )
}

@Preview
@Composable
private fun PatternDialogPreview() {
    FindroidTheme {
        PatternDialog(
            prompt =
                PinPrompt(
                    UUID.randomUUID(),
                    "Midas",
                    PinError.WRONG,
                    1,
                    lockType = ProfileLockType.PATTERN,
                ),
            title = "Draw the pattern for Midas",
            footnote = "Forgot the pattern? Remove this user and sign in again.",
            onSubmit = {},
            onDismiss = {},
        )
    }
}
