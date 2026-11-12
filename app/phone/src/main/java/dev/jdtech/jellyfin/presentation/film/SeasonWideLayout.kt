package dev.jdtech.jellyfin.presentation.film

import android.content.Intent
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.PlayerActivity
import dev.jdtech.jellyfin.core.Constants
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadProgress
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadStatus
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderEvent
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderState
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderViewModel
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisodes
import dev.jdtech.jellyfin.core.presentation.dummy.dummySeason
import dev.jdtech.jellyfin.film.domain.withMediaInfo
import dev.jdtech.jellyfin.film.presentation.episode.EpisodeAction
import dev.jdtech.jellyfin.film.presentation.episode.EpisodeViewModel
import dev.jdtech.jellyfin.film.presentation.season.SeasonAction
import dev.jdtech.jellyfin.film.presentation.season.SeasonState
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidSeason
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.presentation.film.components.ArtworkPlacement
import dev.jdtech.jellyfin.presentation.film.components.ArtworkState
import dev.jdtech.jellyfin.presentation.film.components.MediaInfoSheet
import dev.jdtech.jellyfin.presentation.film.components.Direction
import dev.jdtech.jellyfin.presentation.film.components.DownloadQualityDialog
import dev.jdtech.jellyfin.presentation.film.components.DownloadRow
import dev.jdtech.jellyfin.presentation.film.components.EpisodeDownloadControl
import dev.jdtech.jellyfin.presentation.film.components.episodeRowDownloadClick
import dev.jdtech.jellyfin.presentation.film.components.ItemButtonsBar
import dev.jdtech.jellyfin.presentation.film.components.ItemHeader
import dev.jdtech.jellyfin.presentation.film.components.ItemPoster
import dev.jdtech.jellyfin.presentation.film.components.ItemTopBar
import dev.jdtech.jellyfin.presentation.film.components.LoadingOrError
import dev.jdtech.jellyfin.presentation.film.components.groupedItemShape
import dev.jdtech.jellyfin.presentation.film.components.playDownloadedItem
import dev.jdtech.jellyfin.presentation.film.components.rememberArtworkState
import dev.jdtech.jellyfin.presentation.film.components.rememberTopBarBackgroundAlpha
import dev.jdtech.jellyfin.presentation.film.components.rememberUndoController
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.LightStatusBarOverArtwork
import dev.jdtech.jellyfin.presentation.utils.LocalOfflineMode
import dev.jdtech.jellyfin.presentation.utils.ReloadOnResume
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding
import dev.jdtech.jellyfin.utils.ObserveAsEvents
import dev.jdtech.jellyfin.utils.copyOnLongClick
import java.util.UUID
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.model.api.BaseItemKind

/** The list pane takes 40 % of the window, at least 360 dp and at most 480 dp (4-design.md §7). */
private fun listPaneWidth(windowWidth: Dp): Dp = (windowWidth * 0.4f).coerceIn(360.dp, 480.dp)

/**
 * The episode the list-detail selects when the season opens, so the detail pane is never empty:
 * the first one not watched yet (the one the user most likely plays next), else the first.
 * Missing episodes (known to the server, no file) are only picked when there is nothing else.
 */
internal fun defaultSelectedEpisode(episodes: List<FindroidEpisode>): FindroidEpisode? =
    episodes.firstOrNull { !it.played && !it.missing }
        ?: episodes.firstOrNull { !it.missing }
        ?: episodes.firstOrNull()

/**
 * The episode list position of the Season page, shared by the single-column page and the
 * list-detail (4-design.md §13, criterion 2). Both lists hold the same items in the same order
 * (the header, the download summary when shown, then the episodes), so a rotation that switches
 * between them keeps the same episode at the top.
 *
 * A rotation recreates the activity, and the list's own saved position is not enough (#435):
 * - The list is first measured before the window insets arrive, with a bottom padding short by
 *   the navigation bar, so a list scrolled near its end is clamped back by that much.
 * - The other orientation's list may be shorter, so it clamps the position too, and rotating
 *   back would start from the clamped one.
 *
 * So the position the user left the list at is saved apart from the list, and changes only when
 * the list moves after a restore has settled. Once per restore it is applied again over the next
 * frames until it holds; that stops as soon as anything else moves the list (the user's scroll,
 * or the list-detail scrolling the selected episode into view), and never runs on an ordinary
 * recomposition. A position the list can no longer reach, because the season has fewer episodes
 * now, is given up, and the list's own position is saved from then on.
 */
