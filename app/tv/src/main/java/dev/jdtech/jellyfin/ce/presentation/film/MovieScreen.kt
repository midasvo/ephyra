package dev.jdtech.jellyfin.ce.presentation.film

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.ce.ui.PersonCard
import dev.jdtech.jellyfin.ce.ui.components.TvLoadError
import dev.jdtech.jellyfin.ce.ui.components.TvOverviewText
import dev.jdtech.jellyfin.ce.ui.focus.NoBringIntoView
import dev.jdtech.jellyfin.ce.ui.focus.RestoreFocus
import dev.jdtech.jellyfin.ce.ui.focus.ephyraButtonBorder
import dev.jdtech.jellyfin.ce.ui.focus.focusPosition
import dev.jdtech.jellyfin.ce.ui.focus.rememberFocusPosition
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.core.presentation.dummy.dummyVideoMetadata
import dev.jdtech.jellyfin.film.presentation.movie.MovieAction
import dev.jdtech.jellyfin.film.presentation.movie.MovieState
import dev.jdtech.jellyfin.film.presentation.movie.MovieViewModel
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.getVersions
import dev.jdtech.jellyfin.ui.components.Direction
import dev.jdtech.jellyfin.utils.AfterFirstFrame
import dev.jdtech.jellyfin.presentation.film.components.VersionDialog
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.utils.format
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * CE replacement of the upstream TV movie page (ADR 0001).
 * Shows metadata, buttons, genres/crew and a cast row. Back from a person restores focus to that
 * card (ADR 0003).
 */
