package dev.jdtech.jellyfin

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.ViewModelStoreProvider
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreProvider
import androidx.lifecycle.viewmodel.navigation3.ViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.SaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.window.core.layout.WindowSizeClass
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.ephyra.ephyraScreenTransition
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.FindroidBoxSet
import dev.jdtech.jellyfin.models.FindroidCollection
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidFolder
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidSeason
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.navigation.navigateOrReuse
import dev.jdtech.jellyfin.navigation.resumedAction
import dev.jdtech.jellyfin.presentation.film.CollectionScreen
import dev.jdtech.jellyfin.presentation.film.DownloadedShowScreen
import dev.jdtech.jellyfin.presentation.film.DownloadsScreen
import dev.jdtech.jellyfin.presentation.film.EpisodeScreen
import dev.jdtech.jellyfin.presentation.film.FavoritesScreen
import dev.jdtech.jellyfin.presentation.film.HomeScreen
import dev.jdtech.jellyfin.presentation.film.LibraryScreen
import dev.jdtech.jellyfin.presentation.film.MediaScreen
import dev.jdtech.jellyfin.presentation.film.MovieScreen
import dev.jdtech.jellyfin.presentation.film.PersonScreen
import dev.jdtech.jellyfin.presentation.film.SeasonScreen
import dev.jdtech.jellyfin.presentation.film.ShowScreen
import dev.jdtech.jellyfin.presentation.settings.AboutScreen
import dev.jdtech.jellyfin.presentation.settings.LicensesScreen
import dev.jdtech.jellyfin.presentation.settings.HomeRowsScreen
import dev.jdtech.jellyfin.presentation.settings.SettingsFileEditScreen
import dev.jdtech.jellyfin.presentation.settings.SettingsScreen
import dev.jdtech.jellyfin.presentation.settings.SubtitleStyleScreen
import dev.jdtech.jellyfin.presentation.setup.pin.ProfilePinGateScreen
import dev.jdtech.jellyfin.presentation.setup.pin.ProfilePinScreen
import dev.jdtech.jellyfin.presentation.setup.addresses.ServerAddressesScreen
import dev.jdtech.jellyfin.presentation.setup.addserver.AddServerScreen
import dev.jdtech.jellyfin.presentation.setup.login.LoginScreen
import dev.jdtech.jellyfin.presentation.setup.permission.PermissionScreen
import dev.jdtech.jellyfin.presentation.setup.servers.ServersScreen
import dev.jdtech.jellyfin.presentation.setup.users.UsersScreen
import dev.jdtech.jellyfin.presentation.setup.welcome.WelcomeScreen
import dev.jdtech.jellyfin.presentation.utils.LocalOfflineMode
import dev.jdtech.jellyfin.presentation.utils.useDockedSearch
import dev.jdtech.jellyfin.setup.presentation.permission.PermissionAction
import dev.jdtech.jellyfin.setup.presentation.permission.PermissionViewModel
import java.util.UUID

private val TOP_LEVEL_ROUTES = setOf(HomeRoute, FavoritesRoute, MediaRoute, DownloadsRoute)
private val GLOBAL_TRANSITION_SPEC = ephyraScreenTransition()

data class TabBarItem(
    @param:StringRes val title: Int,
    @param:DrawableRes val icon: Int,
    val route: NavKey,
    val enabled: Boolean = true,
)

val homeTab =
    TabBarItem(title = CoreR.string.title_home, icon = CoreR.drawable.ic_home, route = HomeRoute)

val favoritesTab =
    TabBarItem(
        title = CoreR.string.title_favorite,
        icon = CoreR.drawable.ic_heart,
        route = FavoritesRoute,
    )

val mediaTab =
    TabBarItem(
        title = CoreR.string.title_media,
        icon = CoreR.drawable.ic_library,
        route = MediaRoute,
    )

val downloadsTab =
    TabBarItem(
        title = CoreR.string.title_download,
        icon = CoreR.drawable.ic_download,
        route = DownloadsRoute,
    )

