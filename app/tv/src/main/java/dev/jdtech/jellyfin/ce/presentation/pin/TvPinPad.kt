package dev.jdtech.jellyfin.ce.presentation.pin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.jdtech.jellyfin.R
import dev.jdtech.jellyfin.ce.ui.focus.ephyraButtonBorder
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.ephyra.errorShake
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.profilelock.ProfilePinHasher
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.presentation.pin.PinError
import dev.jdtech.jellyfin.setup.presentation.pin.PinPrompt

/** The message for a rejected PIN. */
@Composable
fun PinError.message(): String =
    when (this) {
        PinError.WRONG -> stringResource(SetupR.string.ce_profile_pin_wrong)
        PinError.MISMATCH -> stringResource(SetupR.string.ce_profile_pin_mismatch)
    }

/**
 * The digits typed for [prompt]. A rejection ([PinPrompt.errorCount] going up) clears them, and so
 * does a new step of the same prompt ([key]).
 */
@Composable
fun rememberTvPinState(prompt: PinPrompt, key: Any? = null): MutableState<String> {
    val pin = rememberSaveable(prompt.userId, key) { mutableStateOf("") }
    LaunchedEffect(prompt.errorCount) { if (prompt.errorCount > 0) pin.value = "" }
    return pin
}

private val KeySize = 64.dp

/**
 * CE (#299): the PIN on TV. Dots for the typed digits, the reason for the last rejection, and a
 * keypad for the D-pad: 1 to 9, then delete, 0 and OK. The remote's digit keys and its delete key
 * type straight away, wherever focus is on the pad, and a digit key moves focus to OK. OK with
 * fewer than four digits does nothing.
 */
@Composable
fun TvPinPad(
    prompt: PinPrompt,
    pin: MutableState<String>,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstKey = remember { FocusRequester() }
    val okKey = remember { FocusRequester() }
    // tv-material's buttons keep the click handler of their first composition for the D-pad's OK,
    // and a new step hands in a new [pin] state. Read both through these, never from the closure.
    val currentPin by rememberUpdatedState(pin)
    val currentOnSubmit by rememberUpdatedState(onSubmit)

    fun type(digit: Char) {
        if (currentPin.value.length < ProfilePinHasher.MAX_LENGTH) currentPin.value += digit
    }

    fun delete() {
        currentPin.value = currentPin.value.dropLast(1)
    }

    fun submit() {
        val value = currentPin.value
        if (ProfilePinHasher.isValid(value)) currentOnSubmit(value)
    }

    LaunchedEffect(Unit) { runCatching { firstKey.requestFocus() } }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier.testTag("ce_pin_pad").onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val digit = event.key.digit()
                when {
                    digit != null -> {
                        type(digit)
                        // OK on the remote comes next: it must press the check key, not the
                        // key focus rested on, which would add a digit (#409).
                        runCatching { okKey.requestFocus() }
                        true
                    }
                    event.key == Key.Backspace || event.key == Key.Delete -> {
                        delete()
                        true
                    }
                    event.key == Key.NumPadEnter -> {
                        submit()
                        true
                    }
                    else -> false
                }
            },
    ) {
        PinDots(
            count = pin.value.length,
            error = prompt.error != null,
            modifier = Modifier.errorShake(prompt.errorCount),
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))
        // Always laid out, so the pad does not jump when the error comes and goes.
        Text(
            text = prompt.error?.message().orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.alpha(if (prompt.error != null) 1f else 0f),
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))
        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
            listOf("123", "456", "789").forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
                    row.forEach { digit ->
                        PadKey(
                            onClick = { type(digit) },
                            modifier =
                                if (digit == '1') Modifier.focusRequester(firstKey) else Modifier,
                        ) {
                            Text(text = digit.toString(), style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
                val deleteDescription = stringResource(SetupR.string.ce_profile_pin_delete)
                PadKey(
                    onClick = ::delete,
                    modifier = Modifier.semantics { contentDescription = deleteDescription },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete_digit),
                        contentDescription = null,
                    )
                }
                PadKey(onClick = { type('0') }) {
                    Text(text = "0", style = MaterialTheme.typography.titleLarge)
                }
                // Dimmed, not disabled, until the PIN is long enough: focus can still rest on it. The
                // icon is dimmed rather than the key, so the focus ring outside it is not cut off.
                PadKey(
                    onClick = ::submit,
                    modifier = Modifier.focusRequester(okKey).testTag("ce_pin_ok"),
                ) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_check),
                        contentDescription = null,
                        modifier =
                            Modifier.alpha(if (ProfilePinHasher.isValid(pin.value)) 1f else 0.4f),
                    )
                }
            }
        }
    }
}

/** One key of the pad: a round button with the focus ring. Never disabled, so focus can rest on it. */
@Composable
private fun PadKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = ButtonDefaults.shape(shape = CircleShape),
        border = ephyraButtonBorder(),
        contentPadding = PaddingValues(0.dp),
        modifier = modifier.size(KeySize),
    ) {
        Box(modifier = Modifier.size(KeySize), contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * A dot per typed digit, and an empty ring for each of the four a PIN needs at least. Up to six
 * dots, never the digits themselves.
 */
@Composable
private fun PinDots(count: Int, error: Boolean, modifier: Modifier = Modifier) {
    val slots = maxOf(ProfilePinHasher.MIN_LENGTH, count)
    val filled = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
        modifier = modifier.padding(vertical = MaterialTheme.spacings.small),
    ) {
        repeat(slots) { index ->
            Box(
                modifier =
                    Modifier.size(16.dp)
                        .then(
                            if (index < count) Modifier.background(filled, CircleShape)
                            else Modifier.border(2.dp, outline, CircleShape)
                        )
            )
        }
    }
}

private fun Key.digit(): Char? =
    when (this) {
        Key.Zero, Key.NumPad0 -> '0'
        Key.One, Key.NumPad1 -> '1'
        Key.Two, Key.NumPad2 -> '2'
        Key.Three, Key.NumPad3 -> '3'
        Key.Four, Key.NumPad4 -> '4'
        Key.Five, Key.NumPad5 -> '5'
        Key.Six, Key.NumPad6 -> '6'
        Key.Seven, Key.NumPad7 -> '7'
        Key.Eight, Key.NumPad8 -> '8'
        Key.Nine, Key.NumPad9 -> '9'
        else -> null
    }