@Composable
internal fun rememberSeasonListState(): LazyListState {
    val state = rememberLazyListState()
    var savedIndex by rememberSaveable { mutableIntStateOf(0) }
    var savedOffset by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(state) {
        val settled = restorePosition(state, target = savedIndex to savedOffset)
        // From here on, where the list is now is where the user left it. Only a list still
        // clamped short of the saved position keeps that position until it moves.
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .drop(if (settled) 0 else 1)
            .collect { (index, offset) ->
                savedIndex = index
                savedOffset = offset
            }
    }
    return state
}

/** Frames to wait for the window insets after a restore: about half a second. */
private const val RESTORE_FRAMES = 30

/** How long to wait for the list to hold the saved item before giving the position up. */
private const val RESTORE_ITEMS_TIMEOUT_MS = 2_000L

/**
 * Scrolls [state] to [target] once its items are laid out, and again on each of the next frames
 * while the list is still short of it, unless something else moved the list in between.
 *
 * Gives up without scrolling when the list does not hold the target item within about two
 * seconds: the season may have fewer episodes than when the position was saved (offline, a
 * removed download, a library change), and the list stays where it is.
 *
 * Returns whether the list's position is now the one to keep: false only when the list is still
 * short of a target it holds, clamped by its layout (the other orientation's shorter list).
 */
internal suspend fun restorePosition(state: LazyListState, target: Pair<Int, Int>): Boolean {
    if (target == 0 to 0) return true
    fun position() = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
    // The first layout with the target item in it.
    withTimeoutOrNull(RESTORE_ITEMS_TIMEOUT_MS) {
        snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > target.first }
    } ?: return true
    var last = position()
    repeat(RESTORE_FRAMES) { frame ->
        if (last == target) return true
        if (frame > 0) {
            withFrameNanos {}
            // Moved by someone else since: leave it there.
            if (state.isScrollInProgress || position() != last) return true
        }
        state.scrollToItem(target.first, target.second)
        last = position()
    }
    return last == target
}

/**
 * The Season page at Expanded width (docs/tablet/4-design.md §7): the season header and its
 * episodes on the left, the selected episode's page on the right. Selecting an episode changes
 * the right pane; it does not navigate.
 *
 * [selectedEpisodeId], [onSelectEpisode] and [lazyListState] are owned by `SeasonScreen`, above
 * its width branch, so the selection and the list position survive a rotation to the
 * single-column page and back (see [rememberSeasonListState]). The detail pane is
 * driven by an `EpisodeViewModel` per selected episode (see [SeasonEpisodeDetailPane]).
 */
@Composable
fun SeasonListDetail(
    state: SeasonState,
    selectedEpisodeId: UUID?,
    onSelectEpisode: (UUID) -> Unit,
    lazyListState: LazyListState,
    onAction: (SeasonAction) -> Unit,
    onDownloadSeason: (maxBitrate: Int?) -> Unit,
    onDeleteSeasonDownloads: () -> Unit,
    onReloadSeason: () -> Unit,
    navigateToPerson: (personId: UUID) -> Unit,
) {
    // The selection, once the episodes are known: the one asked for if the season has it, else
    // the default. Written back, so marking the default watched doesn't move the selection on.
    val selected =
        remember(selectedEpisodeId, state.episodes) {
            selectedEpisodeId?.takeIf { id -> state.episodes.any { it.id == id } }
                ?: defaultSelectedEpisode(state.episodes)?.id
        }
    LaunchedEffect(selected) {
        if (selected != null && selected != selectedEpisodeId) onSelectEpisode(selected)
    }
    // CE (#437): whether the detail pane's artwork is under the status bar, for its icons.
    var detailOverArtwork by remember { mutableStateOf(false) }

    SeasonWideLayout(
        state = state,
        selectedEpisodeId = selected,
        onSelectEpisode = onSelectEpisode,
        lazyListState = lazyListState,
        onAction = onAction,
        onDownloadSeason = onDownloadSeason,
        onDeleteSeasonDownloads = onDeleteSeasonDownloads,
        detailOverArtwork = { detailOverArtwork },
        detailPane = { episodeId ->
            SeasonEpisodeDetailPane(
                episodeId = episodeId,
                navigateToPerson = navigateToPerson,
                onOverArtworkChange = { detailOverArtwork = it },
                onEpisodeChanged = { episode ->
                    // Watched and favourite change in the detail pane only; the list shows the
                    // watched tick and the season its own state, so they are loaded again.
                    val listed = state.episodes.firstOrNull { it.id == episode.id }
                    if (
                        listed != null &&
                            (listed.played != episode.played || listed.favorite != episode.favorite)
                    ) {
                        onReloadSeason()
                    }
                },
                onDownloadChanged = onReloadSeason,
            )
        },
    )
}