@Composable
fun NavigationRoot(
    hasServers: Boolean,
    hasCurrentServer: Boolean,
    hasCurrentUser: Boolean,
    showFavoritesTab: Boolean = true,
    askUserOnStart: Boolean = false,
    profileLocked: Boolean = false,
) {
    val setupComplete = hasServers && hasCurrentServer && hasCurrentUser

    val setupStartRoute: NavKey =
        when {
            hasServers && hasCurrentServer -> UsersRoute
            hasServers -> ServersRoute
            else -> WelcomeRoute
        }

    // Navigation state for both modes is hoisted so that it survives switching between them.
    val setupBackStack = rememberNavBackStack(setupStartRoute)
    val navigationState =
        rememberNavigationState(
            startRoute = HomeRoute,
            topLevelRoutes = TOP_LEVEL_ROUTES,
        )
    val navigator = remember(navigationState) { Navigator(navigationState) }

    val entryDecorators = rememberModeEntryDecorators()

    // Exposed as a state object (rather than a plain value) so the entry content closures, which
    // nav3 caches per back stack snapshot, always read the current value.
    val searchExpanded = rememberSearchExpanded()
    val searchFromHome = rememberSearchFromHome()

    // Live setup condition: keep the back stacks in a consistent state across mode switches. The
    // stacks are reconciled during composition (rather than in a LaunchedEffect, which only runs
    // after the first frame has been composed) so the first frame of the new mode already shows
    // the right screen.
    val appliedSetupMode = remember { AppliedSetupMode(setupComplete) }
    if (appliedSetupMode.setupComplete != setupComplete) {
        if (setupComplete) {
            // Setup completed: discard the setup flow and land on Home.
            resetMainToHome(navigator, navigationState, entryDecorators)
        } else {
            // Setup became incomplete: restart the setup flow at the appropriate screen.
            restartSetup(setupBackStack, setupStartRoute, entryDecorators)
        }
        appliedSetupMode.setupComplete = setupComplete
    }

    // "Ask who is watching" (#245): once per app start, push the Users screen on top of Home. Saved
    // so a rotation does not ask again; a new process starts with it unset.
    var askedUserOnStart by rememberSaveable { mutableStateOf(false) }
    // Keyed on setupComplete only: the preference is read once the main mode is entered, so
    // flipping the switch in Settings does not push the screen there and then.
    LaunchedEffect(setupComplete) {
        if (setupComplete && !askedUserOnStart) {
            askedUserOnStart = true
            if (askUserOnStart) navigator.navigate(UsersRoute)
        }
    }

    // The activity-scoped permission ViewModel is shared by the interstitial and the setup screen.
    val permissionViewModel: PermissionViewModel = hiltViewModel()
    val permissionState by permissionViewModel.state.collectAsStateWithLifecycle()

    if (setupComplete && profileLocked) {
        // CE (#299): the profile's PIN comes before Home, and before anything Home would load.
        ProfilePinGateScreen()
    } else if (setupComplete) {
        // Upgrader interstitial: shown before the home screen until the user decides or
        // dismisses it for this session.
        if (permissionState.showInterstitial) {
            PermissionScreen(
                viewModel = permissionViewModel,
                onDecided = {},
                onBackPressed = { permissionViewModel.onAction(PermissionAction.OnDismiss) },
            )
        } else {
            MainNavigation(
                navigationState = navigationState,
                navigator = navigator,
                entryDecorators = entryDecorators.main,
                isOfflineMode = LocalOfflineMode.current,
                showFavoritesTab = showFavoritesTab,
                searchExpanded = searchExpanded,
                searchFromHome = searchFromHome,
                permissionViewModel = permissionViewModel,
                permissionApplicable = permissionState.currentRequest != null,
            )
        }
    } else {
        SetupNavigation(
            backStack = setupBackStack,
            entryDecorators = entryDecorators.setup,
            permissionViewModel = permissionViewModel,
            permissionApplicable = permissionState.currentRequest != null,
        )
    }
}

/**
 * Whether the My media search is expanded. Saved (#387): the activity is recreated on rotation, a
 * theme switch or a resize, and the search must stay open with its query and results.
 */
