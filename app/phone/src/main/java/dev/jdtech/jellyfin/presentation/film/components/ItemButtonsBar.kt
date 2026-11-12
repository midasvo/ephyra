package dev.jdtech.jellyfin.presentation.film.components

import android.os.Environment
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import dev.jdtech.jellyfin.core.Constants
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderState
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.utils.download.DownloadStatus
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.FindroidTrailer
import dev.jdtech.jellyfin.models.getVersions
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.utils.LocalOfflineMode
import dev.jdtech.jellyfin.presentation.theme.spacings

@Composable
fun ItemButtonsBar(
    item: FindroidItem,
    onPlayClick: (startFromBeginning: Boolean) -> Unit,
    onMarkAsPlayedClick: () -> Unit,
    onMarkAsFavoriteClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onDownloadCancelClick: () -> Unit,
    onDownloadDeleteClick: () -> Unit,
    onTrailerClick: (uri: String) -> Unit,
    modifier: Modifier = Modifier,
    downloaderState: DownloaderState? = null,
    downloaderStateProvider: (() -> DownloaderState)? = null,
    canPlay: Boolean = true,
    canDownload: Boolean = item.canDownload,
    isDownloaded: Boolean = item.isDownloaded(),
    onPlayVersionClick: ((startFromBeginning: Boolean, mediaSourceId: String) -> Unit)? = null,
    hasMoreToDownload: Boolean = false,
) {
    // Read inside this composable so a progress tick only recomposes the buttons bar, not the
    // screen that hosts it.
    val currentDownloaderState = downloaderStateProvider?.invoke() ?: downloaderState
    val context = LocalContext.current
    val isOfflineMode = LocalOfflineMode.current
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass

    // CE (#332): every remote trailer; a menu picks one when there are several.
    val trailers =
        remember(item) {
            val all =
                when (item) {
                    is FindroidMovie -> item.trailers
                    is FindroidShow -> item.trailers
                    else -> emptyList()
                }
            val first =
                when (item) {
                    is FindroidMovie -> item.trailer
                    is FindroidShow -> item.trailer
                    else -> null
                }
            all.ifEmpty { listOfNotNull(first?.let { FindroidTrailer(name = null, url = it) }) }
        }
    var trailerMenuOpen by remember { mutableStateOf(false) }

    var cancelDownloadDialogOpen by remember { mutableStateOf(false) }
    var deleteDownloadDialogOpen by remember { mutableStateOf(false) }

    val versions = remember(item.sources) { item.getVersions() }
    var versionDialogStartFromBeginning by remember { mutableStateOf<Boolean?>(null) }
    val onPlay = { startFromBeginning: Boolean ->
        if (onPlayVersionClick != null && versions.size > 1) {
            versionDialogStartFromBeginning = startFromBeginning
        } else {
            onPlayClick(startFromBeginning)
        }
    }

    val storageLocations = remember { context.getExternalFilesDirs(null) }

    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        ) {
            if (
                !windowSizeClass.isWidthAtLeastBreakpoint(
                    WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND
                )
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
                    PlayButton(
                        item = item,
                        onClick = { onPlay(false) },
                        modifier = Modifier.weight(weight = 1f, fill = true),
                        enabled = item.canPlay && canPlay,
                    )
                    if (item.playbackPositionTicks.div(Constants.TICKS_PER_MINUTE) > 0) {
                        FilledTonalIconButton(onClick = { onPlay(true) }) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_rotate_ccw),
                                contentDescription = stringResource(CoreR.string.play_from_beginning),
                            )
                        }
                    }
                }
            }
            // Wraps instead of clipping when the buttons don't fit beside each other (#429).
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
            ) {
                if (
                    windowSizeClass.isWidthAtLeastBreakpoint(
                        WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND
                    )
                ) {
                    PlayButton(
                        item = item,
                        onClick = { onPlay(false) },
                        enabled = item.canPlay && canPlay,
                    )
                    if (item.playbackPositionTicks.div(Constants.TICKS_PER_MINUTE) > 0) {
                        FilledTonalIconButton(onClick = { onPlay(true) }) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_rotate_ccw),
                                contentDescription = stringResource(CoreR.string.play_from_beginning),
                            )
                        }
                    }
                }
                if (trailers.isNotEmpty() && !isOfflineMode) {
                    Box {
                        FilledTonalIconButton(
                            onClick = {
                                if (trailers.size == 1) onTrailerClick(trailers.first().url)
                                else trailerMenuOpen = true
                            }
                        ) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_film),
                                contentDescription = stringResource(CoreR.string.trailer_button_description),
                            )
                        }
                        DropdownMenu(
                            expanded = trailerMenuOpen,
                            onDismissRequest = { trailerMenuOpen = false },
                        ) {
                            trailers.forEachIndexed { index, trailer ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text =
                                                trailer.name
                                                    ?: stringResource(
                                                        CoreR.string.item_trailer_number,
                                                        index + 1,
                                                    )
                                        )
                                    },
                                    onClick = {
                                        trailerMenuOpen = false
                                        onTrailerClick(trailer.url)
                                    },
                                )
                            }
                        }
                    }
                }
                // CE (#397): watched fills the button, so the state reads without telling two
                // tints apart; TalkBack hears it as checked or not.
                FilledTonalIconToggleButton(
                    checked = item.played,
                    onCheckedChange = { onMarkAsPlayedClick() },
                    colors =
                        IconButtonDefaults.filledTonalIconToggleButtonColors(
                            checkedContainerColor = MaterialTheme.colorScheme.tertiary,
                            checkedContentColor = MaterialTheme.colorScheme.onTertiary,
                        ),
                ) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_check),
                        contentDescription = stringResource(CoreR.string.check_button_description),
                    )
                }
                FilledTonalIconButton(onClick = onMarkAsFavoriteClick) {
                    when (item.favorite) {
                        true -> {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_heart_filled),
                                contentDescription = stringResource(CoreR.string.favorite_button_description),
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        false -> {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_heart),
                                contentDescription = stringResource(CoreR.string.favorite_button_description),
                            )
                        }
                    }
                }
                if (currentDownloaderState != null && !currentDownloaderState.isDownloading) {
                    if (canDownload && !isOfflineMode && (!isDownloaded || hasMoreToDownload)) {
                        FilledTonalIconButton(onClick = onDownloadClick) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_download),
                                contentDescription = stringResource(CoreR.string.download_button_description),
                            )
                        }
                    }
                    if (isDownloaded) {
                        FilledTonalIconButton(onClick = { deleteDownloadDialogOpen = true }) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_trash),
                                contentDescription = stringResource(CoreR.string.delete_download),
                            )
                        }
                    }
                }
            }
            if (currentDownloaderState != null) {
                AnimatedVisibility(currentDownloaderState.isDownloading) {
                    Column {
                        DownloaderCard(
                            state = currentDownloaderState,
                            onCancelClick = { cancelDownloadDialogOpen = true },
                            onRetryClick = onDownloadClick,
                        )
                        Spacer(Modifier.height(MaterialTheme.spacings.small))
                    }
                }
            }
            if (currentDownloaderState != null && !currentDownloaderState.isDownloading && isDownloaded) {
                val localSource =
                    item.sources.firstOrNull {
                        it.type == FindroidSourceType.LOCAL && !it.path.endsWith(".download")
                    }
                if (localSource != null) {
                    val label =
                        remember(localSource.path) {
                            val dir =
                                storageLocations.firstOrNull { dir ->
                                    dir != null && localSource.path.startsWith(dir.path)
                                }
                            val isExternal =
                                dir != null && Environment.isExternalStorageRemovable(dir)
                            context.getString(
                                CoreR.string.downloaded_to,
                                if (isExternal) context.getString(CoreR.string.external)
                                else context.getString(CoreR.string.internal),
                            )
                        }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (cancelDownloadDialogOpen) {
            CancelDownloadDialog(
                onCancel = {
                    onDownloadCancelClick()
                    cancelDownloadDialogOpen = false
                },
                onDismiss = { cancelDownloadDialogOpen = false },
            )
        }
        if (deleteDownloadDialogOpen) {
            DeleteDownloadDialog(
                onDelete = {
                    onDownloadDeleteClick()
                    deleteDownloadDialogOpen = false
                },
                onDismiss = { deleteDownloadDialogOpen = false },
            )
        }
        versionDialogStartFromBeginning?.let { startFromBeginning ->
            VersionDialog(
                versions = versions,
                onSelect = { mediaSourceId ->
                    versionDialogStartFromBeginning = null
                    onPlayVersionClick?.invoke(startFromBeginning, mediaSourceId)
                },
                onDismiss = { versionDialogStartFromBeginning = null },
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ItemButtonsBarPreview() {
    FindroidTheme {
        ItemButtonsBar(
            item = dummyEpisode,
            onPlayClick = {},
            onMarkAsPlayedClick = {},
            onMarkAsFavoriteClick = {},
            onDownloadClick = {},
            onDownloadCancelClick = {},
            onDownloadDeleteClick = {},
            onTrailerClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ItemButtonsBarDownloadingPreview() {
    FindroidTheme {
        ItemButtonsBar(
            item = dummyEpisode,
            downloaderState =
                DownloaderState(status = DownloadStatus.RUNNING, progress = 0.3f),
            onPlayClick = {},
            onMarkAsPlayedClick = {},
            onMarkAsFavoriteClick = {},
            onDownloadClick = {},
            onDownloadCancelClick = {},
            onDownloadDeleteClick = {},
            onTrailerClick = {},
        )
    }
}
