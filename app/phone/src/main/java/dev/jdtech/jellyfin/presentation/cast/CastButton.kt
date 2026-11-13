package dev.jdtech.jellyfin.presentation.cast

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme

enum class CastButtonState {
    /** No Cast in this build, or turned off: there is no button. */
    HIDDEN,
    IDLE,
    CONNECTING,
    CONNECTED,
}

/** CE (#626): what the Cast button in a top bar shows and does. The shell provides it. */
@Immutable
class CastEntry(
    val state: CastButtonState = CastButtonState.HIDDEN,
    val deviceName: String? = null,
    val onClick: () -> Unit = {},
)

val LocalCastEntry = compositionLocalOf { CastEntry() }

/** The Cast button beside search and the profile in Home's header: the same 56 dp circle. */
@Composable
fun CastHeaderButton(modifier: Modifier = Modifier) {
    val entry = LocalCastEntry.current
    if (entry.state == CastButtonState.HIDDEN) return
    val description = castButtonDescription(entry)
    Surface(
        onClick = entry.onClick,
        modifier =
            modifier.fillMaxHeight().aspectRatio(1f).semantics {
                contentDescription = description
                role = Role.Button
            },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CastIcon(state = entry.state, restingTint = LocalContentColor.current)
        }
    }
}

/**
 * The Cast button at the end of an item page's top bar, in that bar's own button colours.
 * [overArtwork] keeps the connected icon white: no brand colour goes across artwork (VISION §1.1),
 * and the lit screen still tells the state.
 */
@Composable
fun CastTopBarButton(
    colors: IconButtonColors,
    overArtwork: Boolean,
    modifier: Modifier = Modifier,
) {
    val entry = LocalCastEntry.current
    if (entry.state == CastButtonState.HIDDEN) return
    val description = castButtonDescription(entry)
    IconButton(
        onClick = entry.onClick,
        modifier = modifier.semantics { contentDescription = description },
        colors = colors,
    ) {
        CastIcon(
            state = entry.state,
            restingTint = LocalContentColor.current,
            connectedTint =
                if (overArtwork) LocalContentColor.current else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun castButtonDescription(entry: CastEntry): String {
    val device = entry.deviceName
    return when {
        entry.state == CastButtonState.CONNECTED && device != null ->
            stringResource(CoreR.string.ce_cast_connected_to, device)
        entry.state == CastButtonState.CONNECTING && device != null ->
            stringResource(CoreR.string.ce_cast_connecting_to, device)
        else -> stringResource(CoreR.string.ce_cast_sheet_title)
    }
}

/**
 * The icon in its three states. Connected is the lit screen in `primary`: a session is something
 * that is happening now (VISION §1.2), and the filled shape says so without the colour (§3.4).
 * Connecting is a ring that turns around the resting icon.
 */
@Composable
internal fun CastIcon(
    state: CastButtonState,
    modifier: Modifier = Modifier,
    restingTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    connectedTint: Color = MaterialTheme.colorScheme.primary,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (state == CastButtonState.CONNECTING) {
            CircularProgressIndicator(
                modifier = Modifier.size(36.dp),
                color = connectedTint,
                strokeWidth = 2.dp,
            )
        }
        Icon(
            painter =
                painterResource(
                    if (state == CastButtonState.CONNECTED) CoreR.drawable.ic_cast_connected
                    else CoreR.drawable.ic_cast
                ),
            contentDescription = null,
            tint =
                if (state == CastButtonState.CONNECTED) connectedTint else restingTint,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CastIconPreview() {
    FindroidTheme {
        Row {
            CastIcon(CastButtonState.IDLE, Modifier.size(48.dp))
            CastIcon(CastButtonState.CONNECTING, Modifier.size(48.dp))
            CastIcon(CastButtonState.CONNECTED, Modifier.size(48.dp))
        }
    }
}
