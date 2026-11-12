package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovies
import dev.jdtech.jellyfin.film.presentation.home.HomeAction
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.WidthClass
import dev.jdtech.jellyfin.presentation.utils.currentWidthClass
import dev.jdtech.jellyfin.presentation.utils.isCompactHeight
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Pages side by side in the Home carousel (docs/tablet/4-design.md §4 and §12), by the window's
 * width class rather than the pager's own width, which is the window minus the rail and the margins
 * and so came out a page short (#438). A window of compact height (a phone in landscape) is
 * Expanded wide but too short for three, so it keeps two.
 */
internal fun carouselPageCount(width: WidthClass, compactHeight: Boolean): Int =
    when (width) {
        WidthClass.Compact -> 1
        WidthClass.Medium -> 2
        WidthClass.Expanded -> if (compactHeight) 2 else 3
    }

/**
 * CE (#398): the pages a keyboard can focus, the ones fully on screen once the pager has settled
 * on [settledPage]. A peeking page, partly or wholly off screen, is not a Tab stop.
 */
internal fun carouselFocusablePages(settledPage: Int, pages: Int, pageCount: Int): IntRange =
    settledPage until minOf(settledPage + pages, pageCount)

/**
 * CE (#398): where Left or Right moves the keyboard focus from [focusedPage], and the page the
 * pager must settle on first so that page is fully on screen; null at either end.
 */
internal fun carouselArrowTarget(
    focusedPage: Int,
    forward: Boolean,
    settledPage: Int,
    pages: Int,
    pageCount: Int,
): Pair<Int, Int>? {
    val target = if (forward) focusedPage + 1 else focusedPage - 1
    if (target !in 0 until pageCount) return null
    val settleOn =
        when {
            target < settledPage -> target
            target >= settledPage + pages -> target - pages + 1
            else -> settledPage
        }
    return target to settleOn
}

private fun carouselPageSize(pages: Int) =
    object : PageSize {
        override fun Density.calculateMainAxisPageSize(availableSpace: Int, pageSpacing: Int): Int =
            (availableSpace - (pages - 1) * pageSpacing) / pages
    }

@Composable
fun HomeCarousel(
    items: List<FindroidItem>,
    itemsPadding: PaddingValues,
    onAction: (HomeAction) -> Unit,
    isParentScrolling: () -> Boolean = { false },
) {
    val pagerState = rememberPagerState(pageCount = { items.size })
    val pagerIsDragged by pagerState.interactionSource.collectIsDraggedAsState()

    val pages = carouselPageCount(currentWidthClass(), isCompactHeight())
    val pageSize = remember(pages) { carouselPageSize(pages) }

    val autoScrollDelay = 5000L

    // #398: the page that has keyboard focus, or -1. While a page has it the carousel stands
    // still, so the focus does not move off screen.
    var focusedPage by remember { mutableStateOf(-1) }
    // The page Left or Right is moving the focus to; focusable while the pager gets there.
    var arrowTarget by remember { mutableStateOf(-1) }
    val requesters = remember { mutableMapOf<Int, FocusRequester>() }
    val scope = rememberCoroutineScope()

    if (!pagerIsDragged) {
        LaunchedEffect(pagerState) {
            while (true) {
                delay(autoScrollDelay)
                // Don't animate a page change on top of the user's scroll of the list below.
                if (isParentScrolling() || focusedPage >= 0) continue
                val nextPage =
                    if (pagerState.canScrollForward) {
                        pagerState.currentPage + 1
                    } else {
                        0
                    }
                pagerState.animateScrollToPage(nextPage)
            }
        }
    }

    HorizontalPager(
        state = pagerState,
        contentPadding = itemsPadding,
        pageSize = pageSize,
        pageSpacing = MaterialTheme.spacings.medium,
        modifier =
            Modifier.onPreviewKeyEvent { event ->
                val forward =
                    when (event.key) {
                        Key.DirectionRight -> true
                        Key.DirectionLeft -> false
                        else -> return@onPreviewKeyEvent false
                    }
                if (focusedPage < 0) return@onPreviewKeyEvent false
                // At either end the focus stays on the page: the default search would jump to a
                // control elsewhere on the screen, such as the bottom bar.
                val (target, settleOn) =
                    carouselArrowTarget(
                        focusedPage = focusedPage,
                        forward = forward,
                        settledPage = pagerState.settledPage,
                        pages = pages,
                        pageCount = items.size,
                    ) ?: return@onPreviewKeyEvent true
                if (event.type == KeyEventType.KeyDown) {
                    arrowTarget = target
                    scope.launch { pageTo(pagerState, settleOn, requesters, target) }
                }
                true
            },
    ) { page ->
        val item = items[page]
        val requester = remember { FocusRequester() }
        DisposableEffect(page) {
            requesters[page] = requester
            onDispose { requesters.remove(page) }
        }
        // The focused page stays focusable while it moves away, so the focus is handed over
        // instead of dropped.
        val focusable =
            page in carouselFocusablePages(pagerState.settledPage, pages, items.size) ||
                page == focusedPage ||
                page == arrowTarget
        HomeCarouselItem(
            item = item,
            onAction = onAction,
            modifier =
                Modifier.focusRequester(requester)
                    .focusProperties { canFocus = focusable }
                    .onFocusChanged { state ->
                        if (state.hasFocus) {
                            focusedPage = page
                            if (arrowTarget == page) arrowTarget = -1
                        } else if (focusedPage == page) {
                            focusedPage = -1
                        }
                    },
        )
    }
}

/** Settles the pager on [settleOn] and then moves the keyboard focus to [target] (#398). */
private suspend fun pageTo(
    pagerState: PagerState,
    settleOn: Int,
    requesters: Map<Int, FocusRequester>,
    target: Int,
) {
    if (pagerState.settledPage != settleOn) {
        pagerState.animateScrollToPage(settleOn)
        snapshotFlow { pagerState.settledPage }.first { it == settleOn }
    }
    // One frame, so the target page is composed and focusable before it is asked to take focus.
    withFrameNanos {}
    requesters[target]?.requestFocus()
}

@Composable
@Preview(showBackground = true)
private fun HomeCarouselPreview() {
    FindroidTheme {
        HomeCarousel(
            items = dummyMovies,
            itemsPadding = PaddingValues(horizontal = 0.dp),
            onAction = {},
        )
    }
}
