package dev.jdtech.jellyfin.ce.presentation.film

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.ce.ui.TvSafeArea
import dev.jdtech.jellyfin.ce.ui.components.TvLoadError
import dev.jdtech.jellyfin.ce.ui.components.TvOverviewText
import dev.jdtech.jellyfin.ce.ui.focus.FocusPosition
import dev.jdtech.jellyfin.ce.ui.focus.NoBringIntoView
import dev.jdtech.jellyfin.ce.ui.focus.RestoreFocus
import dev.jdtech.jellyfin.ce.ui.focus.focusPosition
import dev.jdtech.jellyfin.ce.ui.focus.rememberFocusPosition
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovies
import dev.jdtech.jellyfin.core.presentation.dummy.dummyPersonDetail
import dev.jdtech.jellyfin.film.presentation.person.PersonState
import dev.jdtech.jellyfin.film.presentation.person.PersonViewModel
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.ui.components.Direction
import dev.jdtech.jellyfin.ui.components.ItemCard
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * CE person screen on Android TV (#136, ADR 0001). Shows photo, bio and the person's movies and
 * shows as rows. Back from an item restores focus to that card (ADR 0003).
 */
@Composable
fun PersonScreen(
    personId: UUID,
    navigateToMovie: (itemId: UUID) -> Unit,
    navigateToShow: (itemId: UUID) -> Unit,
    navigateToEpisode: (episodeId: UUID) -> Unit,
    viewModel: PersonViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(personId) { viewModel.loadPerson(personId) }

    PersonScreenLayout(
        state = state,
        onRetryClick = { viewModel.loadPerson(personId) },
        onItemClick = { item ->
            when (item) {
                is FindroidMovie -> navigateToMovie(item.id)
                is FindroidShow -> navigateToShow(item.id)
                is FindroidEpisode -> navigateToEpisode(item.id)
                else -> Unit
            }
        },
    )
}

internal data class PersonMediaSection(
    val id: String,
    val titleRes: Int,
    val items: List<FindroidItem>,
)

internal fun personMediaCardTag(id: UUID) = "ce_person_media_card_$id"

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PersonScreenLayout(
    state: PersonState,
    onRetryClick: () -> Unit,
    onItemClick: (FindroidItem) -> Unit,
    focusPosition: FocusPosition = rememberFocusPosition(row = 0, column = 0),
) {
    val sections =
        remember(state.starredInMovies, state.starredInShows) {
            buildList {
                if (state.starredInMovies.isNotEmpty()) {
                    add(
                        PersonMediaSection(
                            id = "movies",
                            titleRes = CoreR.string.movies_label,
                            items = state.starredInMovies,
                        )
                    )
                }
                if (state.starredInShows.isNotEmpty()) {
                    add(
                        PersonMediaSection(
                            id = "shows",
                            titleRes = CoreR.string.shows_label,
                            items = state.starredInShows,
                        )
                    )
                }
            }
        }

    val listState = rememberLazyListState()
    // The header (item 0) has nothing to focus: the first section scrolls to it, so the name and
    // biography stay on screen (#407). The other sections scroll to their own row.
    val lazyIndexOf: (Int) -> Int = { sectionIndex ->
        if (sectionIndex == 0) 0 else 1 + sectionIndex
    }
    val scope = rememberCoroutineScope()
    val rowSpec = LocalBringIntoViewSpec.current
    val scrollToRow: (Int) -> Unit = { row ->
        scope.launch {
            withFrameNanos {}
            listState.animateScrollToItem(row)
        }
    }

    val person = state.person
    val error = state.error

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading && person == null -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            error != null && person == null -> {
                TvLoadError(
                    error = error,
                    onRetry = onRetryClick,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            person != null -> {
                // The page scrolls itself when a section gets focus. Without this, moving along the
                // first row would also scroll the header off (#407). The rows keep the default.
                CompositionLocalProvider(LocalBringIntoViewSpec provides NoBringIntoView) {
                    LazyColumn(
                        state = listState,
                        contentPadding =
                            PaddingValues(
                                top = TvSafeArea.vertical,
                                bottom = MaterialTheme.spacings.large,
                            ),
                        // CE (#508): medium under the header, so the first row fits under it.
                        // The sections add the rest of large between them.
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        item(key = "header") {
                            // CE (#508): a fixed height, so the whole first row (card, title and
                            // year) fits on screen under it. The biography gets the lines that fit
                            // beside the photo and opens in full on OK.
                            Row(
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .height(PersonHeaderHeight)
                                        .padding(horizontal = TvSafeArea.horizontal),
                                horizontalArrangement =
                                    Arrangement.spacedBy(MaterialTheme.spacings.large),
                            ) {
                                val imageUri = person.images.primary
                                var failed by remember(imageUri) { mutableStateOf(false) }
                                Box(
                                    modifier =
                                        Modifier.height(PersonHeaderHeight)
                                            .aspectRatio(0.66f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (imageUri == null || failed) {
                                        Icon(
                                            painter = painterResource(CoreR.drawable.ic_user),
                                            contentDescription = null,
                                            modifier = Modifier.size(64.dp),
                                            tint =
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                    alpha = 0.6f
                                                ),
                                        )
                                    } else {
                                        AsyncImage(
                                            model = imageUri,
                                            contentDescription =
                                                stringResource(
                                                    CoreR.string.person_image_description,
                                                    person.name,
                                                ),
                                            contentScale = ContentScale.Crop,
                                            onError = { failed = true },
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement =
                                        Arrangement.spacedBy(MaterialTheme.spacings.small),
                                ) {
                                    Text(
                                        text = person.name,
                                        style = MaterialTheme.typography.displayMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (person.overview.isNotBlank()) {
                                        PersonBiography(
                                            name = person.name,
                                            biography = person.overview,
                                            modifier =
                                                Modifier.weight(1f, fill = false)
                                                    .fillMaxWidth(0.9f),
                                        )
                                    }
                                }
                            }
                        }

                        if (sections.isEmpty()) {
                            item(key = "empty_focus_target") {
                                Box(
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .height(1.dp)
                                            .focusPosition(focusPosition, 0, 0)
                                            .focusable()
                                )
                            }
                        }

                        itemsIndexed(sections, key = { _, section -> section.id }) {
                            sectionIndex,
                            section ->
                            Column(
                                modifier =
                                    Modifier.padding(
                                        top =
                                            if (sectionIndex == 0) 0.dp
                                            else
                                                MaterialTheme.spacings.large -
                                                    MaterialTheme.spacings.medium
                                    )
                            ) {
                                Text(
                                    text = stringResource(section.titleRes),
                                    style = MaterialTheme.typography.headlineMedium,
                                    modifier =
                                        Modifier.padding(horizontal = TvSafeArea.horizontal),
                                )
                                Spacer(modifier = Modifier.height(MaterialTheme.spacings.small))
                                CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                                    LazyRow(
                                        horizontalArrangement =
                                            Arrangement.spacedBy(MaterialTheme.spacings.default),
                                        contentPadding =
                                            PaddingValues(horizontal = TvSafeArea.horizontal),
                                        modifier =
                                            Modifier.focusRestorer().onFocusChanged {
                                                if (it.hasFocus) {
                                                    scrollToRow(lazyIndexOf(sectionIndex))
                                                }
                                            },
                                    ) {
                                        itemsIndexed(section.items, key = { _, item -> item.id }) {
                                            columnIndex,
                                            item ->
                                            ItemCard(
                                                item = item,
                                                direction = Direction.VERTICAL,
                                                showYear = true,
                                                // CE (#508): one line, as on Home (#423), so
                                                // the first row fits under the header.
                                                titleMaxLines = 1,
                                                onClick = { onItemClick(item) },
                                                modifier =
                                                    Modifier.focusPosition(
                                                            focusPosition,
                                                            row = sectionIndex,
                                                            column = columnIndex,
                                                            key = item.id.toString(),
                                                        )
                                                        .testTag(personMediaCardTag(item.id)),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                RestoreFocus(
                    position = focusPosition,
                    ready = !state.isLoading,
                ) { position ->
                    if (sections.isEmpty()) return@RestoreFocus
                    val sectionIndex = position.row.coerceIn(0, sections.lastIndex)
                    val section = sections[sectionIndex]
                    position.resolve(sectionIndex, section.items.size) { i ->
                        section.items.getOrNull(i)?.id?.toString()
                    }
                    listState.scrollToItem(lazyIndexOf(position.row))
                }
            }
        }
    }
}

/** The header's height, and the photo's: low enough for the first row to fit under it (#508). */
private val PersonHeaderHeight = 128.dp

internal const val PERSON_BIOGRAPHY_TAG = "ce_person_biography"

/**
 * The biography beside the photo, cut to the lines that fit (#508). Only when it is cut, it takes
 * focus with the Ephyra ring and OK opens the full text; Back from the dialog puts focus on it
 * again ([TvOverviewText], shared with the item pages since #545).
 */
@Composable
private fun PersonBiography(name: String, biography: String, modifier: Modifier = Modifier) {
    TvOverviewText(
        title = name,
        text = biography,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
        testTag = PERSON_BIOGRAPHY_TAG,
        modifier = modifier,
    )
}

@Preview(device = "id:tv_1080p")
@Composable
private fun PersonScreenLayoutPreview() {
    FindroidTheme {
        PersonScreenLayout(
            state = PersonState(person = dummyPersonDetail, starredInMovies = dummyMovies),
            onRetryClick = {},
            onItemClick = {},
        )
    }
}