@Composable
internal fun rememberSearchExpanded(): MutableState<Boolean> =
    rememberSaveable { mutableStateOf(false) }

/** Whether Home's search button opened the My media search (#394), saved like the open state. */
@Composable
internal fun rememberSearchFromHome(): MutableState<Boolean> =
    rememberSaveable { mutableStateOf(false) }

/**
 * The My media search: whether it is open, and whether Home's search button opened it (#394).
 * Home's button opens it on the My media tab, because the search bar lives there; closing a search
 * opened that way goes back to Home instead of leaving the user on My media. Picking My media in
 * the bar or the rail (which stays beside a docked search, #439) makes it a My media search, and
 * leaving the tab forgets both.
 */
internal class MediaSearch(
    val expanded: MutableState<Boolean>,
    private val fromHome: MutableState<Boolean>,
) {
    /** Home's search button. The caller switches to My media. */
    fun openFromHome() {
        expanded.value = true
        fromHome.value = true
    }

    /** A tab picked in the bar or the rail while [current] is shown. */
    fun onTabClick(route: NavKey, current: NavKey) {
        if (route != MediaRoute) return
        fromHome.value = false
        // A second press on My media opens its search.
        if (current == MediaRoute) expanded.value = true
    }

    /** The search bar opened or closed itself: Back, its arrow, Esc. Returns the tab to go to. */
    fun setExpanded(expand: Boolean): NavKey? {
        expanded.value = expand
        if (expand || !fromHome.value) return null
        fromHome.value = false
        return HomeRoute
    }

    /** The shown tab changed; the search belongs to My media. */
    fun onTopLevelRoute(route: NavKey) {
        if (route == MediaRoute) return
        expanded.value = false
        fromHome.value = false
    }
}

/*
 * #388: a mode switch resets the stack of the incoming mode while its display is not shown, so nav3
 * never pops the entries the reset drops. Their saved state and ViewModels are discarded here
 * instead, or a later entry with the same key (a Login screen) gets them back.
 */

/** Resets the main stacks to their roots and Home. The roots keep their state. */
internal fun resetMainToHome(
    navigator: Navigator,
    navigationState: NavigationState,
    entryDecorators: ModeEntryDecorators,
) {
    val before = navigationState.backStacks.values.flatMap { it.toList() }
    navigator.navigateHome()
    val after = navigationState.backStacks.values.flatMapTo(HashSet()) { it.toList() }
    entryDecorators.discardMain(before.filterNot { it in after })
}

/** Restarts the setup flow at [startRoute], with none of the previous flow's state. */
internal fun restartSetup(
    backStack: NavBackStack<NavKey>,
    startRoute: NavKey,
    entryDecorators: ModeEntryDecorators,
) {
    val before = backStack.toList()
    backStack.clear()
    backStack.add(startRoute)
    entryDecorators.discardSetup(before)
}

/**
 * Remembers the setup mode that the back stacks were last reconciled for. Deliberately not
 * observable state: it is written during composition, which must not schedule recompositions.
 */
private class AppliedSetupMode(var setupComplete: Boolean)

/** The entry decorators of the main and the setup [NavDisplay]. */
internal class ModeEntryDecorators(
    private val mainState: EntryState,
    private val setupState: EntryState,
) {
    val main: List<NavEntryDecorator<NavKey>> = mainState.decorators
    val setup: List<NavEntryDecorator<NavKey>> = setupState.decorators

    /** Discards the saved state and ViewModels of [keys], dropped from the main stacks. */
    fun discardMain(keys: Collection<NavKey>) = mainState.discard(keys)

    /** Discards the saved state and ViewModels of [keys], dropped from the setup stack. */
    fun discardSetup(keys: Collection<NavKey>) = setupState.discard(keys)
}

/**
 * The per-entry saved state and ViewModel stores of one [NavDisplay], and the decorators that
 * provide them to its entries.
 */
