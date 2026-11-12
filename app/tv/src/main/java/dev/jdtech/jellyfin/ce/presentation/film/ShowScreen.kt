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
import dev.jdtech.jellyfin.ce.thememusic.rememberThemeMusic
import dev.jdtech.jellyfin.ce.ui.PersonCard
import dev.jdtech.jellyfin.ce.ui.components.TvLoadError
import dev.jdtech.jellyfin.ce.ui.components.TvOverviewText
import dev.jdtech.jellyfin.ce.ui.focus.NoBringIntoView
import dev.jdtech.jellyfin.ce.ui.focus.RestoreFocus
import dev.jdtech.jellyfin.ce.ui.focus.ephyraButtonBorder
import dev.jdtech.jellyfin.ce.ui.focus.focusPosition
import dev.jdtech.jellyfin.ce.ui.focus.rememberFocusPosition
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.core.presentation.dummy.dummyShow
import dev.jdtech.jellyfin.film.presentation.show.ShowAction
import dev.jdtech.jellyfin.film.presentation.show.ShowState
import dev.jdtech.jellyfin.film.presentation.show.ShowViewModel
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.ui.components.Direction
import dev.jdtech.jellyfin.ui.components.ItemCard
import dev.jdtech.jellyfin.models.airSchedule
import dev.jdtech.jellyfin.utils.AfterFirstFrame
import dev.jdtech.jellyfin.utils.getShowDateString
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * CE replacement of the upstream TV show page (ADR 0001). Back from a season or person focuses that
 * card again, without scrolling to the top (ADR 0003).
 */
