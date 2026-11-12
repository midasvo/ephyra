package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.R as FilmR
import dev.jdtech.jellyfin.film.presentation.search.SearchAction
import dev.jdtech.jellyfin.film.presentation.search.SearchState
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.GridCellsAdaptiveWithMinColumns
import dev.jdtech.jellyfin.presentation.utils.Layout
import dev.jdtech.jellyfin.presentation.utils.currentWidthClass
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun FilmSearchBar(
    state: SearchState,
    expanded: Boolean,
    onExpand: (Boolean) -> Unit,
    onAction: (SearchAction) -> Unit,
    modifier: Modifier = Modifier,
    paddingStart: Dp = 0.dp,
    paddingEnd: Dp = 0.dp,
    docked: Boolean = false,
) {
    val focusRequester = remember { FocusRequester() }
    val safePadding = rememberSafePadding()

    var query by rememberSaveable { mutableStateOf("") }

    val searchBarPaddingStart by
        animateDpAsState(
            targetValue = if (expanded) 0.dp else paddingStart,
            label = "search_bar_padding_start",
        )

    val searchBarPaddingEnd by
        animateDpAsState(
            targetValue = if (expanded) 0.dp else paddingEnd,
            label = "search_bar_padding_end",
        )

    // #439: docked, the open field stays where the collapsed bar was and keeps a readable width;
    // the results below it fill the content area.
    val searchBarInputPaddingStart by
        animateDpAsState(
            targetValue =
                when {
                    !expanded -> 0.dp
                    docked -> paddingStart
                    else -> safePadding.start
                },
            label = "search_bar_padding_start",
        )

    val searchBarInputPaddingEnd by
        animateDpAsState(
            targetValue =
                when {
                    !expanded -> 0.dp
                    docked -> paddingEnd
                    else -> safePadding.end
                },
            label = "search_bar_padding_end",
        )

    // Focus the field when the search opens, not again when it is restored open after a rotation
    // or theme switch (#387): that would bring the keyboard up over the results.
    var focusedForExpansion by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded && !focusedForExpansion) {
            focusRequester.requestFocus()
        }
        focusedForExpansion = expanded
    }

    LaunchedEffect(query) { onAction(SearchAction.Search(query)) }

    // #430: Material 3's field asks to expand whenever it gains focus, so Tab opened the
    // full-screen search and never reached the library cards. Focus from the keyboard leaves the
    // bar closed; a tap or click, typing and Enter open it.
    val inputModeManager = LocalInputModeManager.current
    val interactionSource = remember { MutableInteractionSource() }
    val currentExpanded by rememberUpdatedState(expanded)
    val currentOnExpand by rememberUpdatedState(onExpand)
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            // Covers a mouse click, and a tap after Tab left the app in keyboard mode.
            if (interaction is PressInteraction.Press && !currentExpanded) currentOnExpand(true)
        }
    }
    val focused by interactionSource.collectIsFocusedAsState()
    // The same M3 focus state layer the cards and the rail show.
    val focusStateLayer = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    val focusShape = SearchBarDefaults.inputFieldShape

    SearchBar(
        inputField = {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = {
                    query = it
                    if (!expanded && it.isNotEmpty()) onExpand(true)
                },
                onSearch = { onExpand(true) },
                expanded = expanded,
                onExpandedChange = {
                    if (!it || inputModeManager.inputMode == InputMode.Touch) onExpand(it)
                },
                modifier =
                    Modifier.padding(
                            start = searchBarInputPaddingStart,
                            end = searchBarInputPaddingEnd,
                        )
                        // The open bar measures the field at its full width; take that slot
                        // and keep the field at the start of it.
                        .then(
                            if (docked && expanded) {
                                Modifier.wrapContentWidth(Alignment.Start)
                                    .widthIn(max = Layout.textMaxWidth)
                                    .fillMaxWidth()
                            } else {
                                Modifier
                            }
                        )
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                            if (!expanded && isEnter) {
                                if (event.type == KeyEventType.KeyDown) onExpand(true)
                                true
                            } else {
                                false
                            }
                        }
                        .drawWithContent {
                            drawContent()
                            if (focused && !expanded) {
                                drawOutline(
                                    focusShape.createOutline(size, layoutDirection, this),
                                    focusStateLayer,
                                )
                            }
                        },
                interactionSource = interactionSource,
                placeholder = {
                    Text(
                        text = stringResource(FilmR.string.search_placeholder),
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                },
                leadingIcon = {
                    AnimatedContent(targetState = expanded, label = "search_to_back") {
                        targetExpanded ->
                        if (targetExpanded) {
                            IconButton(onClick = { onExpand(false) }) {
                                Icon(
                                    painter = painterResource(CoreR.drawable.ic_arrow_left),
                                    contentDescription = stringResource(CoreR.string.navigate_back),
                                )
                            }
                        } else {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_search),
                                contentDescription = null,
                            )
                        }
                    }
                },
                trailingIcon = {
                    if (state.loading) {
                        Box(modifier = Modifier.size(32.dp)) { CircularProgressIndicator() }
                    } else if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_x),
                                contentDescription = null,
                            )
                        }
                    }
                },
            )
        },
        expanded = expanded,
        onExpandedChange = { onExpand(it) },
        modifier = modifier.padding(start = searchBarPaddingStart, end = searchBarPaddingEnd),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCellsAdaptiveWithMinColumns(minSize = Layout.gridMinCell(currentWidthClass()), minColumns = 2),
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        start = safePadding.start + MaterialTheme.spacings.default,
                        top = MaterialTheme.spacings.default,
                        end = safePadding.end + MaterialTheme.spacings.default,
                        bottom = safePadding.bottom + MaterialTheme.spacings.default,
                    ),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
            ) {
                items(items = state.items, key = { it.id }) { item ->
                    ItemCard(
                        item = item,
                        direction = Direction.VERTICAL,
                        onClick = { onAction(SearchAction.OnItemClick(item)) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            // state.query trails query during the debounce, so this can't flash while typing.
            if (query.isNotBlank() && query == state.query && !state.loading) {
                val contentModifier = Modifier.fillMaxSize().imePadding()
                val error = state.error
                if (error != null) {
                    ErrorState(
                        error = error,
                        onRetryClick = { onAction(SearchAction.Search(query)) },
                        modifier = contentModifier,
                    )
                } else if (state.items.isEmpty()) {
                    EmptyState(
                        icon = CoreR.drawable.ic_search,
                        title = stringResource(CoreR.string.no_search_results),
                        modifier = contentModifier,
                    )
                }
            }
        }
    }
}