internal class EntryState(
    private val saveableStateHolder: SaveableStateHolder,
    private val viewModelStoreProvider: ViewModelStoreProvider,
) {
    val decorators: List<NavEntryDecorator<NavKey>> =
        listOf(
            SaveableStateHolderNavEntryDecorator(saveableStateHolder),
            ViewModelStoreNavEntryDecorator(viewModelStoreProvider),
        )

    /**
     * Does for [keys] what nav3 does when an entry is popped: removes its saved state and clears
     * its ViewModels. Only for entries that are not shown. A key without state is ignored.
     */
    fun discard(keys: Collection<NavKey>) {
        keys.forEach { key ->
            val contentKey = entryContentKey(key)
            saveableStateHolder.removeState(contentKey)
            viewModelStoreProvider.clearKey(contentKey)
        }
    }
}

/**
 * The entry decorators hold the per-entry SavedState and ViewModel stores. They are hoisted to
 * [NavigationRoot], otherwise the retained state would be discarded when switching modes.
 *
 * Each mode has its own pair (#381): both back stacks can hold the same route (Settings pushes
 * [UsersRoute] on the main stack, and setup restarts on it when the current user is removed), and
 * the outgoing display releases its entries only after the incoming one has registered them, so a
 * shared saved state holder would see the key twice and throw.
 */
@Composable
internal fun rememberModeEntryDecorators(): ModeEntryDecorators {
    val main = rememberEntryState()
    val setup = rememberEntryState()
    return remember(main, setup) { ModeEntryDecorators(mainState = main, setupState = setup) }
}

/**
 * The content key nav3 gives the entry of [key]: the entries here keep the default of `entry<T>`,
 * which is the default of [NavEntry]. The saved state and ViewModel stores are keyed on it.
 */
internal fun entryContentKey(key: NavKey): Any = NavEntry(key) {}.contentKey

/**
 * The same holder and provider that nav3's `rememberSaveableStateHolderNavEntryDecorator` and
 * `rememberViewModelStoreNavEntryDecorator` create, kept so [EntryState.discard] can reach them.
 */
@Composable
private fun rememberEntryState(): EntryState {
    val saveableStateHolder = rememberSaveableStateHolder()
    val viewModelStoreProvider = rememberViewModelStoreProvider()
    return remember(saveableStateHolder, viewModelStoreProvider) {
        EntryState(saveableStateHolder, viewModelStoreProvider)
    }
}

