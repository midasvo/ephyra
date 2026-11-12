package dev.jdtech.jellyfin.presentation.film

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.PlayerActivity
import dev.jdtech.jellyfin.core.Constants
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderAction
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderEvent
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderState
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderViewModel
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.core.presentation.dummy.dummyVideoMetadata
import dev.jdtech.jellyfin.film.domain.withMediaInfo
import dev.jdtech.jellyfin.film.presentation.movie.MovieAction
import dev.jdtech.jellyfin.film.presentation.movie.MovieState
import dev.jdtech.jellyfin.film.presentation.movie.MovieViewModel
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.presentation.film.components.ActorsRow
import dev.jdtech.jellyfin.presentation.film.components.ArtworkPlacement
import dev.jdtech.jellyfin.presentation.film.components.CriticRating
import dev.jdtech.jellyfin.presentation.film.components.Direction
import dev.jdtech.jellyfin.presentation.film.components.ExternalLinksRow
import dev.jdtech.jellyfin.presentation.film.components.ItemsRow
import dev.jdtech.jellyfin.presentation.film.components.TaglineText
import dev.jdtech.jellyfin.presentation.film.components.WatchHistoryText
import dev.jdtech.jellyfin.presentation.film.components.DownloadQualityDialog
import dev.jdtech.jellyfin.presentation.film.components.MediaInfoSheet
import dev.jdtech.jellyfin.presentation.film.components.InfoText
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
import dev.jdtech.jellyfin.presentation.utils.useWideItemLayout
import dev.jdtech.jellyfin.utils.AfterFirstFrame
import dev.jdtech.jellyfin.utils.ObserveAsEvents
import dev.jdtech.jellyfin.utils.copyOnLongClick
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind

@Composable
fun MovieScreen(
    movieId: UUID,
    navigateBack: () -> Unit,
    navigateHome: () -> Unit,
    navigateToPerson: (personId: UUID) -> Unit,
    navigateToItem: (item: FindroidItem) -> Unit = {},
    viewModel: MovieViewModel = hiltViewModel(),
    downloaderViewModel: DownloaderViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val isOfflineMode = LocalOfflineMode.current

    val state by viewModel.state.collectAsStateWithLifecycle()
    // Not read here: a progress tick must only recompose the download card.
    val downloaderState = downloaderViewModel.state.collectAsStateWithLifecycle()

    ReloadOnResume(shouldReload = { viewModel.shouldReload(movieId, stopEventFollows = true) }) {
        viewModel.loadMovie(movieId = movieId)
    }

    LaunchedEffect(state.movie) { state.movie?.let { movie -> downloaderViewModel.update(movie) } }

    // CE (#332): the rows below the page only once the movie is on screen.
    AfterFirstFrame(state.movie?.id) { id -> viewModel.loadRelated(id) }

    ObserveAsEvents(downloaderViewModel.events) { event ->
        when (event) {
            is DownloaderEvent.Successful -> {
                viewModel.loadMovie(movieId = movieId)
            }
            is DownloaderEvent.Deleted -> {
                if (isOfflineMode) {
                    navigateBack()
                } else {
                    viewModel.loadMovie(movieId = movieId)
                }
            }
            is DownloaderEvent.Failed -> {
                val message = event.errorText?.asString(context.resources)
                    ?: context.getString(CoreR.string.download_failed)
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    MovieScreenLayout(
        state = state,
        downloaderState = { downloaderState.value },
        onAction = { action ->
            when (action) {
                is MovieAction.Play -> {
                    val intent = Intent(context, PlayerActivity::class.java)
                    intent.putExtra("itemId", movieId.toString())
                    intent.putExtra("itemKind", BaseItemKind.MOVIE.serialName)
                    intent.putExtra("startFromBeginning", action.startFromBeginning)
                    intent.putExtra("mediaSourceId", action.mediaSourceId)
                    context.startActivity(intent)
                }
                is MovieAction.PlayTrailer -> {
                    try {
                        uriHandler.openUri(action.trailer)
                    } catch (e: IllegalArgumentException) {
                        Toast.makeText(context, e.localizedMessage, Toast.LENGTH_SHORT).show()
                    }
                }
                is MovieAction.PlayExtra -> playExtra(context, action.extraId)
                is MovieAction.OpenExternalLink -> openExternalLink(context, uriHandler, action.url)
                is MovieAction.NavigateToItem -> navigateToItem(action.item)
                is MovieAction.OnBackClick -> navigateBack()
                is MovieAction.OnHomeClick -> navigateHome()
                is MovieAction.NavigateToPerson -> navigateToPerson(action.personId)
                else -> Unit
            }
            viewModel.onAction(action)
        },
        onDownloaderAction = { action -> downloaderViewModel.onAction(action) },
    )
}

@Composable
private fun MovieScreenLayout(
    state: MovieState,
    downloaderState: () -> DownloaderState,
    onAction: (MovieAction) -> Unit,
    onDownloaderAction: (DownloaderAction) -> Unit,
) {
    // CE (#510): above the narrow/wide branch, so the sheet stays open, on the version picked,
    // when a rotation, a fold or a resized window changes the layout. Shown once there is
    // something to show: after process death the flag is back before the movie is.
    var mediaInfoOpen by rememberSaveable { mutableStateOf(false) }
    val movieSources = state.movie?.sources
    val mediaInfoSources = remember(movieSources) { movieSources.orEmpty().withMediaInfo() }
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
        MovieWideLayout(
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
    val artwork = rememberArtworkState(state.movie, ArtworkPlacement.Header)

    var downloadQualityDialogOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        state.movie?.let { movie ->
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(scrollState)) {
                ItemHeader(
                    item = movie,
                    scrollState = scrollState,
                    artwork = artwork,
                    content = {
                        Column(
                            modifier =
                                Modifier.align(Alignment.BottomStart)
                                    .padding(start = paddingStart, end = paddingEnd)
                        ) {
                            Text(
                                text = movie.name,
                                modifier = Modifier.copyOnLongClick(movie.name),
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 3,
                                style = MaterialTheme.typography.headlineMedium,
                            )
                            movie.originalTitle?.let { originalTitle ->
                                if (originalTitle != movie.name) {
                                    Text(
                                        text = originalTitle,
                                        modifier = Modifier.copyOnLongClick(originalTitle),
                                        overflow = TextOverflow.Ellipsis,
                                        maxLines = 1,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
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
                        movie.premiereDate?.let { premiereDate ->
                            val premiereDateText = premiereDate.year.toString()
                            Text(
                                text = premiereDateText,
                                modifier = Modifier.copyOnLongClick(premiereDateText),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        val runtimeText = stringResource(
                            CoreR.string.runtime_minutes,
                            movie.runtimeTicks.div(Constants.TICKS_PER_MINUTE),
                        )
                        Text(
                            text = runtimeText,
                            modifier = Modifier.copyOnLongClick(runtimeText),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        movie.officialRating?.let { officialRating ->
                            Text(
                                text = officialRating,
                                modifier = Modifier.copyOnLongClick(officialRating),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        movie.communityRating?.let { communityRating ->
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
                        CriticRating(movie.criticRating)
                    }
                    Spacer(Modifier.height(MaterialTheme.spacings.small))
                    state.videoMetadata?.let { videoMetadata ->
                        VideoMetadataBar(
                            videoMetadata,
                            is3D = movie.is3D,
                            onInfoClick = onMediaInfoClick,
                        )
                        Spacer(Modifier.height(MaterialTheme.spacings.small))
                    }
                    ItemButtonsBar(
                        item = movie,
                        downloaderStateProvider = downloaderState,
                        onPlayClick = { startFromBeginning ->
                            onAction(MovieAction.Play(startFromBeginning = startFromBeginning))
                        },
                        onPlayVersionClick = { startFromBeginning, mediaSourceId ->
                            onAction(
                                MovieAction.Play(
                                    startFromBeginning = startFromBeginning,
                                    mediaSourceId = mediaSourceId,
                                )
                            )
                        },
                        onMarkAsPlayedClick = {
                            when (movie.played) {
                                true -> onAction(MovieAction.UnmarkAsPlayed)
                                false -> onAction(MovieAction.MarkAsPlayed)
                            }
                        },
                        onMarkAsFavoriteClick = {
                            when (movie.favorite) {
                                true -> onAction(MovieAction.UnmarkAsFavorite)
                                false -> onAction(MovieAction.MarkAsFavorite)
                            }
                        },
                        onTrailerClick = { uri -> onAction(MovieAction.PlayTrailer(uri)) },
                        onDownloadClick = { downloadQualityDialogOpen = true },
                        onDownloadCancelClick = {
                            onDownloaderAction(DownloaderAction.CancelDownload(movie))
                        },
                        onDownloadDeleteClick = {
                            onDownloaderAction(DownloaderAction.DeleteDownload(movie))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(MaterialTheme.spacings.small))
                    movie.tagline?.let { tagline ->
                        TaglineText(tagline)
                        Spacer(Modifier.height(MaterialTheme.spacings.small))
                    }
                    OverviewText(text = movie.overview, maxCollapsedLines = 3)
                    Spacer(Modifier.height(MaterialTheme.spacings.medium))
                    InfoText(
                        genres = movie.genres,
                        director = state.director,
                        writers = state.writers,
                        studios = movie.studios,
                    )
                    MovieWatchAndLinks(movie, onAction)
                    Spacer(Modifier.height(MaterialTheme.spacings.medium))
                }
                if (state.actors.isNotEmpty()) {
                    ActorsRow(
                        actors = state.actors,
                        onActorClick = { personId ->
                            onAction(MovieAction.NavigateToPerson(personId))
                        },
                        contentPadding = PaddingValues(start = paddingStart, end = paddingEnd),
                    )
                    Spacer(Modifier.height(MaterialTheme.spacings.medium))
                }
                MovieRelatedRows(
                    state = state,
                    onAction = onAction,
                    contentPadding = PaddingValues(start = paddingStart, end = paddingEnd),
                )
                Spacer(Modifier.height(paddingBottom))
            }
        } ?: run {
            LoadingOrError(
                error = state.error,
                onRetryClick = { onAction(MovieAction.OnRetryClick) },
            )
        }

        LightStatusBarOverArtwork(artwork.hasArtwork, rememberTopBarBackgroundAlpha(scrollState))
        ItemTopBar(
            hasBackButton = true,
            hasHomeButton = true,
            backgroundAlpha = rememberTopBarBackgroundAlpha(scrollState),
            overArtwork = artwork.hasArtwork,
            onBackClick = { onAction(MovieAction.OnBackClick) },
            onHomeClick = { onAction(MovieAction.OnHomeClick) },
        )


        if (downloadQualityDialogOpen) {
            DownloadQualityDialog(
                onSelect = { maxBitrate ->
                    state.movie?.let { movie ->
                        onDownloaderAction(DownloaderAction.Download(movie, maxBitrate))
                    }
                    downloadQualityDialogOpen = false
                },
                onDismiss = { downloadQualityDialogOpen = false },
            )
        }
    }
}

/** CE (#332): the watch history and the outside links, right below the info text. */
@Composable
internal fun MovieWatchAndLinks(movie: FindroidMovie, onAction: (MovieAction) -> Unit) {
    if (movie.playCount > 0) {
        Spacer(Modifier.height(MaterialTheme.spacings.small))
        WatchHistoryText(playCount = movie.playCount, lastPlayedDate = movie.lastPlayedDate)
    }
    if (movie.externalLinks.isNotEmpty() && !LocalOfflineMode.current) {
        Spacer(Modifier.height(MaterialTheme.spacings.medium))
        ExternalLinksRow(movie.externalLinks) { url -> onAction(MovieAction.OpenExternalLink(url)) }
    }
}

/** CE (#332): extras, the collections it is in and "More like this", loaded after the page. */
@Composable
internal fun MovieRelatedRows(
    state: MovieState,
    onAction: (MovieAction) -> Unit,
    contentPadding: PaddingValues,
) {
    ItemsRow(
        title = stringResource(CoreR.string.item_extras),
        items = state.related.extras,
        direction = Direction.HORIZONTAL,
        onItemClick = { onAction(MovieAction.PlayExtra(it.id)) },
        contentPadding = contentPadding,
    )
    ItemsRow(
        title = stringResource(CoreR.string.item_included_in),
        items = state.related.collections,
        direction = Direction.VERTICAL,
        onItemClick = { onAction(MovieAction.NavigateToItem(it)) },
        contentPadding = contentPadding,
    )
    ItemsRow(
        title = stringResource(CoreR.string.item_more_like_this),
        items = state.related.similar,
        direction = Direction.VERTICAL,
        onItemClick = { onAction(MovieAction.NavigateToItem(it)) },
        contentPadding = contentPadding,
    )
}

@PreviewScreenSizes
@Composable
private fun EpisodeScreenLayoutPreview() {
    FindroidTheme {
        MovieScreenLayout(
            state = MovieState(movie = dummyMovie, videoMetadata = dummyVideoMetadata),
            downloaderState = { DownloaderState() },
            onAction = {},
            onDownloaderAction = {},
        )
    }
}