@Composable
fun ShowScreen(
    showId: UUID,
    navigateToItem: (item: FindroidItem) -> Unit,
    navigateToPlayer: (itemId: UUID, shuffle: Boolean) -> Unit,
    navigateToPerson: (personId: UUID) -> Unit,
    navigateToExtra: (extraId: UUID) -> Unit = {},
    viewModel: ShowViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) {
        if (viewModel.shouldReload(showId)) viewModel.loadShow(showId)
        viewModel.loadThemeSong(showId)
    }
    val themeMusic = rememberThemeMusic(state.themeSongUrl)

    // CE (#332): the rows below the page only once the show is on screen.
    AfterFirstFrame(state.show?.id) { id -> viewModel.loadRelated(id) }

    ShowScreenLayout(
        state = state,
        onAction = { action ->
            when (action) {
                is ShowAction.Play -> {
                    themeMusic.onPlaybackStarted()
                    navigateToPlayer(showId, false)
                }
                is ShowAction.Shuffle -> {
                    themeMusic.onPlaybackStarted()
                    navigateToPlayer(showId, true)
                }
                is ShowAction.PlayTrailer -> {
                    themeMusic.onPlaybackStarted()
                    try {
                        uriHandler.openUri(action.trailer)
                    } catch (e: IllegalArgumentException) {
                        Toast.makeText(context, e.localizedMessage, Toast.LENGTH_SHORT).show()
                    }
                }
                is ShowAction.PlayExtra -> {
                    themeMusic.onPlaybackStarted()
                    navigateToExtra(action.extraId)
                }
                is ShowAction.NavigateToItem -> navigateToItem(action.item)
                is ShowAction.NavigateToPerson -> navigateToPerson(action.personId)
                else -> Unit
            }
            viewModel.onAction(action)
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShowScreenLayout(state: ShowState, onAction: (ShowAction) -> Unit) {
    // CE (#514): the page scrolls itself when a row gets focus, so moving along the buttons leaves
    // the title on screen. The rows keep the default.
    val rowSpec = LocalBringIntoViewSpec.current
    CompositionLocalProvider(LocalBringIntoViewSpec provides NoBringIntoView) {
        ShowScreenContent(state = state, onAction = onAction, rowSpec = rowSpec)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShowScreenContent(
    state: ShowState,
    onAction: (ShowAction) -> Unit,
    rowSpec: BringIntoViewSpec,
) {
    val configuration = LocalConfiguration.current
    val locale = configuration.locales.get(0)

    // Default focus: the Play button. After Back from a season/person: that card.
    val focusPosition = rememberFocusPosition(row = ROW_BUTTONS, column = 0)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // The list follows the focused row (it has no bring-into-view of its own, #514). Wait a frame
    // first, so the row's focus change has settled before the list scrolls.
    val scrollToRow: (Int) -> Unit = { row ->
        scope.launch {
            withFrameNanos {}
            listState.animateScrollToItem(row)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        state.show?.let { show ->
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
                                ROW_SIMILAR,
                                CoreR.string.item_more_like_this,
                                Direction.VERTICAL,
                                state.related.similar,
                            ),
                        )
                        .filter { it.items.isNotEmpty() }
                }
            // The list holds the header, the seasons and the cast when there are any, then these.
            val firstRelatedIndex =
                1 + (if (state.seasons.isNotEmpty()) 1 else 0) + (if (state.actors.isNotEmpty()) 1 else 0)
            var size by remember { mutableStateOf(Size.Zero) }
            Box(
                modifier =
                    Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                        size = coordinates.size.toSize()
                    }
            ) {
                AsyncImage(
                    model = rememberDetailBackdrop(show.images.backdrop),
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
                    // the buttons can't reach the seasons row while that row is below the screen.
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
                            Text(text = show.name, style = MaterialTheme.typography.displayMedium)
                            if (show.originalTitle != show.name) {
                                show.originalTitle?.let { originalTitle ->
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
                                Text(
                                    text = getShowDateString(show),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Text(
                                    text =
                                        stringResource(
                                            CoreR.string.runtime_minutes,
                                            show.runtimeTicks.div(600000000),
                                        ),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                show.officialRating?.let {
                                    Text(text = it, style = MaterialTheme.typography.labelMedium)
                                }
                                show.communityRating?.let {
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
                                TvCriticRating(show.criticRating)
                            }
                            show.airSchedule(locale, stringResource(CoreR.string.item_airs_daily))?.let { schedule ->
                                Spacer(modifier = Modifier.height(MaterialTheme.spacings.extraSmall))
                                Text(
                                    text = stringResource(CoreR.string.item_airs, schedule),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = .7f),
                                )
                            }
                            Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))
                            TvTagline(show.tagline)
                            // CE (#545): a cut overview takes focus and opens in full on OK.
                            TvOverviewText(
                                title = show.name,
                                text = show.overview,
                                maxLines = 4,
                                modifier = Modifier.width(640.dp),
                            )
                            Spacer(modifier = Modifier.height(MaterialTheme.spacings.default))
                            Row(
                                horizontalArrangement =
                                    Arrangement.spacedBy(MaterialTheme.spacings.medium),
                                // A plain Row needs focusGroup() for focusRestorer() to work.
                                modifier = Modifier.focusRestorer().focusGroup(),
                            ) {
                                Button(
                                    border = ephyraButtonBorder(),
                                    onClick = { onAction(ShowAction.Play()) },
                                    modifier =
                                        Modifier.focusPosition(focusPosition, ROW_BUTTONS, 0),
                                ) {
                                    Icon(
                                        painter = painterResource(id = CoreR.drawable.ic_play),
                                        contentDescription = null,
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(text = stringResource(id = CoreR.string.play))
                                }
                                Button(
                                    border = ephyraButtonBorder(),
                                    onClick = { onAction(ShowAction.Shuffle) },
                                    modifier =
                                        Modifier.focusPosition(focusPosition, ROW_BUTTONS, 1),
                                ) {
                                    Icon(
                                        painter = painterResource(id = CoreR.drawable.ic_shuffle),
                                        contentDescription = null,
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(text = stringResource(id = CoreR.string.shuffle))
                                }
                                show.trailer?.let { trailerUri ->
                                    Button(
                                        border = ephyraButtonBorder(),
                                        onClick = { onAction(ShowAction.PlayTrailer(trailerUri)) },
                                        modifier =
                                            Modifier.focusPosition(focusPosition, ROW_BUTTONS, 2),
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
                                        when (show.played) {
                                            true -> onAction(ShowAction.UnmarkAsPlayed)
                                            false -> onAction(ShowAction.MarkAsPlayed)
                                        }
                                    },
                                    modifier =
                                        Modifier.focusPosition(focusPosition, ROW_BUTTONS, 3),
                                ) {
                                    Icon(
                                        painter = painterResource(id = CoreR.drawable.ic_check),
                                        contentDescription = null,
                                        tint =
                                            if (show.played) MaterialTheme.colorScheme.tertiary
                                            else LocalContentColor.current,
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text =
                                            stringResource(
                                                id =
                                                    if (show.played) CoreR.string.unmark_as_played
                                                    else CoreR.string.mark_as_played
                                            )
                                    )
                                }
                                Button(
                                    border = ephyraButtonBorder(),
                                    onClick = {
                                        when (show.favorite) {
                                            true -> onAction(ShowAction.UnmarkAsFavorite)
                                            false -> onAction(ShowAction.MarkAsFavorite)
                                        }
                                    },
                                    modifier =
                                        Modifier.focusPosition(focusPosition, ROW_BUTTONS, 4),
                                ) {
                                    Icon(
                                        painter =
                                            painterResource(
                                                id =
                                                    if (show.favorite)
                                                        CoreR.drawable.ic_heart_filled
                                                    else CoreR.drawable.ic_heart
                                            ),
                                        contentDescription = null,
                                        tint =
                                            if (show.favorite) MaterialTheme.colorScheme.tertiary
                                            else LocalContentColor.current,
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text =
                                            stringResource(
                                                id =
                                                    if (show.favorite)
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
                                        show.genres.joinToString(),
                                    stringResource(CoreR.string.director) to
                                        state.director?.name.orEmpty(),
                                    stringResource(CoreR.string.writers) to
                                        state.writers.joinToString { it.name },
                                    stringResource(CoreR.string.item_networks) to
                                        show.studios.joinToString(),
                                )
                            )
                            if (state.seasons.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(MaterialTheme.spacings.large))
                                Text(
                                    text = stringResource(id = CoreR.string.seasons),
                                    style = MaterialTheme.typography.headlineMedium,
                                )
                            }
                        }
                    }
                    if (state.seasons.isNotEmpty()) {
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
                                            if (it.hasFocus) scrollToRow(ROW_SEASONS)
                                        },
                                ) {
                                    itemsIndexed(state.seasons, key = { _, season -> season.id }) {
                                        index,
                                        season ->
                                        ItemCard(
                                            item = season,
                                            direction = Direction.VERTICAL,
                                            onClick = {
                                                onAction(ShowAction.NavigateToItem(season))
                                            },
                                            modifier =
                                                Modifier.focusPosition(
                                                    focusPosition,
                                                    ROW_SEASONS,
                                                    index,
                                                    season.id.toString(),
                                                ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (state.actors.isNotEmpty()) {
                        item {
                            Column {
                                Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))
                                Text(
                                    text = stringResource(id = CoreR.string.cast_amp_crew),
                                    style = MaterialTheme.typography.headlineMedium,
                                    modifier =
                                        Modifier.padding(
                                            horizontal = MaterialTheme.spacings.default * 2
                                        ),
                                )
                                Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))
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
                                                if (it.hasFocus) {
                                                    scrollToRow(
                                                        if (state.seasons.isNotEmpty()) 2 else 1
                                                    )
                                                }
                                            },
                                    ) {
                                        itemsIndexed(state.actors, key = { _, actor -> actor.id }) {
                                            index,
                                            actor ->
                                            PersonCard(
                                                person = actor,
                                                onClick = {
                                                    onAction(ShowAction.NavigateToPerson(actor.id))
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
                                onFocused = {
                                    relatedRows.relatedLazyIndex(related.row, firstRelatedIndex)?.let {
                                        scrollToRow(it)
                                    }
                                },
                                onClick = { item ->
                                    onAction(
                                        if (related.row == ROW_EXTRAS) ShowAction.PlayExtra(item.id)
                                        else ShowAction.NavigateToItem(item)
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
                        ROW_EXTRAS,
                        ROW_SIMILAR -> relatedRows.any { it.row == focusPosition.row }
                        else ->
                            state.seasons.isNotEmpty() ||
                                state.actors.isNotEmpty() ||
                                focusPosition.row == ROW_BUTTONS
                    },
            ) { position ->
                relatedRows.relatedLazyIndex(position.row, firstRelatedIndex)?.let { index ->
                    relatedRows.forEach { related ->
                        position.resolve(related.row, related.items.size) { i ->
                            related.items[i].id.toString()
                        }
                    }
                    listState.scrollToItem(index)
                }
                if (position.row == ROW_SEASONS) {
                    position.resolve(ROW_SEASONS, state.seasons.size) { i ->
                        state.seasons[i].id.toString()
                    }
                    listState.scrollToItem(ROW_SEASONS)
                } else if (position.row == ROW_CAST) {
                    position.resolve(ROW_CAST, state.actors.size) { i ->
                        state.actors[i].id.toString()
                    }
                    listState.scrollToItem(if (state.seasons.isNotEmpty()) 2 else 1)
                }
            }
        }
            ?: run {
                val error = state.error
                if (error != null) {
                    TvLoadError(
                        error = error,
                        onRetry = { onAction(ShowAction.OnRetryClick) },
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }
    }
}

private const val ROW_BUTTONS = 0
private const val ROW_SEASONS = 1
private const val ROW_CAST = 2
private const val ROW_EXTRAS = 3
private const val ROW_SIMILAR = 4

@Preview(device = "id:tv_1080p")
@Composable
private fun ShowScreenLayoutPreview() {
    FindroidTheme {
        ShowScreenLayout(state = ShowState(show = dummyShow, nextUp = dummyEpisode), onAction = {})
    }
}
