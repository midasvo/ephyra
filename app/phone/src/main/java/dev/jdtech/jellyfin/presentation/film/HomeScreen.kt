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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyHomeSection
import dev.jdtech.jellyfin.core.presentation.dummy.dummyHomeSuggestions
import dev.jdtech.jellyfin.core.presentation.dummy.dummyHomeView
import dev.jdtech.jellyfin.core.presentation.dummy.dummyServer
import dev.jdtech.jellyfin.core.presentation.ephyra.EphyraMotion
import dev.jdtech.jellyfin.core.presentation.rows.rememberLazyListStateAtStart
import dev.jdtech.jellyfin.core.presentation.session.LocalServerSession
import dev.jdtech.jellyfin.domain.ports.ServerSession
import dev.jdtech.jellyfin.film.presentation.home.HomeAction
import dev.jdtech.jellyfin.film.presentation.home.HomeState
import dev.jdtech.jellyfin.film.presentation.home.HomeViewModel
import dev.jdtech.jellyfin.film.presentation.home.orderedRows
import dev.jdtech.jellyfin.models.HomeItem
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.Library
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.toServerId
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
import dev.jdtech.jellyfin.presentation.utils.LocalServerStatus
import dev.jdtech.jellyfin.presentation.utils.ReloadWhenServerAwayChanges
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding
import dev.jdtech.jellyfin.presentation.utils.showsServerIndicator
import dev.jdtech.jellyfin.utils.ReloadOnResume
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onLibraryClick: (library: Library) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onManageServers: () -> Unit,
    onItemClick: (item: Item) -> Unit,
    onAddUser: () -> Unit,
    onPublicUserClick: (username: String) -> Unit,
    onUserSwitched: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // CE (#810): never a frame of the previous user's rows after a switch.
    val sessionUser = rememberSessionUser()

    val serverAway = LocalServerStatus.current.unreachable
    ReloadWhenServerAwayChanges { away -> viewModel.onServerAwayChanged(away) }

    ReloadOnResume(
        shouldReload = {
            viewModel.onServerAwayChanged(serverAway)
            viewModel.shouldReload(stopEventFollows = true)
        }
    ) {
        viewModel.loadData(silent = true)
    }

    HomeScreenLayout(
        state = state.shownTo(sessionUser.userId),
        onAddUser = onAddUser,
        onPublicUserClick = onPublicUserClick,
        // CE (#810): the ViewModel follows the current user and loads the new user's rows itself.
        onUserSwitched = {
            // CE (#872): Home stays on screen through this switch; it reads the user again now.
            sessionUser.recheck()
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

/**
 * CE (#872): the user the client acts for, as Home reads it. [ServerSession.userId] is no snapshot
 * state, so a switch does not recompose Home by itself. A switch on the profile sheet keeps Home on
 * screen, and until the ViewModel heard of it from Room, a couple of frames later, Home went on
 * showing the previous user's rows. [recheck] makes the readers of [userId] read it again.
 */
@Stable
internal class SessionUser(private val session: ServerSession) {
    private var checks by mutableIntStateOf(0)

    /** Read fresh each time; reading [checks] makes a [recheck] recompose the reader. */
    val userId: UserId?
        get() = session.userId.also { checks }

    fun recheck() {
        checks++
    }
}

@Composable
internal fun rememberSessionUser(): SessionUser {
    val session = LocalServerSession.current
    return remember(session) { SessionUser(session) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreenLayout(
    state: HomeState,
    onAction: (HomeAction) -> Unit,
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
    // CE (#775): an empty Home keeps the banner; only over rows can it be put away.
    val showErrorCard =
        !showErrorState && showsServerOrErrorCard(cardError, hasContent = !state.isEmpty)
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
        // CE (#810): no user is no one to switch to. During a switch Home can show no user for a
        // moment (HomeState.shownTo); the switch is from the previous user to the next one.
        val userId = state.user?.id ?: return@LaunchedEffect
        if (shownUserId == null) {
            // The first user of this screen isn't a switch: a cold start arrives with no user and
            // gets one a moment later, and the rows must not dip for that.
            shownUserId = userId
        } else if (userId != shownUserId) {
            shownUserId = userId
            userFade.animateTo(
                0f,
                tween(EphyraMotion.DURATION_SHORT, easing = EphyraMotion.EasingExit),
            )
        }
        // Also after a fade that a moment without user cut short, back to the same user: the
        // rows always end fully visible. Nothing to do when they are.
        userFade.animateTo(
            1f,
            tween(EphyraMotion.DURATION_MEDIUM, easing = EphyraMotion.EasingEnter),
        )
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
            // CE (#872): a list of its own per user. Rows that leave a list fade out
            // (animateItem), and after a switch the previous user's rows did so for a few frames
            // under the new user's header. A new list has no rows to fade out.
            key(state.user?.id) {
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
            }
            TopContentScrim(height = scrimHeight)
        }

        if (showErrorCard) {
            ServerOrErrorCard(
                error = cardError,
                onShowStacktrace = { showErrorDialog = true },
                onRetryClick = { onAction(HomeAction.OnRetryClick) },
                hasContent = !state.isEmpty,
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
        // CE (#775): in place of the banner the user put away; the error page says it already.
        serverIndicator = showsServerIndicator(hasContent = !state.isEmpty) && !showErrorState,
        modifier = Modifier.padding(start = paddingStart, top = paddingTop, end = paddingEnd),
    )

    if (showProfileBottomSheet) {
        ProfileBottomSheet(
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
                // CE (#872): Home lets go of the previous user's rows first, behind the sheet. The
                // sheet is a window of its own: removed in the same frame, it showed Home's last
                // frame, the previous user's, before Home had drawn again. It slides away instead.
                onUserSwitched()
                scope
                    .launch { profileSheetState.hide() }
                    .invokeOnCompletion { showProfileBottomSheet = false }
            },
            onDismissRequest = { showProfileBottomSheet = false },
            sheetState = profileSheetState,
        )
    }

    if (showServerSelectionBottomSheet) {
        ServerSelectionBottomSheet(
            currentServerId = state.server?.id ?: "".toServerId(),
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
