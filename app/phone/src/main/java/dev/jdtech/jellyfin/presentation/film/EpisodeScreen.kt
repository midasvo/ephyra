package dev.jdtech.jellyfin.presentation.film

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.PlayerActivity
import dev.jdtech.jellyfin.core.Constants
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderAction
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderEvent
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderState
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderViewModel
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.core.presentation.dummy.dummyVideoMetadata
import dev.jdtech.jellyfin.film.domain.withMediaInfo
import dev.jdtech.jellyfin.film.presentation.episode.EpisodeAction
import dev.jdtech.jellyfin.film.presentation.episode.EpisodeState
import dev.jdtech.jellyfin.film.presentation.episode.EpisodeViewModel
import dev.jdtech.jellyfin.presentation.film.components.ActorsRow
import dev.jdtech.jellyfin.presentation.film.components.ArtworkPlacement
import dev.jdtech.jellyfin.presentation.film.components.DownloadQualityDialog
import dev.jdtech.jellyfin.presentation.film.components.ExternalLinksRow
import dev.jdtech.jellyfin.presentation.film.components.MediaInfoSheet
import dev.jdtech.jellyfin.presentation.film.components.WatchHistoryText
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.presentation.film.components.ItemButtonsBar
import dev.jdtech.jellyfin.presentation.film.components.ItemHeader
import dev.jdtech.jellyfin.presentation.film.components.ItemTopBar
import dev.jdtech.jellyfin.presentation.film.components.LoadingOrError
import dev.jdtech.jellyfin.presentation.film.components.OverviewText
import dev.jdtech.jellyfin.presentation.film.components.VideoMetadataBar
import dev.jdtech.jellyfin.presentation.film.components.rememberArtworkState
import dev.jdtech.jellyfin.presentation.film.components.rememberTopBarBackgroundAlpha
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.LocalOfflineMode
import dev.jdtech.jellyfin.presentation.utils.LightStatusBarOverArtwork
import dev.jdtech.jellyfin.presentation.utils.ReloadOnResume
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding
import dev.jdtech.jellyfin.presentation.utils.useSeasonListDetail
import dev.jdtech.jellyfin.presentation.utils.useWideItemLayout
import dev.jdtech.jellyfin.utils.ObserveAsEvents
import dev.jdtech.jellyfin.utils.copyOnLongClick
import dev.jdtech.jellyfin.utils.format
import android.widget.Toast
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind

@Composable
fun EpisodeScreen(
    episodeId: UUID,
    navigateBack: () -> Unit,
    navigateHome: () -> Unit,
    navigateToPerson: (personId: UUID) -> Unit,
    navigateToSeason: (seasonId: UUID, seriesId: UUID) -> Unit,
    navigateToSeries: (seriesId: UUID) -> Unit = {},
    viewModel: EpisodeViewModel = hiltViewModel(),
    downloaderViewModel: DownloaderViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val isOfflineMode = LocalOfflineMode.current

    val state by viewModel.state.collectAsStateWithLifecycle()
    // Not read here: a progress tick must only recompose the download card.
    val downloaderState = downloaderViewModel.state.collectAsStateWithLifecycle()

    ReloadOnResume(shouldReload = { viewModel.shouldReload(episodeId, stopEventFollows = true) }) {
        viewModel.loadEpisode(episodeId = episodeId)
    }

    LaunchedEffect(state.episode) {
        state.episode?.let { episode -> downloaderViewModel.update(episode) }
    }

    ObserveAsEvents(downloaderViewModel.events) { event ->
        when (event) {
            is DownloaderEvent.Successful -> {
                viewModel.loadEpisode(episodeId = episodeId)
            }
            is DownloaderEvent.Deleted -> {
                if (isOfflineMode) {
                    navigateBack()
                } else {
                    viewModel.loadEpisode(episodeId = episodeId)
                }
            }
            is DownloaderEvent.Failed -> {
                val message = event.errorText?.asString(context.resources)
                    ?: context.getString(CoreR.string.download_failed)
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // CE: on an expanded window an episode opens as its season's list-detail with the episode
    // selected, so Next Up, search and Downloads land where the season page would (4-design.md §7).
    val loadedEpisode = state.episode
    if (useSeasonListDetail() && loadedEpisode != null && loadedEpisode.id == episodeId) {
        SeasonScreen(
            seasonId = loadedEpisode.seasonId,
            seriesId = loadedEpisode.seriesId,
            initialEpisodeId = episodeId,
            navigateBack = navigateBack,
            navigateHome = navigateHome,
            // Only the single-column season page navigates to an episode.
            navigateToItem = {},
            navigateToSeries = navigateToSeries,
            navigateToPerson = navigateToPerson,
        )
        return
    }

    EpisodeScreenLayout(
        state = state,
        downloaderState = { downloaderState.value },
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
                is EpisodeAction.OpenExternalLink -> openExternalLink(context, uriHandler, action.url)
                is EpisodeAction.OnBackClick -> navigateBack()
                is EpisodeAction.OnHomeClick -> navigateHome()
                is EpisodeAction.NavigateToPerson -> navigateToPerson(action.personId)
                is EpisodeAction.NavigateToSeason -> navigateToSeason(action.seasonId, action.seriesId)
                is EpisodeAction.NavigateToSeries -> navigateToSeries(action.seriesId)
                else -> Unit
            }
            viewModel.onAction(action)
        },
        onDownloaderAction = { action -> downloaderViewModel.onAction(action) },
    )
}

@Composable
private fun EpisodeScreenLayout(
    state: EpisodeState,
    downloaderState: () -> DownloaderState,
    onAction: (EpisodeAction) -> Unit,
    onDownloaderAction: (DownloaderAction) -> Unit,
) {
    // CE (#510): above the narrow/wide branch, so the sheet stays open, on the version picked,
    // when a rotation, a fold or a resized window changes the layout. Shown once there is
    // something to show: after process death the flag is back before the episode is.
    var mediaInfoOpen by rememberSaveable { mutableStateOf(false) }
    val episodeSources = state.episode?.sources
    val mediaInfoSources = remember(episodeSources) { episodeSources.orEmpty().withMediaInfo() }
    // No chip when there is nothing to describe: offline, a download has no track data.
    val onMediaInfoClick = { mediaInfoOpen = true }.takeIf { mediaInfoSources.isNotEmpty() }
    if (mediaInfoOpen && mediaInfoSources.isNotEmpty()) {
        MediaInfoSheet(
            sources = mediaInfoSources,
            onDismissRequest = { mediaInfoOpen = false },
        )
    }

    // CE: medium and expanded windows get the hero layout (docs/tablet/4-design.md §6).
    if (useWideItemLayout()) {
        EpisodeWideLayout(
            state,
            downloaderState,
            onAction,
            onDownloaderAction,
            onMediaInfoClick = onMediaInfoClick,
        )
        return
    }

    val safePadding = rememberSafePadding()

    val paddingStart = safePadding.start + MaterialTheme.spacings.default
    val paddingEnd = safePadding.end + MaterialTheme.spacings.default
    val paddingBottom = safePadding.bottom + MaterialTheme.spacings.default

    val scrollState = rememberScrollState()
    // CE (#437): the artwork under the top bar, if any; shared by the header and the bar.
    val artwork = rememberArtworkState(state.episode, ArtworkPlacement.Header)

    var downloadQualityDialogOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        state.episode?.let { episode ->
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(scrollState)) {
                ItemHeader(
                    item = episode,
                    scrollState = scrollState,
                    artwork = artwork,
                    content = {
                        Column(
                            modifier =
                                Modifier.align(Alignment.BottomStart)
                                    .padding(start = paddingStart, end = paddingEnd)
                        ) {
                            val seasonName =
                                episode.seasonName
                                    ?: run {
                                        stringResource(
                                            CoreR.string.season_number,
                                            episode.parentIndexNumber,
                                        )
                                    }
                            val episodeNumberText = stringResource(
                                id = CoreR.string.episode_number,
                                episode.indexNumber,
                            )
                            val prefixText = "$seasonName - $episodeNumberText"
                            Text(
                                text = prefixText,
                                modifier = Modifier.copyOnLongClick(prefixText),
                                maxLines = 1,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text = episode.name,
                                modifier = Modifier.copyOnLongClick(episode.name),
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 3,
                                style = MaterialTheme.typography.headlineMedium,
                            )
                        }
                    },
                )
                Column(modifier = Modifier.padding(start = paddingStart, end = paddingEnd)) {
                    Spacer(Modifier.height(MaterialTheme.spacings.small))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        episode.premiereDate?.let { premiereDate ->
                            val locale = LocalConfiguration.current.locales.get(0)
                            val dateText = premiereDate.format(locale)
                            Text(
                                text = dateText,
                                modifier = Modifier.copyOnLongClick(dateText),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        val runtimeText = stringResource(
                            CoreR.string.runtime_minutes,
                            episode.runtimeTicks.div(Constants.TICKS_PER_MINUTE),
                        )
                        Text(
                            text = runtimeText,
                            modifier = Modifier.copyOnLongClick(runtimeText),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        episode.communityRating?.let { communityRating ->
                            val ratingText = "%.1f".format(communityRating)
                            Row(
                                modifier = Modifier.copyOnLongClick(ratingText),
                                verticalAlignment = Alignment.Bottom
                            ) {
                                Icon(
                                    painter = painterResource(CoreR.drawable.ic_star),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                )
                                Spacer(Modifier.width(MaterialTheme.spacings.extraSmall))
                                Text(
                                    text = "%.1f".format(communityRating),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(MaterialTheme.spacings.small))
                    state.videoMetadata?.let { videoMetadata ->
                        VideoMetadataBar(videoMetadata, onInfoClick = onMediaInfoClick)
                        Spacer(Modifier.height(MaterialTheme.spacings.small))
                    }
                    ItemButtonsBar(
                        item = episode,
                        downloaderStateProvider = downloaderState,
                        onPlayClick = { startFromBeginning ->
                            onAction(EpisodeAction.Play(startFromBeginning = startFromBeginning))
                        },
                        onPlayVersionClick = { startFromBeginning, mediaSourceId ->
                            onAction(
                                EpisodeAction.Play(
                                    startFromBeginning = startFromBeginning,
                                    mediaSourceId = mediaSourceId,
                                )
                            )
                        },
                        onMarkAsPlayedClick = {
                            when (episode.played) {
                                true -> onAction(EpisodeAction.UnmarkAsPlayed)
                                false -> onAction(EpisodeAction.MarkAsPlayed)
                            }
                        },
                        onMarkAsFavoriteClick = {
                            when (episode.favorite) {
                                true -> onAction(EpisodeAction.UnmarkAsFavorite)
                                false -> onAction(EpisodeAction.MarkAsFavorite)
                            }
                        },
                        onTrailerClick = {},
                        onDownloadClick = { downloadQualityDialogOpen = true },
                        onDownloadCancelClick = {
                            onDownloaderAction(DownloaderAction.CancelDownload(episode))
                        },
                        onDownloadDeleteClick = {
                            onDownloaderAction(DownloaderAction.DeleteDownload(episode))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(MaterialTheme.spacings.small))
                    OverviewText(text = episode.overview)
                    EpisodeWatchAndLinks(episode, onAction)
                    Spacer(Modifier.height(MaterialTheme.spacings.medium))
                }
                if (state.actors.isNotEmpty()) {
                    ActorsRow(
                        actors = state.actors,
                        onActorClick = { personId ->
                            onAction(EpisodeAction.NavigateToPerson(personId))
                        },
                        contentPadding = PaddingValues(start = paddingStart, end = paddingEnd),
                    )
                }
                Spacer(Modifier.height(paddingBottom))
            }
        } ?: run {
            LoadingOrError(
                error = state.error,
                onRetryClick = { onAction(EpisodeAction.OnRetryClick) },
            )
        }

        LightStatusBarOverArtwork(artwork.hasArtwork, rememberTopBarBackgroundAlpha(scrollState))
        ItemTopBar(
            hasBackButton = true,
            hasHomeButton = true,
            backgroundAlpha = rememberTopBarBackgroundAlpha(scrollState),
            overArtwork = artwork.hasArtwork,
            onBackClick = { onAction(EpisodeAction.OnBackClick) },
            onHomeClick = { onAction(EpisodeAction.OnHomeClick) },
        ) {
            state.episode?.let { episode -> EpisodeTopBarLinks(episode, onAction) }
        }

        if (downloadQualityDialogOpen) {
            DownloadQualityDialog(
                onSelect = { maxBitrate ->
                    state.episode?.let { episode ->
                        onDownloaderAction(DownloaderAction.Download(episode, maxBitrate))
                    }
                    downloadQualityDialogOpen = false
                },
                onDismiss = { downloadQualityDialogOpen = false },
            )
        }
    }
}

/** CE (#332): the watch history and the outside links, right below the overview. */
@Composable
internal fun EpisodeWatchAndLinks(episode: FindroidEpisode, onAction: (EpisodeAction) -> Unit) {
    if (episode.playCount > 0) {
        Spacer(Modifier.height(MaterialTheme.spacings.medium))
        WatchHistoryText(playCount = episode.playCount, lastPlayedDate = episode.lastPlayedDate)
    }
    if (episode.externalLinks.isNotEmpty() && !LocalOfflineMode.current) {
        Spacer(Modifier.height(MaterialTheme.spacings.medium))
        ExternalLinksRow(episode.externalLinks) { url ->
            onAction(EpisodeAction.OpenExternalLink(url))
        }
    }
}

@PreviewScreenSizes
@Composable
private fun EpisodeScreenLayoutPreview() {
    FindroidTheme {
        EpisodeScreenLayout(
            state = EpisodeState(episode = dummyEpisode, videoMetadata = dummyVideoMetadata),
            downloaderState = { DownloaderState() },
            onAction = {},
            onDownloaderAction = {},
        )
    }
}