@Composable
fun MovieScreen(
    movieId: UUID,
    navigateToPlayer: (itemId: UUID, mediaSourceId: String?, startFromBeginning: Boolean) -> Unit,
    navigateToPerson: (personId: UUID) -> Unit,
    navigateToItem: (item: FindroidItem) -> Unit = {},
    viewModel: MovieViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(movieId) {
        if (viewModel.shouldReload(movieId)) viewModel.loadMovie(movieId = movieId)
    }

    // CE (#332): the rows below the page only once the movie is on screen.
    AfterFirstFrame(state.movie?.id) { id -> viewModel.loadRelated(id) }

    MovieScreenLayout(
        state = state,
        onAction = { action ->
            when (action) {
                is MovieAction.Play -> {
                    navigateToPlayer(movieId, action.mediaSourceId, action.startFromBeginning)
                }
                is MovieAction.PlayTrailer -> {
                    try {
                        uriHandler.openUri(action.trailer)
                    } catch (e: IllegalArgumentException) {
                        Toast.makeText(context, e.localizedMessage, Toast.LENGTH_SHORT).show()
                    }
                }
                is MovieAction.NavigateToPerson -> {
                    navigateToPerson(action.personId)
                }
                is MovieAction.PlayExtra -> navigateToPlayer(action.extraId, null, false)
                is MovieAction.NavigateToItem -> navigateToItem(action.item)
                else -> Unit
            }
            viewModel.onAction(action)
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MovieScreenLayout(state: MovieState, onAction: (MovieAction) -> Unit) {
    // CE (#514): the page scrolls itself when a row gets focus, so moving along the buttons leaves
    // the title on screen. The rows and the version dialog keep the default.
    val rowSpec = LocalBringIntoViewSpec.current
    CompositionLocalProvider(LocalBringIntoViewSpec provides NoBringIntoView) {
        MovieScreenContent(state = state, onAction = onAction, rowSpec = rowSpec)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MovieScreenContent(
    state: MovieState,
    onAction: (MovieAction) -> Unit,
    rowSpec: BringIntoViewSpec,
) {
    val configuration = LocalConfiguration.current
    val locale = configuration.locales.get(0)

    val focusPosition = rememberFocusPosition(row = ROW_BUTTONS, column = 0)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val scrollToRow: (Int) -> Unit = { row ->
        scope.launch {
            withFrameNanos {}
            listState.animateScrollToItem(row)
        }
    }

    // Null while closed; otherwise whether the chosen version starts from the beginning.
    var versionDialogStartFromBeginning by remember { mutableStateOf<Boolean?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        // CE (#544): an if, not movie?.let { } ?: run { }. That let ended with the version
        // dialog's ?.let, null while the dialog is closed, so the spinner showed over the page.
        val movie = state.movie
        if (movie != null) {
            val versions = remember(movie.sources) { movie.getVersions() }
            val inProgress = movie.playbackPositionTicks > 0 && !movie.played
            val relatedRows =
                remember(state.related) {
                    listOf(
                            RelatedRow(
                                ROW_EXTRAS,
                                CoreR.string.item_extras,
                                Direction.HORIZONTAL,
                                state.related.extras,
                            ),
                            RelatedRow(
                                ROW_COLLECTIONS,
                                CoreR.string.item_included_in,
                                Direction.VERTICAL,
                                state.related.collections,
                            ),
                            RelatedRow(
                                ROW_SIMILAR,
                                CoreR.string.item_more_like_this,
                                Direction.VERTICAL,
                                state.related.similar,
                            ),
                        )
                        .filter { it.items.isNotEmpty() }
                }
            // The list holds the header, the cast row when there is a cast, then the related rows.
            val firstRelatedIndex = if (state.actors.isNotEmpty()) 2 else 1
            val lazyIndexOf: (Int) -> Int = { row ->
                relatedRows.relatedLazyIndex(row, firstRelatedIndex) ?: row
            }
            var size by remember { mutableStateOf(Size.Zero) }
            Box(
                modifier =
                    Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                        size = coordinates.size.toSize()
                    }
            ) {
                AsyncImage(
                    model = rememberDetailBackdrop(movie.images.backdrop),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (size != Size.Zero) {
                    Box(
                        modifier =
                            Modifier.fillMaxSize()
                                .background(
                                    Brush.radialGradient(
                                        listOf(Color.Black.copy(alpha = .2f), MaterialTheme.colorScheme.surface),
                                        center = Offset(size.width, 0f),
                                        radius = size.width * .8f,
                                    )
                                )
                    )
                }
                LazyColumn(
                    state = listState,
                    contentPadding =
                        PaddingValues(top = 112.dp, bottom = MaterialTheme.spacings.large),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                    // CE (#514): no userScrollEnabled = false. Without user scrolling, Down from
                    // the buttons can't reach the cast row while that row is below the screen.
                ) {
                    item {
                        Column(
                            modifier =
                                Modifier.padding(
                                        start = MaterialTheme.spacings.default * 2,
                                        end = MaterialTheme.spacings.default * 2,
                                    )
                                    .onFocusChanged { if (it.hasFocus) scrollToRow(ROW_BUTTONS) }
                        ) {
                            Text(text = movie.name, style = MaterialTheme.typography.displayMedium)
                            if (movie.originalTitle != movie.name) {
                                movie.originalTitle?.let { originalTitle ->
                                    Text(
                                        text = originalTitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))
                            Row(
                                horizontalArrangement =
                                    Arrangement.spacedBy(MaterialTheme.spacings.small)
                            ) {
                                movie.premiereDate?.let { premiereDate ->
                                    Text(
                                        text = premiereDate.format(locale),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                Text(
                                    text =
                                        stringResource(
                                            CoreR.string.runtime_minutes,
                                            movie.runtimeTicks.div(600000000),
                                        ),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                // CE (#412): how much is left of a movie that is partly watched.
                                if (inProgress) {
                                    Text(
                                        text =
                                            stringResource(
                                                CoreR.string.runtime_minutes_left,
                                                minutesLeft(movie),
                                            ),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                movie.officialRating?.let {
                                    Text(text = it, style = MaterialTheme.typography.labelMedium)
                                }
                                movie.communityRating?.let {
                                    Row {
                                        Icon(
                                            painter = painterResource(id = CoreR.drawable.ic_star),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.tertiary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(
                                            modifier =
                                                Modifier.width(MaterialTheme.spacings.extraSmall)
                                        )
                                        Text(
                                            text = String.format(locale, "%.1f", it),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                }
                                TvCriticRating(movie.criticRating)
                                if (movie.is3D) {
                                    Text(text = "3D", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            if (movie.playCount > 0) {
                                Spacer(modifier = Modifier.height(MaterialTheme.spacings.extraSmall))
                                TvWatchHistory(movie.playCount, movie.lastPlayedDate)
                            }
                            Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))
                            TvTagline(movie.tagline)
                            // CE (#545): a cut overview takes focus and opens in full on OK.
                            TvOverviewText(
                                title = movie.name,
                                text = movie.overview,
                                maxLines = 4,
                                modifier = Modifier.width(640.dp),
                            )
                            Spacer(modifier = Modifier.height(MaterialTheme.spacings.default))
                            Row(
                                horizontalArrangement =
                                    Arrangement.spacedBy(MaterialTheme.spacings.medium),
                                modifier = Modifier.focusRestorer().focusGroup(),
                            ) {
                                // CE (#412): fixed columns, as on the season page. A button that
                                // is gone on return (Restart after the end) falls back to Play.
                                val trailerCol = 2
                                val playedCol = 3
                                val favoriteCol = 4
                                // A version dialog remembers whether it was opened to restart.
                                val play = { startFromBeginning: Boolean ->
                                    if (versions.size > 1) {
                                        versionDialogStartFromBeginning = startFromBeginning
                                    } else {
                                        onAction(MovieAction.Play(startFromBeginning))
                                    }
                                }

                                Button(
                                    border = ephyraButtonBorder(),
                                    onClick = { play(false) },
                                    modifier =
                                        Modifier.focusPosition(focusPosition, ROW_BUTTONS, 0),
                                ) {
                                    Icon(
                                        painter = painterResource(id = CoreR.drawable.ic_play),
                                        contentDescription = null,
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text =
                                            stringResource(
                                                id =
                                                    if (inProgress) CoreR.string.resume
                                                    else CoreR.string.play
                                            )
                                    )
                                }
                                if (inProgress) {
                                    Button(
                                        border = ephyraButtonBorder(),
                                        onClick = { play(true) },
                                        modifier =
                                            Modifier.focusPosition(focusPosition, ROW_BUTTONS, 1),
                                    ) {
                                        Icon(
                                            painter =
                                                painterResource(id = CoreR.drawable.ic_rotate_ccw),
                                            contentDescription = null,
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(text = stringResource(id = CoreR.string.restart))
                                    }
                                }
                                movie.trailer?.let { trailerUri ->
                                    Button(
                                        border = ephyraButtonBorder(),
                                        onClick = {
                                            onAction(MovieAction.PlayTrailer(trailerUri))
                                        },
                                        modifier =
                                            Modifier.focusPosition(
                                                focusPosition,
                                                ROW_BUTTONS,
                                                trailerCol,
                                            ),
                                    ) {
                                        Icon(
                                            painter = painterResource(id = CoreR.drawable.ic_film),
                                            contentDescription = null,
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(text = stringResource(id = CoreR.string.watch_trailer))
                                    }
                                }
                                Button(
                                    border = ephyraButtonBorder(),
                                    onClick = {
                                        when (movie.played) {
                                            true -> onAction(MovieAction.UnmarkAsPlayed)
                                            false -> onAction(MovieAction.MarkAsPlayed)
                                        }
                                    },
                                    modifier =
                                        Modifier.focusPosition(
                                            focusPosition,
                                            ROW_BUTTONS,
                                            playedCol,
                                        ),
                                ) {
                                    Icon(
                                        painter = painterResource(id = CoreR.drawable.ic_check),
                                        contentDescription = null,
                                        tint =
                                            if (movie.played) MaterialTheme.colorScheme.tertiary
                                            else LocalContentColor.current,
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text =
                                            stringResource(
                                                id =
                                                    if (movie.played) CoreR.string.unmark_as_played
                                                    else CoreR.string.mark_as_played
                                            )
                                    )
                                }
                                Button(
                                    border = ephyraButtonBorder(),
                                    onClick = {
                                        when (movie.favorite) {
                                            true -> onAction(MovieAction.UnmarkAsFavorite)
                                            false -> onAction(MovieAction.MarkAsFavorite)
                                        }
                                    },
                                    modifier =
                                        Modifier.focusPosition(
                                            focusPosition,
                                            ROW_BUTTONS,
                                            favoriteCol,
                                        ),
                                ) {
                                    Icon(
                                        painter =
                                            painterResource(
                                                id =
                                                    if (movie.favorite)
                                                        CoreR.drawable.ic_heart_filled
                                                    else CoreR.drawable.ic_heart
                                            ),
                                        contentDescription = null,
                                        tint =
                                            if (movie.favorite) MaterialTheme.colorScheme.tertiary
                                            else LocalContentColor.current,
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text =
                                            stringResource(
                                                id =
                                                    if (movie.favorite)
                                                        CoreR.string.remove_from_favorites
                                                    else CoreR.string.add_to_favorites
                                            )
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(MaterialTheme.spacings.default))
                            TvCredits(
                                listOf(
                                    stringResource(CoreR.string.genres) to
                                        movie.genres.joinToString(),
                                    stringResource(CoreR.string.director) to
                                        state.director?.name.orEmpty(),
                                    stringResource(CoreR.string.writers) to
                                        state.writers.joinToString { it.name },
                                    stringResource(CoreR.string.item_studios) to
                                        movie.studios.joinToString(),
                                )
                            )
                            if (state.actors.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(MaterialTheme.spacings.large))
                                Text(
                                    text = stringResource(id = CoreR.string.cast_amp_crew),
                                    style = MaterialTheme.typography.headlineMedium,
                                )
                            }
                        }
                    }

                    if (state.actors.isNotEmpty()) {
                        item {
                            CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                                LazyRow(
                                    horizontalArrangement =
                                        Arrangement.spacedBy(MaterialTheme.spacings.default),
                                    contentPadding =
                                        PaddingValues(
                                            horizontal = MaterialTheme.spacings.default * 2
                                        ),
                                    modifier =
                                        Modifier.focusRestorer().onFocusChanged {
                                            if (it.hasFocus) scrollToRow(ROW_CAST)
                                        },
                                ) {
                                    itemsIndexed(state.actors, key = { _, actor -> actor.id }) {
                                        index,
                                        actor ->
                                        PersonCard(
                                            person = actor,
                                            onClick = {
                                                onAction(MovieAction.NavigateToPerson(actor.id))
                                            },
                                            modifier =
                                                Modifier.focusPosition(
                                                    focusPosition,
                                                    ROW_CAST,
                                                    index,
                                                    actor.id.toString(),
                                                ),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // CE (#332): loaded after the page is shown, below everything else.
                    relatedRows.forEach { related ->
                        item(key = related.row) {
                            TvItemsRow(
                                title = stringResource(related.title),
                                items = related.items,
                                direction = related.direction,
                                focusPosition = focusPosition,
                                row = related.row,
                                rowSpec = rowSpec,
                                onFocused = { scrollToRow(lazyIndexOf(related.row)) },
                                onClick = { item ->
                                    onAction(
                                        if (related.row == ROW_EXTRAS) MovieAction.PlayExtra(item.id)
                                        else MovieAction.NavigateToItem(item)
                                    )
                                },
                            )
                        }
                    }
                }
            }

            RestoreFocus(
                position = focusPosition,
                ready =
                    when (focusPosition.row) {
                        ROW_BUTTONS -> true
                        ROW_CAST -> state.actors.isNotEmpty()
                        else -> relatedRows.any { it.row == focusPosition.row }
                    },
            ) { position ->
                position.resolve(ROW_CAST, state.actors.size) { i ->
                    state.actors[i].id.toString()
                }
                relatedRows.forEach { related ->
                    position.resolve(related.row, related.items.size) { i ->
                        related.items[i].id.toString()
                    }
                }
                listState.scrollToItem(lazyIndexOf(position.row))
            }

            versionDialogStartFromBeginning?.let { startFromBeginning ->
                CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                    VersionDialog(
                        versions = versions,
                        onSelect = { mediaSourceId ->
                            versionDialogStartFromBeginning = null
                            onAction(
                                MovieAction.Play(
                                    startFromBeginning = startFromBeginning,
                                    mediaSourceId = mediaSourceId,
                                )
                            )
                        },
                        onDismiss = { versionDialogStartFromBeginning = null },
                    )
                }
            }
        } else {
            val error = state.error
            if (error != null) {
                TvLoadError(
                    error = error,
                    onRetry = { onAction(MovieAction.OnRetryClick) },
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

/** Whole minutes left of a partly watched movie, at least 1, as the season page counts them. */
private fun minutesLeft(movie: FindroidMovie): Long =
    ((movie.runtimeTicks - movie.playbackPositionTicks) / TICKS_PER_MINUTE).coerceAtLeast(1)

private const val TICKS_PER_MINUTE = 600_000_000L

private const val ROW_BUTTONS = 0
private const val ROW_CAST = 1
private const val ROW_EXTRAS = 2
private const val ROW_COLLECTIONS = 3
private const val ROW_SIMILAR = 4

@Preview(device = "id:tv_1080p")
@Composable
private fun MovieScreenLayoutPreview() {
    FindroidTheme {
        MovieScreenLayout(
            state = MovieState(movie = dummyMovie, videoMetadata = dummyVideoMetadata),
            onAction = {},
        )
    }
}