/**
 * Stateless layout of the list-detail. [detailPane] draws the page of the episode with the given
 * id; it is keyed on the id, so each episode gets its own state.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun SeasonWideLayout(
    state: SeasonState,
    selectedEpisodeId: UUID?,
    onSelectEpisode: (UUID) -> Unit,
    onAction: (SeasonAction) -> Unit,
    onDownloadSeason: (maxBitrate: Int?) -> Unit,
    onDeleteSeasonDownloads: () -> Unit,
    detailPane: @Composable (episodeId: UUID) -> Unit,
    lazyListState: LazyListState = rememberLazyListState(),
    detailOverArtwork: () -> Boolean = { false },
) {
    val context = LocalContext.current
    val safePadding = rememberSafePadding()

    val paddingStart = safePadding.start + MaterialTheme.spacings.default
    // The list pane ends at the divider, not at the window edge, so no end inset.
    val paddingEnd = MaterialTheme.spacings.default
    val paddingBottom = safePadding.bottom + MaterialTheme.spacings.default

    val topBarBackgroundAlpha = rememberTopBarBackgroundAlpha(lazyListState)
    // CE (#437): the artwork under the top bar, if any; shared by the header and the bar.
    val artwork = rememberArtworkState(state.season, ArtworkPlacement.Header)

    var seasonQualityDialogOpen by remember { mutableStateOf(false) }
    var episodeQualityTarget by remember { mutableStateOf<FindroidEpisode?>(null) }

    // Downloads removed from the list wait behind an Undo snackbar, as on the single-column page.
    val snackbarHostState = remember { SnackbarHostState() }
    val undo = rememberUndoController(snackbarHostState)
    LaunchedEffect(state.episodeDownloadProgress) {
        undo.retainHidden(
            state.episodeDownloadProgress.filterValues { it.status != DownloadStatus.NONE }.keys
        )
    }
    val hiddenIds = undo.hiddenIds
    val episodeProgress =
        remember(state.episodeDownloadProgress, hiddenIds) {
            state.episodeDownloadProgress - hiddenIds
        }
    val offlineMode = LocalOfflineMode.current
    val episodes =
        remember(state.episodes, hiddenIds, offlineMode) {
            if (offlineMode) state.episodes.filterNot { it.id in hiddenIds } else state.episodes
        }
    val hasDownloads =
        remember(episodes, hiddenIds) { episodes.any { it.isDownloaded() && it.id !in hiddenIds } }
    val anyCanDownload = remember(episodes) { episodes.any { it.canDownload } }
    fun removeDownload(episode: FindroidEpisode, @StringRes message: Int) {
        undo.run(ids = setOf(episode.id), message = context.getString(message, episode.name)) {
            onAction(SeasonAction.DeleteEpisodeDownload(episode))
        }
    }

    // An episode opened from elsewhere (Next Up, search) may be far down the list: show its row
    // once, without moving the list again after the user scrolled. A list that is not at the top
    // was scrolled by the user, here or before a rotation (#435), and keeps its position.
    var revealedSelection by rememberSaveable { mutableStateOf(false) }
    val downloadSummaryShown =
        state.episodeDownloadProgress.values.any {
            it.status == DownloadStatus.COMPLETED ||
                it.status == DownloadStatus.DOWNLOADING ||
                it.status == DownloadStatus.PENDING ||
                it.status == DownloadStatus.QUEUED ||
                it.status == DownloadStatus.PAUSED
        }
    LaunchedEffect(episodes.isNotEmpty(), selectedEpisodeId) {
        if (revealedSelection || episodes.isEmpty() || selectedEpisodeId == null) return@LaunchedEffect
        revealedSelection = true
        val scrolled =
            lazyListState.firstVisibleItemIndex > 0 ||
                lazyListState.firstVisibleItemScrollOffset > 0
        if (scrolled) return@LaunchedEffect
        val index = episodes.indexOfFirst { it.id == selectedEpisodeId }
        if (index < 0) return@LaunchedEffect
        val itemIndex = index + 1 + if (downloadSummaryShown) 1 else 0
        if (lazyListState.layoutInfo.visibleItemsInfo.none { it.index == itemIndex }) {
            lazyListState.scrollToItem(itemIndex)
        }
    }

    val navigator =
        rememberListDetailPaneScaffoldNavigator<UUID>(
            // Both panes, side by side with the divider instead of a gap.
            scaffoldDirective =
                calculatePaneScaffoldDirective(currentWindowAdaptiveInfo())
                    .copy(maxHorizontalPartitions = 2, horizontalPartitionSpacerSize = 0.dp)
        )
    LaunchedEffect(selectedEpisodeId) {
        selectedEpisodeId?.let { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, it) }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val listWidth = listPaneWidth(maxWidth)

        state.season?.let { season ->
            ListDetailPaneScaffold(
                directive = navigator.scaffoldDirective,
                value = navigator.scaffoldValue,
                listPane = {
                    AnimatedPane(modifier = Modifier.preferredWidth(listWidth)) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = lazyListState,
                            contentPadding = PaddingValues(bottom = paddingBottom),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            item {
                                SeasonListHeader(
                                    season = season,
                                    artwork = artwork,
                                    lazyListState = lazyListState,
                                    paddingStart = paddingStart,
                                    paddingEnd = paddingEnd,
                                )
                                Spacer(Modifier.height(MaterialTheme.spacings.default.div(2)))
                                ItemButtonsBar(
                                    item = season,
                                    downloaderState =
                                        if (episodes.isNotEmpty()) DownloaderState() else null,
                                    onPlayClick = { startFromBeginning ->
                                        onAction(
                                            SeasonAction.Play(startFromBeginning = startFromBeginning)
                                        )
                                    },
                                    onMarkAsPlayedClick = {
                                        when (season.played) {
                                            true -> onAction(SeasonAction.UnmarkAsPlayed)
                                            false -> onAction(SeasonAction.MarkAsPlayed)
                                        }
                                    },
                                    onMarkAsFavoriteClick = {
                                        when (season.favorite) {
                                            true -> onAction(SeasonAction.UnmarkAsFavorite)
                                            false -> onAction(SeasonAction.MarkAsFavorite)
                                        }
                                    },
                                    onTrailerClick = {},
                                    onDownloadClick = { seasonQualityDialogOpen = true },
                                    onDownloadCancelClick = {},
                                    onDownloadDeleteClick = onDeleteSeasonDownloads,
                                    modifier =
                                        Modifier.padding(start = paddingStart, end = paddingEnd)
                                            .fillMaxWidth(),
                                    canPlay = episodes.isNotEmpty(),
                                    canDownload = anyCanDownload,
                                    isDownloaded = hasDownloads,
                                    hasMoreToDownload =
                                        episodes.any {
                                            it.canDownload &&
                                                (episodeProgress[it.id]?.status
                                                    ?: DownloadStatus.NONE) == DownloadStatus.NONE
                                        },
                                )
                                Spacer(Modifier.height(MaterialTheme.spacings.default))
                            }
                            if (downloadSummaryShown) {
                                item {
                                    DownloadSummary(
                                        state = state,
                                        totalCount = episodes.size,
                                        modifier =
                                            Modifier.padding(
                                                start = paddingStart,
                                                end = paddingEnd,
                                                bottom = MaterialTheme.spacings.default,
                                            ),
                                    )
                                }
                            }
                            itemsIndexed(items = episodes, key = { _, episode -> episode.id }) {
                                index,
                                episode ->
                                CompactEpisodeRow(
                                    episode = episode,
                                    selected = episode.id == selectedEpisodeId,
                                    shape = groupedItemShape(index, episodes.size),
                                    onClick = { onSelectEpisode(episode.id) },
                                    modifier =
                                        Modifier.padding(start = paddingStart, end = paddingEnd),
                                    downloadProgress = episodeProgress[episode.id],
                                    onPlayClick =
                                        if (episode.canPlay) {
                                            { context.playDownloadedItem(episode) }
                                        } else null,
                                    onDownloadClick =
                                        episodeRowDownloadClick(
                                            episode = episode,
                                            removalPending = episode.id in undo.hiddenIds,
                                        ) {
                                            episodeQualityTarget = episode
                                        },
                                    onDownloadCancelClick = {
                                        removeDownload(episode, CoreR.string.download_cancelled)
                                    },
                                    onDownloadRetryClick = {
                                        onAction(SeasonAction.RetryEpisodeDownload(episode))
                                    },
                                    onDownloadRemoveClick = {
                                        removeDownload(episode, CoreR.string.download_removed)
                                    },
                                )
                            }
                        }
                    }
                },
                detailPane = {
                    AnimatedPane {
                        Row(modifier = Modifier.fillMaxSize()) {
                            VerticalDivider(
                                thickness = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                selectedEpisodeId?.let { id -> key(id) { detailPane(id) } }
                            }
                        }
                    }
                },
            )
        }
            ?: run {
                LoadingOrError(
                    error = state.error,
                    onRetryClick = { onAction(SeasonAction.OnRetryClick) },
                )
            }

        // The status bar spans both panes and has one icon colour: light icons only while both
        // panes show artwork under it.
        LightStatusBarOverArtwork(
            hasArtwork = artwork.hasArtwork && detailOverArtwork(),
            topBarBackgroundAlpha = topBarBackgroundAlpha,
        )
        // One top bar for the page, over the list pane: the detail pane has no back or home.
        Box(modifier = Modifier.width(listWidth)) {
            ItemTopBar(
                hasBackButton = true,
                hasHomeButton = true,
                backgroundAlpha = topBarBackgroundAlpha,
                overArtwork = artwork.hasArtwork,
                onBackClick = { onAction(SeasonAction.OnBackClick) },
                onHomeClick = { onAction(SeasonAction.OnHomeClick) },
            ) {
                Spacer(modifier = Modifier.width(MaterialTheme.spacings.extraSmall))
                state.season?.let { season ->
                    Button(
                        onClick = { onAction(SeasonAction.NavigateToSeries(season.seriesId)) },
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = Color.Black.copy(alpha = 0.5f),
                                contentColor = Color.White,
                            ),
                    ) {
                        Text(
                            text = season.seriesName,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        if (seasonQualityDialogOpen) {
            DownloadQualityDialog(
                onSelect = { maxBitrate ->
                    onDownloadSeason(maxBitrate)
                    seasonQualityDialogOpen = false
                },
                onDismiss = { seasonQualityDialogOpen = false },
            )
        }
        episodeQualityTarget?.let { episode ->
            DownloadQualityDialog(
                onSelect = { maxBitrate ->
                    onAction(SeasonAction.DownloadEpisode(episode, maxBitrate))
                    episodeQualityTarget = null
                },
                onDismiss = { episodeQualityTarget = null },
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier.align(Alignment.BottomStart)
                    .width(listWidth)
                    .padding(bottom = safePadding.bottom),
        )
    }
}

/** The season's backdrop header with its poster and names, as on the single-column page. */
@Composable
private fun SeasonListHeader(
    season: FindroidSeason,
    artwork: ArtworkState,
    lazyListState: LazyListState,
    paddingStart: Dp,
    paddingEnd: Dp,
) {
    ItemHeader(
        item = season,
        lazyListState = lazyListState,
        artwork = artwork,
        content = {
            Row(
                modifier =
                    Modifier.align(Alignment.BottomStart)
                        .padding(start = paddingStart, end = paddingEnd),
                verticalAlignment = Alignment.Bottom,
            ) {
                ItemPoster(
                    item = season,
                    direction = Direction.VERTICAL,
                    modifier = Modifier.width(120.dp).clip(MaterialTheme.shapes.small),
                )
                Spacer(Modifier.width(MaterialTheme.spacings.medium))
                Column {
                    Text(
                        text = season.seriesName,
                        modifier = Modifier.copyOnLongClick(season.seriesName),
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = season.name,
                        modifier = Modifier.copyOnLongClick(season.name),
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 3,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
            }
        },
    )
}

/** "3 of 10 downloaded", counting episodes behind an Undo snackbar so the list doesn't jump. */
@Composable
private fun DownloadSummary(state: SeasonState, totalCount: Int, modifier: Modifier) {
    val downloadedCount =
        state.episodeDownloadProgress.values.count { it.status == DownloadStatus.COMPLETED }
    val downloadingCount =
        state.episodeDownloadProgress.values.count {
            it.status == DownloadStatus.DOWNLOADING ||
                it.status == DownloadStatus.PENDING ||
                it.status == DownloadStatus.QUEUED ||
                it.status == DownloadStatus.PAUSED
        }
    val summaryText =
        if (downloadingCount > 0) {
            stringResource(
                CoreR.string.download_summary_downloading,
                downloadedCount + downloadingCount,
                totalCount,
            )
        } else {
            stringResource(CoreR.string.download_summary_downloaded, downloadedCount, totalCount)
        }
    Text(
        text = summaryText,
        modifier = modifier.alpha(0.7f),
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * An episode in the list pane: still, title, `E2 · 38 mins`, watched tick and download state,
 * without the overview the detail pane shows. The selected row is drawn in `secondaryContainer`.
 * Also the row of the player's episode sheet (R10), which has no download control.
 */
@Composable
internal fun CompactEpisodeRow(
    episode: FindroidEpisode,
    selected: Boolean,
    shape: Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    downloadProgress: DownloadProgress? = null,
    onPlayClick: (() -> Unit)? = null,
    onDownloadClick: (() -> Unit)? = null,
    onDownloadCancelClick: (() -> Unit)? = null,
    onDownloadRetryClick: (() -> Unit)? = null,
    onDownloadRemoveClick: (() -> Unit)? = null,
    showDownloadControl: Boolean = true,
) {
    val meta =
        buildList {
                add(stringResource(CoreR.string.episode_number_short, episode.indexNumber))
                if (episode.runtimeTicks > 0) {
                    add(
                        stringResource(
                            CoreR.string.runtime_minutes,
                            (episode.runtimeTicks / Constants.TICKS_PER_MINUTE).toInt(),
                        )
                    )
                }
            }
            .joinToString(" · ")

    DownloadRow(
        item = episode,
        title = episode.name,
        titleStyle = MaterialTheme.typography.titleSmall,
        meta = meta,
        shape = shape,
        containerColor =
            if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        metaColor =
            if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        showPlaybackProgress = true,
        showPlayedBadge = true,
        onPlayClick = onPlayClick,
        onClick = onClick,
        trailing =
            if (showDownloadControl) {
                {
                    EpisodeDownloadControl(
                        downloadProgress = downloadProgress,
                        onDownloadClick = onDownloadClick,
                        onCancelClick = onDownloadCancelClick,
                        onRetryClick = onDownloadRetryClick,
                        onRemoveClick = onDownloadRemoveClick,
                    )
                }
            } else null,
        modifier = modifier.semantics { this.selected = selected },
    )
}

/**
 * The detail pane: the episode's wide page without its top bar, driven by an `EpisodeViewModel`
 * (and a `DownloaderViewModel`) keyed on the episode, so switching episodes shows the right one
 * and switching back finds it loaded. Play, watched, favourite and download act through
 * `EpisodeAction` and the downloader as on the episode page.
 */
@Composable
private fun SeasonEpisodeDetailPane(
    episodeId: UUID,
    navigateToPerson: (personId: UUID) -> Unit,
    onOverArtworkChange: (Boolean) -> Unit,
    onEpisodeChanged: (FindroidEpisode) -> Unit,
    onDownloadChanged: () -> Unit,
) {
    val context = LocalContext.current
    val isOfflineMode = LocalOfflineMode.current
    val viewModel: EpisodeViewModel = hiltViewModel(key = "season-detail-episode-$episodeId")
    val downloaderViewModel: DownloaderViewModel =
        hiltViewModel(key = "season-detail-downloader-$episodeId")

    val state by viewModel.state.collectAsStateWithLifecycle()
    // Not read here: a progress tick must only recompose the download button.
    val downloaderState = downloaderViewModel.state.collectAsStateWithLifecycle()
    val currentOnEpisodeChanged by rememberUpdatedState(onEpisodeChanged)
    val currentOnDownloadChanged by rememberUpdatedState(onDownloadChanged)

    ReloadOnResume(shouldReload = { viewModel.shouldReload(episodeId, stopEventFollows = true) }) {
        viewModel.loadEpisode(episodeId = episodeId)
    }

    LaunchedEffect(state.episode) {
        state.episode?.let { episode ->
            downloaderViewModel.update(episode)
            currentOnEpisodeChanged(episode)
        }
    }

    ObserveAsEvents(downloaderViewModel.events) { event ->
        when (event) {
            is DownloaderEvent.Successful -> {
                viewModel.loadEpisode(episodeId = episodeId)
                currentOnDownloadChanged()
            }
            is DownloaderEvent.Deleted -> {
                // Offline, the season drops the episode and the selection moves on.
                if (!isOfflineMode) viewModel.loadEpisode(episodeId = episodeId)
                currentOnDownloadChanged()
            }
            is DownloaderEvent.Failed -> {
                val message =
                    event.errorText?.asString(context.resources)
                        ?: context.getString(CoreR.string.download_failed)
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // CE (#510): the pane's own media info sheet, closed again when another episode is picked.
    var mediaInfoOpen by rememberSaveable(episodeId) { mutableStateOf(false) }
    val episodeSources = state.episode?.sources
    val mediaInfoSources = remember(episodeSources) { episodeSources.orEmpty().withMediaInfo() }
    if (mediaInfoOpen && mediaInfoSources.isNotEmpty()) {
        MediaInfoSheet(
            sources = mediaInfoSources,
            onDismissRequest = { mediaInfoOpen = false },
        )
    }

    // CE (#437): this pane's artwork is under the status bar until its band has faded in.
    val scrollState = rememberScrollState()
    val topBarBackgroundAlpha = rememberTopBarBackgroundAlpha(scrollState)
    val artwork = rememberArtworkState(state.episode, ArtworkPlacement.Hero)
    val overArtwork by
        remember(artwork, topBarBackgroundAlpha) {
            derivedStateOf { artwork.hasArtwork && topBarBackgroundAlpha() < 0.5f }
        }
    val currentOnOverArtworkChange by rememberUpdatedState(onOverArtworkChange)
    DisposableEffect(overArtwork) {
        currentOnOverArtworkChange(overArtwork)
        onDispose { currentOnOverArtworkChange(false) }
    }

    EpisodeWideContent(
        state = state,
        downloaderState = { downloaderState.value },
        onMediaInfoClick = { mediaInfoOpen = true }.takeIf { mediaInfoSources.isNotEmpty() },
        onAction = { action ->
            when (action) {
                is EpisodeAction.Play -> {
                    val intent = Intent(context, PlayerActivity::class.java)
                    intent.putExtra("itemId", episodeId.toString())
                    intent.putExtra("itemKind", BaseItemKind.EPISODE.serialName)
                    intent.putExtra("startFromBeginning", action.startFromBeginning)
                    intent.putExtra("mediaSourceId", action.mediaSourceId)
                    context.startActivity(intent)
                }
                is EpisodeAction.NavigateToPerson -> navigateToPerson(action.personId)
                else -> Unit
            }
            viewModel.onAction(action)
        },
        onDownloaderAction = { action -> downloaderViewModel.onAction(action) },
        scrollState = scrollState,
        artwork = artwork,
    )
    DetailPaneStatusBarBackground(alpha = topBarBackgroundAlpha)
}

/**
 * CE (#437): the detail pane has no top bar of its own (the page's is over the list), so this
 * band keeps the scrolled episode from running under the status bar. It fades in as the list's
 * top bar does.
 */
@Composable
private fun DetailPaneStatusBarBackground(alpha: () -> Float) {
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .height(rememberSafePadding().top + MaterialTheme.spacings.medium)
                .graphicsLayer { this.alpha = alpha() }
                .background(Brush.verticalGradient(0.7f to background, 1f to Color.Transparent))
    )
}

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun SeasonWideLayoutPreview() {
    FindroidTheme {
        SeasonWideLayout(
            state = SeasonState(season = dummySeason, episodes = dummyEpisodes),
            selectedEpisodeId = dummyEpisodes.first().id,
            onSelectEpisode = {},
            onAction = {},
            onDownloadSeason = {},
            onDeleteSeasonDownloads = {},
            detailPane = {},
        )
    }
}