@Composable
private fun MainNavigation(
    navigationState: NavigationState,
    navigator: Navigator,
    entryDecorators: List<NavEntryDecorator<NavKey>>,
    isOfflineMode: Boolean,
    showFavoritesTab: Boolean,
    searchExpanded: MutableState<Boolean>,
    searchFromHome: MutableState<Boolean>,
    permissionViewModel: PermissionViewModel,
    permissionApplicable: Boolean,
) {
    val mediaSearch =
        remember(searchExpanded, searchFromHome) { MediaSearch(searchExpanded, searchFromHome) }
    val navigationItems =
        when {
            isOfflineMode -> listOf(homeTab, downloadsTab)
            showFavoritesTab -> listOf(homeTab, favoritesTab, mediaTab, downloadsTab)
            else -> listOf(homeTab, mediaTab, downloadsTab)
        }
    val tabRoutes = navigationItems.map { it.route }

    val topLevelRoute = navigationState.topLevelRoute
    val atTabRoot = navigationState.currentBackStack().last() == topLevelRoute
    // #439: on a window with room the open search is docked beside the rail, which stays.
    val dockedSearch = useDockedSearch()
    val showBottomBar =
        atTabRoot && topLevelRoute in tabRoutes && (!searchExpanded.value || dockedSearch)

    LaunchedEffect(topLevelRoute, tabRoutes) {
        if (topLevelRoute !in tabRoutes) {
            navigator.navigateHome()
        }
    }

    // The search bar belongs to the Media tab: collapse it when the active top level route
    // changes away from Media (e.g. via the home button on a detail screen) so that the bottom
    // bar is shown again.
    LaunchedEffect(topLevelRoute) { mediaSearch.onTopLevelRoute(topLevelRoute) }

    val navigationSuiteScaffoldState = rememberNavigationSuiteScaffoldState()

    LaunchedEffect(showBottomBar) {
        if (showBottomBar) {
            navigationSuiteScaffoldState.show()
        } else {
            navigationSuiteScaffoldState.hide()
        }
    }

    val windowAdaptiveInfo = currentWindowAdaptiveInfo()
    val customNavSuiteType =
        with(windowAdaptiveInfo) {
            if (
                windowSizeClass.isWidthAtLeastBreakpoint(
                    WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND
                )
            ) {
                NavigationSuiteType.NavigationRail
            } else {
                NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(this)
            }
        }

    val entryProvider = entryProvider {
        mainEntries(
            navigator = navigator,
            onSearchClick = {
                mediaSearch.openFromHome()
                navigator.navigate(MediaRoute)
            },
            mediaSearch = mediaSearch,
            permissionViewModel = permissionViewModel,
            permissionApplicable = permissionApplicable,
        )
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            navigationItems.forEach { item ->
                item(
                    selected = topLevelRoute == item.route,
                    onClick = {
                        mediaSearch.onTabClick(item.route, topLevelRoute)
                        navigator.navigate(item.route)
                    },
                    icon = {
                        Icon(
                            painter = painterResource(item.icon),
                            contentDescription = stringResource(item.title),
                        )
                    },
                    enabled = item.enabled,
                    label = { Text(text = stringResource(item.title)) },
                    // #442: the hidden bar stays composed; Tab must not stop on it.
                    modifier = Modifier.focusProperties { canFocus = showBottomBar },
                )
            }
        },
        layoutType = customNavSuiteType,
        state = navigationSuiteScaffoldState,
    ) {
        NavDisplay(
            entries = navigationState.toDecoratedEntries(entryDecorators, entryProvider),
            onBack = { navigator.goBack() },
            transitionSpec = { GLOBAL_TRANSITION_SPEC },
            popTransitionSpec = { GLOBAL_TRANSITION_SPEC },
            predictivePopTransitionSpec = { _ -> GLOBAL_TRANSITION_SPEC },
        )
    }
}

@Composable
private fun SetupNavigation(
    backStack: NavBackStack<NavKey>,
    entryDecorators: List<NavEntryDecorator<NavKey>>,
    permissionViewModel: PermissionViewModel,
    permissionApplicable: Boolean,
) {
    val navigate: (NavKey) -> Unit = backStack::add
    val goBack: () -> Unit = { backStack.removeLastOrNull() }
    val navigateOrReuse: (NavKey) -> Unit = backStack::navigateOrReuse

    val entryProvider = entryProvider {
        setupEntries(
            navigate = navigate,
            goBack = goBack,
            navigateOrReuse = navigateOrReuse,
            showBack = backStack.size > 1,
            onSetupComplete = {},
            permissionViewModel = permissionViewModel,
            permissionApplicable = permissionApplicable,
        )
    }

    NavDisplay(
        backStack = backStack,
        onBack = goBack,
        entryDecorators = entryDecorators,
        entryProvider = entryProvider,
        transitionSpec = { GLOBAL_TRANSITION_SPEC },
        popTransitionSpec = { GLOBAL_TRANSITION_SPEC },
        predictivePopTransitionSpec = { _ -> GLOBAL_TRANSITION_SPEC },
    )
}

/**
 * Registers the setup flow entries.
 *
 * The same entries serve both the dedicated setup mode and the setup flow reached from the main UI
 * (e.g. via "manage servers" in Settings). [onSetupComplete] captures the difference: in setup mode
 * completing the flow flips the live condition to the main UI (a no-op), while in the main UI it
 * navigates home. It is invoked through the nearest entry's RESUMED guard.
 */
