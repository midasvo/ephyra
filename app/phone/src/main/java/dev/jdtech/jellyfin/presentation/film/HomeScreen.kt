package dev.jdtech.jellyfin.presentation.film

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyHomeSection
import dev.jdtech.jellyfin.core.presentation.dummy.dummyHomeSuggestions
import dev.jdtech.jellyfin.core.presentation.dummy.dummyHomeView
import dev.jdtech.jellyfin.core.presentation.dummy.dummyServer
import dev.jdtech.jellyfin.core.presentation.ephyra.EphyraMotion
import dev.jdtech.jellyfin.film.presentation.home.HomeAction
import dev.jdtech.jellyfin.film.presentation.home.HomeState
import dev.jdtech.jellyfin.film.presentation.home.HomeViewModel
import dev.jdtech.jellyfin.film.presentation.home.orderedRows
import dev.jdtech.jellyfin.models.FindroidCollection
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.HomeItem
import dev.jdtech.jellyfin.presentation.components.ErrorDialog
import dev.jdtech.jellyfin.presentation.film.components.EmptyState
import dev.jdtech.jellyfin.presentation.film.components.ErrorCardInitialHeight
import dev.jdtech.jellyfin.presentation.film.components.ErrorState
import dev.jdtech.jellyfin.presentation.film.components.HomeCarousel
import dev.jdtech.jellyfin.presentation.film.components.HomeHeader
import dev.jdtech.jellyfin.presentation.film.components.HomeSection
import dev.jdtech.jellyfin.presentation.film.components.HomeView
import dev.jdtech.jellyfin.presentation.film.components.ProfileBottomSheet
import dev.jdtech.jellyfin.presentation.film.components.ServerOrErrorCard
import dev.jdtech.jellyfin.presentation.film.components.ServerSelectionBottomSheet
import dev.jdtech.jellyfin.presentation.film.components.TopContentScrim
import dev.jdtech.jellyfin.presentation.film.components.showsServerOrErrorCard
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.ReloadOnResume
import dev.jdtech.jellyfin.presentation.utils.rememberLazyListStateAtStart
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onLibraryClick: (library: FindroidCollection) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onManageServers: () -> Unit,
    onItemClick: (item: FindroidItem) -> Unit,
    onAddUser: () -> Unit,
    onPublicUserClick: (username: String) -> Unit,
    onUserSwitched: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ReloadOnResume(shouldReload = { viewModel.shouldReload(stopEventFollows = true) }) {
        viewModel.loadData(silent = true)
    }

    HomeScreenLayout(
        state = state,
        baseUrl = JellyfinApi.getInstance(LocalContext.current).api.baseUrl,
        onAddUser = onAddUser,
        onPublicUserClick = onPublicUserClick,
        onUserSwitched = {
            // Home stays alive across a switch, so load the new user's rows now.
            viewModel.loadData()
            onUserSwitched()
        },
        onAction = { action ->
            when (action) {
                is HomeAction.OnItemClick -> onItemClick(action.item)
                is HomeAction.OnLibraryClick -> onLibraryClick(action.library)
                is HomeAction.OnSearchClick -> onSearchClick()
                is HomeAction.OnSettingsClick -> onSettingsClick()
                is HomeAction.OnManageServers -> onManageServers()
                else -> Unit
            }
            viewModel.onAction(action)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreenLayout(
    state: HomeState,
    onAction: (HomeAction) -> Unit,
    baseUrl: String? = null,
    onAddUser: () -> Unit = {},
    onPublicUserClick: (String) -> Unit = {},
    onUserSwitched: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val safePadding = rememberSafePadding(handleStartInsets = false)

    val paddingStart = safePadding.start + MaterialTheme.spacings.default
    val paddingTop = safePadding.top + MaterialTheme.spacings.small
    val paddingEnd = safePadding.end + MaterialTheme.spacings.default
    val paddingBottom = safePadding.bottom + MaterialTheme.spacings.default

    val itemsPadding = PaddingValues(start = paddingStart, end = paddingEnd)

    val scrimHeight = safePadding.top + 88.dp

    // #392: a failed load with rows on screen says so in a card under the header, as My media and
    // Library do, so the header keeps its width and the server name its room. With nothing on
    // screen the list shows ErrorState instead.
    // CE (#281): while the server does not answer, the card is the unreachable banner, also with
    // no failed load at all. A Home with nothing on it and a failed load shows ErrorState, which
    // says the same, so the banner leaves that to it.
    val showErrorState = state.isEmpty && !state.isLoading && state.error != null
    val cardError = state.error.takeIf { !state.isEmpty }
    val showErrorCard = !showErrorState && showsServerOrErrorCard(cardError)
    // CE (#571): the rows start under the card at its real height, which is more than one row with
    // a large font or with the "Allow access" button.
    val density = LocalDensity.current
    var errorCardHeight by remember { mutableStateOf(ErrorCardInitialHeight) }
    val contentPaddingTop by
        animateDpAsState(
            targetValue =
                if (showErrorCard) {
                    safePadding.top + 80.dp + errorCardHeight + MaterialTheme.spacings.medium
                } else {
                    scrimHeight
                },
            label = "content_padding",
        )

    var showErrorDialog by rememberSaveable { mutableStateOf(false) }
    // #440: both sheets open fully. Half the height of a short landscape window hides their last
    // entries (Settings, Manage servers) behind the taskbar.
    val showServerSelectionSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showServerSelectionBottomSheet by remember { mutableStateOf(false) }

    val profileSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showProfileBottomSheet by remember { mutableStateOf(false) }

    // A switch to another user dips the rows to dark and brings the new ones up (VISION §6), so it
    // reads as a change of person rather than a reload. One list, so the scroll state stays shared.
    val userFade = remember { Animatable(1f) }
    var shownUserId by remember { mutableStateOf(state.user?.id) }
    LaunchedEffect(state.user?.id) {
        if (shownUserId == null) {
            // The first user of this screen isn't a switch: a cold start arrives with no user and
            // gets one a moment later, and the rows must not dip for that.
            shownUserId = state.user?.id
        } else if (state.user?.id != shownUserId) {
            shownUserId = state.user?.id
            userFade.animateTo(
                0f,
                tween(EphyraMotion.DURATION_SHORT, easing = EphyraMotion.EasingExit),
            )
            userFade.animateTo(
                1f,
                tween(EphyraMotion.DURATION_MEDIUM, easing = EphyraMotion.EasingEnter),
            )
        }
    }

    // Sections arrive in any order, and a list keeps its first item in place when another lands
    // above it. That would leave the carousel out of view, so stay at the top instead.
    // CE (#142): the rows in the order the user chose, hidden ones left out.
    val rows = remember(state) { state.orderedRows() }
    val listState = rememberLazyListStateAtStart(firstKey = rows.firstOrNull()?.id)

    Box(modifier = Modifier.fillMaxSize().semantics { isTraversalGroup = true }) {
        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = { onAction(HomeAction.OnRetryClick) },
        ) {
            LazyColumn(
                modifier =
                    Modifier.fillMaxSize()
                        .graphicsLayer { alpha = userFade.value }
                        .semantics { traversalIndex = 1f },
                state = listState,
                contentPadding = PaddingValues(top = contentPaddingTop, bottom = paddingBottom),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
            ) {
                items(rows, key = { it.id }, contentType = { it::class }) { row ->
                    when (row) {
                        is HomeItem.Suggestions ->
                            HomeCarousel(
                                items = row.items,
                                itemsPadding = itemsPadding,
                                onAction = onAction,
                                isParentScrolling = { listState.isScrollInProgress },
                            )
                        is HomeItem.Section ->
                            HomeSection(
                                section = row.homeSection,
                                itemsPadding = itemsPadding,
                                onAction = onAction,
                                modifier = Modifier.animateItem(),
                                preferThumb = !state.useEpisodeImages,
                            )
                        is HomeItem.ViewItem ->
                            HomeView(
                                view = row,
                                itemsPadding = itemsPadding,
                                onAction = onAction,
                                modifier = Modifier.animateItem(),
                            )
                        HomeItem.OfflineCard -> Unit
                    }
                }
                if (state.isEmpty && !state.isLoading) {
                    item(key = "placeholder") {
                        val placeholderModifier =
                            Modifier.fillParentMaxWidth().fillParentMaxHeight(0.75f)
                        val error = state.error
                        if (error != null) {
                            ErrorState(
                                error = error,
                                onRetryClick = { onAction(HomeAction.OnRetryClick) },
                                modifier = placeholderModifier,
                            )
                        } else if (state.nothingDownloaded) {
                            // CE: offline, Home's rows are the downloads.
                            EmptyState(
                                icon = CoreR.drawable.ic_download,
                                title = stringResource(CoreR.string.ce_home_offline_empty_title),
                                modifier = placeholderModifier,
                                body = stringResource(CoreR.string.ce_home_offline_empty_body),
                            )
                        } else {
                            EmptyState(
                                icon = CoreR.drawable.ic_home,
                                title = stringResource(CoreR.string.nothing_here_yet),
                                modifier = placeholderModifier,
                                body = stringResource(CoreR.string.home_empty_body),
                            )
                        }
                    }
                }
            }
            TopContentScrim(height = scrimHeight)
        }

        if (showErrorCard) {
            ServerOrErrorCard(
                error = cardError,
                onShowStacktrace = { showErrorDialog = true },
                onRetryClick = { onAction(HomeAction.OnRetryClick) },
                modifier =
                    Modifier.padding(
                            start = paddingStart,
                            top = safePadding.top + 80.dp,
                            end = paddingEnd,
                        )
                        .onSizeChanged { errorCardHeight = with(density) { it.height.toDp() } },
            )
        }

        if (state.error != null && showErrorDialog) {
            ErrorDialog(
                exception = state.error!!,
                onDismissRequest = { showErrorDialog = false },
                onRetry = { onAction(HomeAction.OnRetryClick) },
            )
        }
    }

    HomeHeader(
        serverName = state.server?.name ?: "",
        onServerClick = { showServerSelectionBottomSheet = true },
        onSearchClick = { onAction(HomeAction.OnSearchClick) },
        onUserClick = { showProfileBottomSheet = true },
        user = state.user,
        baseUrl = baseUrl,
        modifier = Modifier.padding(start = paddingStart, top = paddingTop, end = paddingEnd),
    )

    if (showProfileBottomSheet) {
        ProfileBottomSheet(
            baseUrl = baseUrl,
            onSwitchServer = {
                showProfileBottomSheet = false
                showServerSelectionBottomSheet = true
            },
            onPublicUserClick = { username ->
                showProfileBottomSheet = false
                onPublicUserClick(username)
            },
            onAddUser = {
                showProfileBottomSheet = false
                onAddUser()
            },
            onSettings = {
                showProfileBottomSheet = false
                onAction(HomeAction.OnSettingsClick)
            },
            onUserSwitched = {
                showProfileBottomSheet = false
                onUserSwitched()
            },
            onDismissRequest = { showProfileBottomSheet = false },
            sheetState = profileSheetState,
        )
    }

    if (showServerSelectionBottomSheet) {
        ServerSelectionBottomSheet(
            currentServerId = state.server?.id ?: "",
            onUpdate = {
                onAction(HomeAction.OnRetryClick)
                scope
                    .launch { showServerSelectionSheetState.hide() }
                    .invokeOnCompletion {
                        if (!showServerSelectionSheetState.isVisible) {
                            showServerSelectionBottomSheet = false
                        }
                    }
            },
            onManage = {
                onAction(HomeAction.OnManageServers)
                scope.launch { showServerSelectionSheetState.hide() }
            },
            onDismissRequest = { showServerSelectionBottomSheet = false },
            sheetState = showServerSelectionSheetState,
        )
    }
}

@PreviewScreenSizes
@Composable
private fun HomeScreenLayoutPreview() {
    FindroidTheme {
        HomeScreenLayout(
            state =
                HomeState(
                    server = dummyServer,
                    suggestionsSection = dummyHomeSuggestions,
                    resumeSection = dummyHomeSection,
                    views = listOf(dummyHomeView),
                    isLoading = false,
                    error = Exception("Failed to load data"),
                ),
            onAction = {},
        )
    }
}
