package dev.jdtech.jellyfin.presentation.cast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.player.cast.models.CastAvailability
import dev.jdtech.jellyfin.player.cast.models.CastConnectionState
import dev.jdtech.jellyfin.player.cast.models.Device
import dev.jdtech.jellyfin.player.cast.presentation.CastDiscovery
import dev.jdtech.jellyfin.player.cast.presentation.CastUiState
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme

const val CAST_DEVICE_SHEET_TAG = "cast_device_sheet"

/**
 * CE (#626): where a receiver is picked, and where the session is ended. It is the app's usual
 * sheet, like the profile and server sheets, and says which of its states it is in: why Cast cannot
 * be used, searching, the devices found, connecting, connected, or nothing found.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastDeviceSheet(
    state: CastUiState,
    isOfflineMode: Boolean,
    onConnect: (Device) -> Unit,
    onDisconnect: () -> Unit,
    onSearchAgain: () -> Unit,
    onOpenPlayer: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        CastDeviceSheetContent(
            state = state,
            isOfflineMode = isOfflineMode,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            onSearchAgain = onSearchAgain,
            onOpenPlayer = onOpenPlayer,
            modifier = Modifier.testTag(CAST_DEVICE_SHEET_TAG),
        )
    }
}

@Composable
internal fun CastDeviceSheetContent(
    state: CastUiState,
    isOfflineMode: Boolean,
    onConnect: (Device) -> Unit,
    onDisconnect: () -> Unit,
    onSearchAgain: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val device = state.device
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = MaterialTheme.spacings.default)
    ) {
        when {
            state.availability != CastAvailability.AVAILABLE || isOfflineMode ->
                Unavailable(state.availability, isOfflineMode)
            device != null && (state.isConnected || state.isReconnecting) ->
                Connected(state, device, onDisconnect, onOpenPlayer)
            else -> Devices(state, onConnect, onSearchAgain)
        }
    }
}

@Composable
private fun SheetTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier =
            modifier.padding(horizontal = MaterialTheme.spacings.default).semantics { heading() },
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleLarge,
    )
}

@Composable
private fun SheetBody(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = MaterialTheme.spacings.default),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun Unavailable(availability: CastAvailability, isOfflineMode: Boolean) {
    SheetTitle(stringResource(CoreR.string.ce_cast_unavailable_title))
    Spacer(Modifier.height(MaterialTheme.spacings.small))
    SheetBody(
        stringResource(
            when {
                availability == CastAvailability.INSECURE_SERVER ->
                    CoreR.string.ce_cast_unavailable_http
                availability == CastAvailability.NO_PLAY_SERVICES ->
                    CoreR.string.ce_cast_unavailable_play_services
                isOfflineMode -> CoreR.string.ce_cast_unavailable_offline
                else -> CoreR.string.ce_cast_unavailable_play_services
            }
        )
    )
}

@Composable
private fun Devices(state: CastUiState, onConnect: (Device) -> Unit, onSearchAgain: () -> Unit) {
    val connecting = state.connectionState == CastConnectionState.CONNECTING
    val searching = state.discovery == CastDiscovery.SEARCHING

    Row(
        modifier = Modifier.fillMaxWidth().padding(end = MaterialTheme.spacings.default),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SheetTitle(stringResource(CoreR.string.ce_cast_sheet_title), Modifier.weight(1f))
        // Devices are listed while the search goes on: the ring says the list may still grow.
        if (searching && state.devices.isNotEmpty()) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
    Spacer(Modifier.height(MaterialTheme.spacings.small))

    state.connectFailedDevice?.let { name ->
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(
                        horizontal = MaterialTheme.spacings.default,
                        vertical = MaterialTheme.spacings.small,
                    )
                    .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_alert_circle),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(CoreR.string.ce_cast_connect_failed, name),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    when {
        state.devices.isNotEmpty() -> {
            state.devices.forEach { device ->
                val isTarget = connecting && device.id == state.device?.id
                DeviceRow(
                    device = device,
                    connecting = isTarget,
                    enabled = device.enabled && !connecting,
                    onClick = { onConnect(device) },
                )
            }
            Spacer(Modifier.height(MaterialTheme.spacings.small))
            SheetBody(stringResource(CoreR.string.ce_cast_wifi_hint))
        }
        searching || state.discovery == CastDiscovery.IDLE -> {
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(horizontal = MaterialTheme.spacings.default),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(CoreR.string.ce_cast_searching),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            SheetBody(stringResource(CoreR.string.ce_cast_wifi_hint))
        }
        else -> {
            Text(
                text = stringResource(CoreR.string.ce_cast_none_found),
                modifier =
                    Modifier.padding(horizontal = MaterialTheme.spacings.default).semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(MaterialTheme.spacings.extraSmall))
            SheetBody(stringResource(CoreR.string.ce_cast_wifi_hint))
            Spacer(Modifier.height(MaterialTheme.spacings.medium))
            FilledTonalButton(
                onClick = onSearchAgain,
                modifier = Modifier.padding(horizontal = MaterialTheme.spacings.default),
            ) {
                Text(stringResource(CoreR.string.ce_cast_search_again))
            }
        }
    }
}

@Composable
private fun DeviceRow(device: Device, connecting: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(
                    horizontal = MaterialTheme.spacings.default,
                    vertical = MaterialTheme.spacings.small,
                )
                // The row that is connecting keeps its full strength; the others step back.
                .alpha(if (enabled || connecting) 1f else 0.38f),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(CoreR.drawable.ic_tv),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (connecting) {
                Text(
                    text = stringResource(CoreR.string.ce_cast_connecting),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (connecting) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun Connected(
    state: CastUiState,
    device: Device,
    onDisconnect: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacings.default),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            CastIcon(
                state =
                    if (state.isReconnecting) CastButtonState.CONNECTING
                    else CastButtonState.CONNECTED
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name,
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    stringResource(
                        if (state.isReconnecting) CoreR.string.ce_cast_reconnecting
                        else CoreR.string.ce_cast_connected
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
    Spacer(Modifier.height(MaterialTheme.spacings.medium))

    val playing = state.nowPlaying
    if (playing != null) {
        Surface(
            onClick = onOpenPlayer,
            modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacings.medium),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.heightIn(min = 64.dp).padding(MaterialTheme.spacings.small),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CastThumbnail(playing, height = 48.dp)
                CastTitleLines(playing, modifier = Modifier.weight(1f))
            }
        }
    } else {
        SheetBody(stringResource(CoreR.string.ce_cast_nothing_playing_hint, device.name))
    }

    Spacer(Modifier.height(MaterialTheme.spacings.default))
    FilledTonalButton(
        onClick = onDisconnect,
        modifier = Modifier.padding(horizontal = MaterialTheme.spacings.default),
    ) {
        Text(stringResource(CoreR.string.ce_cast_disconnect))
    }
}

internal class CastStateProvider : PreviewParameterProvider<CastUiState> {
    override val values = CastPreviews.sheetStates.asSequence()
}

@Preview(showBackground = true)
@Composable
private fun CastDeviceSheetPreview(@PreviewParameter(CastStateProvider::class) state: CastUiState) {
    FindroidTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            CastDeviceSheetContent(
                state = state,
                isOfflineMode = false,
                onConnect = {},
                onDisconnect = {},
                onSearchAgain = {},
                onOpenPlayer = {},
            )
        }
    }
}