private fun EntryProviderScope<NavKey>.setupEntries(
    navigate: (NavKey) -> Unit,
    goBack: () -> Unit,
    navigateOrReuse: (NavKey) -> Unit,
    showBack: Boolean,
    onSetupComplete: () -> Unit,
    permissionViewModel: PermissionViewModel,
    permissionApplicable: Boolean,
) {
    entry<WelcomeRoute> {
        WelcomeScreen(
            onContinueClick =
                dropUnlessResumed {
                    navigate(if (permissionApplicable) PermissionRoute else ServersRoute)
                }
        )
    }
    entry<PermissionRoute> {
        PermissionScreen(
            viewModel = permissionViewModel,
            onDecided =
                dropUnlessResumed {
                    // Pop the permission entry so back from Servers returns to Welcome.
                    goBack()
                    navigate(ServersRoute)
                },
        )
    }
    entry<ServersRoute> {
        ServersScreen(
            navigateToUsers = dropUnlessResumed { navigate(UsersRoute) },
            navigateToAddresses =
                resumedAction { serverId -> navigate(ServerAddressesRoute(serverId)) },
            onAddClick = dropUnlessResumed { navigate(AddServerRoute) },
            onBackClick = dropUnlessResumed { goBack() },
            showBack = showBack,
        )
    }
    entry<AddServerRoute> {
        AddServerScreen(
            onSuccess = dropUnlessResumed { navigate(UsersRoute) },
            onBackClick = dropUnlessResumed { goBack() },
        )
    }
    entry<ServerAddressesRoute> { key ->
        ServerAddressesScreen(
            serverId = key.serverId,
            navigateBack = dropUnlessResumed { goBack() },
        )
    }
    entry<UsersRoute> {
        UsersScreen(
            navigateToHome = dropUnlessResumed { onSetupComplete() },
            onChangeServerClick = dropUnlessResumed { navigateOrReuse(ServersRoute) },
            onAddClick = dropUnlessResumed { navigate(LoginRoute()) },
            onBackClick = dropUnlessResumed { goBack() },
            onPublicUserClick =
                resumedAction { username -> navigate(LoginRoute(username = username)) },
            showBack = showBack,
        )
    }
    entry<LoginRoute> { key ->
        LoginScreen(
            onSuccess = dropUnlessResumed { onSetupComplete() },
            onChangeServerClick = dropUnlessResumed { navigateOrReuse(ServersRoute) },
            onBackClick = dropUnlessResumed { goBack() },
            prefilledUsername = key.username,
        )
    }
}

private fun EntryProviderScope<NavKey>.mainEntries(
    navigator: Navigator,
    onSearchClick: () -> Unit,
    mediaSearch: MediaSearch,
    permissionViewModel: PermissionViewModel,
    permissionApplicable: Boolean,
) {
    setupEntries(
        navigate = navigator::navigate,
        goBack = navigator::goBack,
        navigateOrReuse = navigator::navigateOrReuse,
        showBack = true,
        onSetupComplete = { navigator.navigateHome() },
        permissionViewModel = permissionViewModel,
        permissionApplicable = permissionApplicable,
    )
    entry<HomeRoute> {
        HomeScreen(
            onLibraryClick =
                resumedAction { library ->
                    navigator.navigate(
                        LibraryRoute(
                            libraryId = library.id.toString(),
                            libraryName = library.name,
                            libraryType = library.type,
                        )
                    )
                },
            onSearchClick = dropUnlessResumed { onSearchClick() },
            onSettingsClick =
                dropUnlessResumed {
                    navigator.navigate(SettingsRoute(indexes = listOf(CoreR.string.title_settings)))
                },
            onManageServers = dropUnlessResumed { navigator.navigate(ServersRoute) },
            onItemClick = resumedAction { item -> navigateToItem(navigator, item) },
            onAddUser = dropUnlessResumed { navigator.navigate(LoginRoute()) },
            onPublicUserClick =
                resumedAction { username -> navigator.navigate(LoginRoute(username = username)) },
            onUserSwitched = { navigator.navigateHome() },
        )
    }
    entry<MediaRoute> {
        MediaScreen(
            onItemClick = resumedAction { item -> navigateToItem(navigator, item) },
            searchExpanded = mediaSearch.expanded.value,
            onSearchExpand = { expand ->
                mediaSearch.setExpanded(expand)?.let { tab -> navigator.navigate(tab) }
            },
        )
    }
    entry<DownloadsRoute> {
        DownloadsScreen(
            onItemClick =
                resumedAction { item ->
                    navigateToItem(
                        navigator,
                        item,
                        downloadsOnly = true,
                    )
                },
            onShowClick =
                resumedAction { show ->
                    navigator.navigate(
                        DownloadedShowRoute(showId = show.id.toString(), showName = show.name)
                    )
                },
            onBrowseLibraryClick = dropUnlessResumed { navigator.navigate(MediaRoute) },
            onStorageClick =
                dropUnlessResumed {
                    navigator.navigate(SettingsRoute(indexes = listOf(CoreR.string.title_download)))
                },
        )
    }
    entry<DownloadedShowRoute> { key ->
        DownloadedShowScreen(
            showId = UUID.fromString(key.showId),
            showName = key.showName,
            navigateBack = dropUnlessResumed { navigator.goBack() },
            onEpisodeClick = resumedAction { item -> navigateToItem(navigator, item) },
        )
    }
    entry<LibraryRoute> { key ->
        LibraryScreen(
            libraryId = UUID.fromString(key.libraryId),
            libraryName = key.libraryName,
            libraryType = key.libraryType,
            onItemClick = resumedAction { item -> navigateToItem(navigator, item) },
            navigateBack = dropUnlessResumed { navigator.goBack() },
        )
    }
    entry<CollectionRoute> { key ->
        CollectionScreen(
            collectionId = UUID.fromString(key.collectionId),
            collectionName = key.collectionName,
            onItemClick = resumedAction { item -> navigateToItem(navigator, item) },
            navigateBack = dropUnlessResumed { navigator.goBack() },
        )
    }
    entry<FavoritesRoute> {
        FavoritesScreen(
            onItemClick = resumedAction { item -> navigateToItem(navigator, item) },
        )
    }
    entry<MovieRoute> { key ->
        MovieScreen(
            movieId = UUID.fromString(key.movieId),
            navigateBack = dropUnlessResumed { navigator.goBack() },
            navigateHome = dropUnlessResumed { navigator.navigateHome() },
            navigateToItem = resumedAction { item -> navigateToItem(navigator, item) },
            navigateToPerson =
                resumedAction { personId ->
                    navigator.navigate(PersonRoute(personId = personId.toString()))
                },
        )
    }
    entry<ShowRoute> { key ->
        ShowScreen(
            showId = UUID.fromString(key.showId),
            downloadsOnly = key.downloadsOnly,
            navigateBack = dropUnlessResumed { navigator.goBack() },
            navigateHome = dropUnlessResumed { navigator.navigateHome() },
            navigateToItem =
                resumedAction { item ->
                    navigateToItem(
                        navigator,
                        item,
                        downloadsOnly = key.downloadsOnly,
                    )
                },
            navigateToPerson =
                resumedAction { personId ->
                    navigator.navigate(PersonRoute(personId = personId.toString()))
                },
        )
    }
    entry<SeasonRoute> { key ->
        SeasonScreen(
            seasonId = UUID.fromString(key.seasonId),
            seriesId = key.seriesId?.let(UUID::fromString),
            downloadsOnly = key.downloadsOnly,
            navigateBack = dropUnlessResumed { navigator.goBack() },
            navigateHome = dropUnlessResumed { navigator.navigateHome() },
            navigateToItem =
                resumedAction { item ->
                    navigateToItem(
                        navigator,
                        item,
                        downloadsOnly = key.downloadsOnly,
                    )
                },
            navigateToSeries =
                resumedAction { seriesId ->
                    navigator.navigateOrReuse(
                        ShowRoute(showId = seriesId.toString(), downloadsOnly = key.downloadsOnly)
                    )
                },
            navigateToPerson =
                resumedAction { personId ->
                    navigator.navigate(PersonRoute(personId = personId.toString()))
                },
        )
    }
    entry<EpisodeRoute> { key ->
        EpisodeScreen(
            episodeId = UUID.fromString(key.episodeId),
            navigateBack = dropUnlessResumed { navigator.goBack() },
            navigateHome = dropUnlessResumed { navigator.navigateHome() },
            navigateToPerson =
                resumedAction { personId ->
                    navigator.navigate(PersonRoute(personId = personId.toString()))
                },
            navigateToSeason =
                resumedAction { seasonId, seriesId ->
                    navigator.navigateOrReuse(
                        SeasonRoute(seasonId = seasonId.toString(), seriesId = seriesId.toString())
                    )
                },
            navigateToSeries =
                resumedAction { seriesId ->
                    navigator.navigateOrReuse(ShowRoute(showId = seriesId.toString()))
                },
        )
    }
    entry<PersonRoute> { key ->
        PersonScreen(
            personId = UUID.fromString(key.personId),
            navigateBack = dropUnlessResumed { navigator.goBack() },
            navigateHome = dropUnlessResumed { navigator.navigateHome() },
            navigateToItem =
                resumedAction { item ->
                    navigateToItem(
                        navigator,
                        item,
                    )
                },
        )
    }
    entry<SettingsRoute> { key ->
        SettingsScreen(
            indexes = key.indexes,
            navigateToSettings =
                resumedAction { indexes -> navigator.navigate(SettingsRoute(indexes = indexes)) },
            navigateToSettingsFileEdit =
                resumedAction { filePath ->
                    navigator.navigate(SettingsFileEditRoute(filePath = filePath))
                },
            navigateToServers = dropUnlessResumed { navigator.navigate(ServersRoute) },
            navigateToUsers = dropUnlessResumed { navigator.navigate(UsersRoute) },
            navigateToAbout = dropUnlessResumed { navigator.navigate(AboutRoute) },
            navigateToSubtitleStyle = dropUnlessResumed { navigator.navigate(SubtitleStyleRoute) },
            navigateToHomeRows = dropUnlessResumed { navigator.navigate(HomeRowsRoute) },
            navigateToProfilePin = dropUnlessResumed { navigator.navigate(ProfilePinRoute) },
            navigateBack = dropUnlessResumed { navigator.goBack() },
        )
    }
    entry<ProfilePinRoute> {
        ProfilePinScreen(navigateBack = dropUnlessResumed { navigator.goBack() })
    }
    entry<HomeRowsRoute> { HomeRowsScreen(navigateBack = dropUnlessResumed { navigator.goBack() }) }
    entry<SubtitleStyleRoute> {
        SubtitleStyleScreen(navigateBack = dropUnlessResumed { navigator.goBack() })
    }
    entry<SettingsFileEditRoute> { key ->
        SettingsFileEditScreen(
            filePath = key.filePath,
            navigateBack = dropUnlessResumed { navigator.goBack() },
        )
    }
    entry<AboutRoute> {
        AboutScreen(
            navigateBack = dropUnlessResumed { navigator.goBack() },
            navigateToLicenses = dropUnlessResumed { navigator.navigate(LicensesRoute) },
        )
    }
    entry<LicensesRoute> { LicensesScreen(navigateBack = dropUnlessResumed { navigator.goBack() }) }
}

private fun navigateToItem(
    navigator: Navigator,
    item: FindroidItem,
    downloadsOnly: Boolean = false,
) {
    when (item) {
        is FindroidBoxSet ->
            navigator.navigate(
                CollectionRoute(collectionId = item.id.toString(), collectionName = item.name)
            )
        is FindroidMovie -> navigator.navigate(MovieRoute(movieId = item.id.toString()))
        is FindroidShow -> navigator.navigate(ShowRoute(showId = item.id.toString(), downloadsOnly = downloadsOnly))
        is FindroidSeason -> navigator.navigate(
                SeasonRoute(
                    seasonId = item.id.toString(),
                    downloadsOnly = downloadsOnly,
                    seriesId = item.seriesId.toString(),
                )
            )
        is FindroidEpisode -> navigator.navigate(EpisodeRoute(episodeId = item.id.toString()))
        is FindroidCollection ->
            navigator.navigate(
                LibraryRoute(
                    libraryId = item.id.toString(),
                    libraryName = item.name,
                    libraryType = item.type,
                )
            )
        is FindroidFolder ->
            navigator.navigate(
                LibraryRoute(
                    libraryId = item.id.toString(),
                    libraryName = item.name,
                    libraryType = CollectionType.Folders,
                )
            )
        else -> Unit
    }
}
