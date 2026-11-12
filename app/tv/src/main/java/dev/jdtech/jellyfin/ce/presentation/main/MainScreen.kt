package dev.jdtech.jellyfin.ce.presentation.main

import android.app.Activity
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.NavigationDrawerItemBorder
import androidx.tv.material3.NavigationDrawerItemDefaults
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import dev.jdtech.jellyfin.ce.presentation.film.FavoritesScreen
import dev.jdtech.jellyfin.ce.presentation.film.HomeScreen
import dev.jdtech.jellyfin.ce.presentation.film.LibraryScreen
import dev.jdtech.jellyfin.ce.presentation.film.SearchScreen
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.ce.ui.TvSafeArea
import dev.jdtech.jellyfin.ce.ui.components.UserAvatar
import dev.jdtech.jellyfin.ce.ui.focus.DirectionKeyClock
import dev.jdtech.jellyfin.ce.ui.focus.LocalRestoreFocusSuppressed
import dev.jdtech.jellyfin.ce.ui.focus.ephyraFocusedBorder
import dev.jdtech.jellyfin.ce.ui.tvSafeAreaPadding
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.ephyra.EphyraMotion
import dev.jdtech.jellyfin.core.presentation.ephyra.reducedMotion
import dev.jdtech.jellyfin.film.presentation.media.MediaViewModel
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.FindroidCollection
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.viewmodels.MainViewModel
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * CE replacement for the upstream TV MainScreen and MediaScreen (ADR 0001, ADR 0002).
 *
 * Implements a side ModalNavigationDrawer collapsed to an icon rail and expanding on focus:
 * - Direct access to Home, Search, Favorites, and every library in one step.
 * - Profile button at the top (user + server; OK opens user switch).
 * - Settings at the bottom (OK opens SettingsRoute).
 * - Switching active content happens on OK (D-pad center), NOT on focus.
 * - Back button behavior (ADR 0002):
 *     - Other destination -> Home
 *     - Home, drawer closed -> Open drawer with focus on Home
 *     - Drawer open -> Exit app
 * - Per-destination state and focus position retained via SaveableStateProvider (ADR 0003).
 */
@Composable
fun MainScreen(
    navigateToSettings: () -> Unit,
    navigateToUsers: () -> Unit,
    navigateToLibrary: (libraryId: UUID, libraryName: String, libraryType: CollectionType) -> Unit,
    navigateToMovie: (itemId: UUID) -> Unit,
    navigateToShow: (itemId: UUID) -> Unit,
    navigateToEpisode: (episodeId: UUID) -> Unit,
    navigateToCollection: (collectionId: UUID, name: String) -> Unit,
    mainViewModel: MainViewModel = hiltViewModel(),
    mediaViewModel: MediaViewModel = hiltViewModel(),
) {
    val uiState by mainViewModel.uiState.collectAsState()
    val mainState by mainViewModel.state.collectAsState()
    val mediaState by mediaViewModel.state.collectAsState()

    // Runs on every return to this screen (#257); see [loadsOnReturn] for what loads.
    val context = LocalContext.current
    LaunchedEffect(true) {
        val loadedUser = (mainViewModel.uiState.value as? MainViewModel.UiState.Normal)?.user?.id
        val loads =
            loadsOnReturn(
                sameUser =
                    loadedUser != null && loadedUser == JellyfinApi.getInstance(context).userId,
                librariesLoadedAtNanos = mediaViewModel.librariesLoadedAtNanos,
                hasLibraries = mediaViewModel.state.value.libraries.isNotEmpty(),
                nowNanos = System.nanoTime(),
            )
        if (loads.user) mainViewModel.loadServerAndUser()
        when (loads.libraries) {
            LibrariesLoad.FULL -> mediaViewModel.loadData()
            LibrariesLoad.SILENT -> mediaViewModel.loadData(silent = true)
            LibrariesLoad.NONE -> Unit
        }
    }

    var user: User? = null
    var server: Server? = null
    if (uiState is MainViewModel.UiState.Normal) {
        val normal = uiState as MainViewModel.UiState.Normal
        user = normal.user
        server = normal.server
    }

    MainScreenLayout(
        user = user,
        server = server,
        libraries = mediaState.libraries,
        showFavorites = mainState.showFavoritesTab,
        navigateToSettings = navigateToSettings,
        navigateToUsers = navigateToUsers,
        homeContent = {
            // CE Home (G13): hero for the focused card. Episodes open in their season (#130).
            HomeScreen(
                navigateToMovie = navigateToMovie,
                navigateToShow = navigateToShow,
                navigateToEpisode = navigateToEpisode,
                // #513: after a failed start the drawer has no libraries; Retry loads them too.
                onRetry = {
                    val media = mediaViewModel.state.value
                    if (reloadsLibrariesOnRetry(media.libraries.isNotEmpty(), media.isLoading)) {
                        mediaViewModel.loadData()
                    }
                },
            )
        },
        searchContent = {
            SearchScreen(
                navigateToMovie = navigateToMovie,
                navigateToShow = navigateToShow,
                navigateToEpisode = navigateToEpisode,
                navigateToCollection = navigateToCollection,
            )
        },
        favoritesContent = {
            FavoritesScreen(
                navigateToMovie = navigateToMovie,
                navigateToShow = navigateToShow,
                navigateToEpisode = navigateToEpisode,
            )
        },
        libraryContent = { library ->
            LibraryScreen(
                libraryId = library.id,
                libraryName = library.name,
                libraryType = library.type,
                navigateToLibrary = navigateToLibrary,
                navigateToMovie = navigateToMovie,
                navigateToShow = navigateToShow,
                navigateToCollection = navigateToCollection,
            )
        },
    )
}

const val DESTINATION_HOME = "home"
const val DESTINATION_SEARCH = "search"
const val DESTINATION_FAVORITES = "favorites"
fun libraryDestinationKey(libraryId: UUID): String = "library:$libraryId"

/** How old the drawer's libraries may get before a return to Main refreshes them (#257). */
internal const val LIBRARIES_REFRESH_NANOS = 5 * 60 * 1_000_000_000L

internal enum class LibrariesLoad {
    NONE,
    FULL,
    SILENT,
}

internal data class ReturnLoads(val user: Boolean, val libraries: LibrariesLoad)

/**
 * What Main loads when it is shown again (#257). A user or server switch comes back here with the
 * same ViewModels.
 * - The server and user load every time: a cheap database read, and the user's row can change on
 *   the screens Main opens, such as a PIN set or removed in Settings, which the drawer's face shows
 *   with its lock badge (#408). An unchanged user does not recompose anything.
 * - The libraries load in full the first time. After that they refresh in the background, keeping
 *   the list on screen, when the user changed or the last load is older than
 *   [LIBRARIES_REFRESH_NANOS], so a library added on the server still shows up.
 */
internal fun loadsOnReturn(
    sameUser: Boolean,
    librariesLoadedAtNanos: Long?,
    hasLibraries: Boolean,
    nowNanos: Long,
): ReturnLoads =
    ReturnLoads(
        user = true,
        libraries =
            when {
                librariesLoadedAtNanos == null || !hasLibraries -> LibrariesLoad.FULL
                !sameUser || nowNanos - librariesLoadedAtNanos > LIBRARIES_REFRESH_NANOS ->
                    LibrariesLoad.SILENT
                else -> LibrariesLoad.NONE
            },
    )

/**
 * Whether Retry on Home also loads the drawer's libraries (#513): only when there are none, as
 * after a start without the server, and no load is running.
 */
internal fun reloadsLibrariesOnRetry(hasLibraries: Boolean, librariesLoading: Boolean): Boolean =
    !hasLibraries && !librariesLoading

private const val ITEM_PROFILE = "profile"
private const val ITEM_SETTINGS = "settings"

/**
 * Where the pages beside the drawer start. Each page pads its own content by
 * [TvSafeArea.horizontal], so its content starts at 128 dp. The collapsed rail is wider
 * ([DrawerStartPadding] + the 56 dp item = 92 dp) and covers the first 12 dp of that padding,
 * which holds no content: the pages stay where they were before #539.
 */
val ClosedDrawerWidth = 80.dp

/**
 * #539 (ADR 0002, safe area): the drawer's icons, avatar and labels sit inside the TV safe area,
 * its background and focus pill may reach into the margin. tv-material puts the collapsed item's
 * 24 dp icon 16 dp in from the item's edge (20 dp when expanded) and the profile's 32 dp avatar
 * overflows that box by 4 dp on each side, so the avatar starts 12 dp in: at
 * [TvSafeArea.horizontal].
 */
internal val DrawerStartPadding = TvSafeArea.horizontal - 12.dp

/**
 * The expanded drawer keeps 12 dp of surface after the pill. The collapsed rail ends at the pill:
 * its opaque surface covers the pages' padding, and 12 dp more cut the glow of a focused first
 * card off with a visible edge (#539).
 */
internal fun drawerEndPadding(drawerValue: DrawerValue): Dp =
    if (drawerValue == DrawerValue.Open) 12.dp else 0.dp

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION")
@Composable
fun MainScreenLayout(
    user: User?,
    server: Server?,
    libraries: List<FindroidCollection>,
    navigateToSettings: () -> Unit,
    navigateToUsers: () -> Unit,
    modifier: Modifier = Modifier,
    showFavorites: Boolean = true,
    homeContent: @Composable () -> Unit,
    searchContent: @Composable () -> Unit,
    favoritesContent: @Composable () -> Unit,
    libraryContent: @Composable (library: FindroidCollection) -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val baseUrl =
        remember(context) { runCatching { JellyfinApi.getInstance(context).api.baseUrl }.getOrNull() }
    val coroutineScope = rememberCoroutineScope()
    var activeDestinationKey by rememberSaveable { mutableStateOf(DESTINATION_HOME) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val saveableStateHolder = rememberSaveableStateHolder()
    val profileFocusRequester = remember { FocusRequester() }
    val homeFocusRequester = remember { FocusRequester() }
    val settingsFocusRequester = remember { FocusRequester() }
    val contentFocusRequester = remember { FocusRequester() }
    val drawerFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    fun drawerRequester(key: String): FocusRequester =
        if (key == DESTINATION_HOME) homeFocusRequester
        else drawerFocusRequesters.getOrPut(key) { FocusRequester() }
    val activeDrawerRequester = drawerRequester(activeDestinationKey)
    // The drawer item that opened another route (profile or settings). This screen leaves the
    // composition while that route is shown, so the effect below runs again on the way back.
    var returnFocusTo by rememberSaveable { mutableStateOf<String?>(null) }
    // #533: while the drawer holds focus, the content's focus restore leaves it there. Otherwise
    // Home, done refreshing after Back from Settings, took focus from the Settings item.
    val drawerFocus = remember { DrawerFocus() }
    val restoreSuppressed: () -> Boolean = remember(drawerFocus) { { drawerFocus.holds } }

    LaunchedEffect(Unit) {
        val item = returnFocusTo ?: return@LaunchedEffect
        returnFocusTo = null
        val requester = if (item == ITEM_PROFILE) profileFocusRequester else settingsFocusRequester
        drawerFocus.hold()
        requestFocusWhenReady(requester)
    }

    val isHome = activeDestinationKey == DESTINATION_HOME

    // Shows [key] and moves focus into it, so focus never stays on a node that just left the
    // composition. While the destination has nothing focusable yet (still loading), focus goes to
    // [fallback] in the drawer instead.
    fun showDestination(key: String, fallback: FocusRequester?) {
        activeDestinationKey = key
        coroutineScope.launch {
            withFrameNanos {}
            if (!contentFocusRequester.tryRequestFocus()) {
                // The destination takes focus from the drawer once it has loaded (#533).
                drawerFocus.release()
                fallback?.tryRequestFocus()
            }
        }
    }

    LaunchedEffect(showFavorites) {
        if (!showFavorites && activeDestinationKey == DESTINATION_FAVORITES) {
            showDestination(DESTINATION_HOME, fallback = homeFocusRequester)
        }
    }

    BackHandler(enabled = true) {
        // Read here, on Back, not while composing: the drawer opens and closes with every
        // Left/Right into the rail, and a read in composition recomposed the whole layout and its
        // content each time (#257).
        val isDrawerOpen = drawerState.currentValue == DrawerValue.Open
        when {
            !isHome && isDrawerOpen -> {
                activeDestinationKey = DESTINATION_HOME
                drawerFocus.hold()
                homeFocusRequester.tryRequestFocus()
            }
            !isHome -> showDestination(DESTINATION_HOME, fallback = homeFocusRequester)
            !isDrawerOpen -> {
                coroutineScope.launch {
                    drawerState.setValue(DrawerValue.Open)
                    drawerFocus.hold()
                    homeFocusRequester.tryRequestFocus()
                }
            }
            else -> {
                (context as? Activity)?.finish()
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = { drawerValue ->
            Column(
                modifier =
                    Modifier.fillMaxHeight()
                        .focusProperties {
                            right = contentFocusRequester
                            // A D-pad key while nothing had focus lands on the shown destination,
                            // as a move from the content does, not on the first item (profile).
                            onEnter = {
                                if (
                                    requestedFocusDirection != FocusDirection.Enter &&
                                        !drawerFocus.contentHasFocus &&
                                        drawerFocus.keyJustPressed()
                                ) {
                                    drawerRequester(activeDestinationKey).tryRequestFocus()
                                }
                            }
                            exit = { direction ->
                                if (direction == FocusDirection.Right) contentFocusRequester
                                else FocusRequester.Default
                            }
                        }
                        .focusGroup()
                        .onFocusChanged {
                            drawerFocus.onFocusChanged(it.hasFocus)
                            // A key at the very start of the app can put focus here before the
                            // drawer follows it: it stayed collapsed, holding focus.
                            if (it.hasFocus && drawerState.currentValue == DrawerValue.Closed) {
                                coroutineScope.launch { drawerState.setValue(DrawerValue.Open) }
                            }
                        }
                        // #539: the expanded items end at 292 dp, right of a 150 dp poster at the
                        // page's 128 dp (Favorites), so a plain Right can find nothing right of the
                        // item and leave focus in the drawer. Then the content is entered instead.
                        .onPreviewKeyEvent {
                            if (it.type != KeyEventType.KeyDown || it.key != Key.DirectionRight) {
                                return@onPreviewKeyEvent false
                            }
                            if (!focusManager.moveFocus(FocusDirection.Right)) {
                                contentFocusRequester.tryRequestFocus()
                            }
                            true
                        }
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(
                            start = DrawerStartPadding,
                            top = TvSafeArea.vertical,
                            end = drawerEndPadding(drawerValue),
                            bottom = TvSafeArea.vertical,
                        )
                        .testTag("main_drawer_content"),
                horizontalAlignment = Alignment.Start,
            ) {
                // Profile header: user + server, OK opens user switch. Two lines, so its own item
                // (#567).
                DrawerProfileItem(
                    userName = user?.name.orEmpty(),
                    serverName = server?.name,
                    onClick = {
                        returnFocusTo = ITEM_PROFILE
                        navigateToUsers()
                    },
                    leadingContent = {
                        // The 32 dp face overflows the 24 dp box evenly (requiredSize centres), so
                        // the rail stays put.
                        Crossfade(
                            targetState = user,
                            animationSpec =
                                if (reducedMotion()) snap()
                                else tween(EphyraMotion.DURATION_MEDIUM),
                            label = "drawer_avatar",
                        ) { shown ->
                            if (shown != null) {
                                UserAvatar(
                                    user = shown,
                                    baseUrl = baseUrl,
                                    size = 32.dp,
                                    modifier =
                                        Modifier.requiredSize(32.dp)
                                            .testTag("drawer_icon_profile"),
                                )
                            } else {
                                Icon(
                                    painter = painterResource(id = CoreR.drawable.ic_user),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp).testTag("drawer_icon_profile"),
                                )
                            }
                        }
                    },
                    modifier =
                        Modifier.focusRequester(profileFocusRequester)
                            .testTag("drawer_item_profile"),
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Scrollable navigation list: Search, Home, Favorites, and each Library. Moving
                // into it lands on the shown destination (ADR 0002), not on the item that had focus
                // last: Back from a library goes Home without passing the drawer, and a restorer
                // then sent Back and Left on Home to that library (#411). A request for one item
                // (Enter) gets that item.
                LazyColumn(
                    modifier =
                        Modifier.weight(1f)
                            .focusProperties {
                                onEnter = {
                                    if (requestedFocusDirection != FocusDirection.Enter) {
                                        drawerRequester(activeDestinationKey).tryRequestFocus()
                                    }
                                }
                            }
                            .focusGroup()
                            .focusProperties { down = settingsFocusRequester },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item(key = DESTINATION_SEARCH) {
                        NavigationDrawerItem(
                            selected = activeDestinationKey == DESTINATION_SEARCH,
                            onClick = {
                                showDestination(
                                    DESTINATION_SEARCH,
                                    fallback = drawerRequester(DESTINATION_SEARCH),
                                )
                            },
                            leadingContent = {
                                Icon(
                                    painter = painterResource(id = CoreR.drawable.ic_search),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp).testTag("drawer_icon_search"),
                                )
                            },
                            border = ephyraDrawerItemBorder(),
                            modifier =
                                Modifier.focusRequester(drawerRequester(DESTINATION_SEARCH))
                                    .testTag("drawer_item_search"),
                        ) {
                            Text(
                                text = stringResource(id = CoreR.string.search),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }

                    item(key = DESTINATION_HOME) {
                        NavigationDrawerItem(
                            selected = activeDestinationKey == DESTINATION_HOME,
                            onClick = { showDestination(DESTINATION_HOME, fallback = homeFocusRequester) },
                            leadingContent = {
                                Icon(
                                    painter = painterResource(id = CoreR.drawable.ic_home),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp).testTag("drawer_icon_home"),
                                )
                            },
                            border = ephyraDrawerItemBorder(),
                            modifier =
                                Modifier.focusRequester(homeFocusRequester)
                                    .testTag("drawer_item_home"),
                        ) {
                            Text(
                                text = stringResource(id = CoreR.string.title_home),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }

                    if (showFavorites) {
                        item(key = DESTINATION_FAVORITES) {
                            NavigationDrawerItem(
                                selected = activeDestinationKey == DESTINATION_FAVORITES,
                                onClick = {
                                    showDestination(
                                        DESTINATION_FAVORITES,
                                        fallback = drawerRequester(DESTINATION_FAVORITES),
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        painter = painterResource(id = CoreR.drawable.ic_heart),
                                        contentDescription = null,
                                        modifier =
                                            Modifier.size(24.dp).testTag("drawer_icon_favorites"),
                                    )
                                },
                                border = ephyraDrawerItemBorder(),
                                modifier =
                                    Modifier.focusRequester(drawerRequester(DESTINATION_FAVORITES))
                                        .testTag("drawer_item_favorites"),
                            ) {
                                Text(
                                    text = stringResource(id = CoreR.string.title_favorite),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }

                    items(libraries, key = { it.id }) { library ->
                        val itemKey = libraryDestinationKey(library.id)
                        NavigationDrawerItem(
                            selected = activeDestinationKey == itemKey,
                            onClick = {
                                showDestination(itemKey, fallback = drawerRequester(itemKey))
                            },
                            leadingContent = {
                                Icon(
                                    painter = painterResource(id = library.type.drawerIconRes()),
                                    contentDescription = null,
                                    modifier =
                                        Modifier.size(24.dp)
                                            .testTag("drawer_icon_library_${library.id}"),
                                )
                            },
                            border = ephyraDrawerItemBorder(),
                            modifier =
                                Modifier.focusRequester(drawerRequester(itemKey))
                                    .testTag("drawer_item_library_${library.id}"),
                        ) {
                            Text(
                                text = library.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Bottom: Settings
                NavigationDrawerItem(
                    selected = false,
                    onClick = {
                        returnFocusTo = ITEM_SETTINGS
                        navigateToSettings()
                    },
                    leadingContent = {
                        Icon(
                            painter = painterResource(id = CoreR.drawable.ic_settings),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp).testTag("drawer_icon_settings"),
                        )
                    },
                    border = ephyraDrawerItemBorder(),
                    modifier =
                        Modifier.focusRequester(settingsFocusRequester)
                            .testTag("drawer_item_settings"),
                ) {
                    Text(
                        text = stringResource(id = CoreR.string.title_settings),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
        // #533: a D-pad move is the user's; see [DrawerFocus].
        modifier =
            modifier.fillMaxSize().onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key in DirectionKeys) {
                    drawerFocus.onDirectionKey()
                }
                false
            },
    ) {
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .padding(start = ClosedDrawerWidth)
                    .focusRequester(contentFocusRequester)
                    .onFocusChanged { drawerFocus.contentHasFocus = it.hasFocus }
                    .focusProperties {
                        left = activeDrawerRequester
                        exit = { direction ->
                            if (direction == FocusDirection.Left) activeDrawerRequester
                            else FocusRequester.Default
                        }
                    }
                    .focusGroup()
        ) {
            saveableStateHolder.SaveableStateProvider(activeDestinationKey) {
                CompositionLocalProvider(LocalRestoreFocusSuppressed provides restoreSuppressed) {
                    when {
                        activeDestinationKey == DESTINATION_HOME -> {
                            homeContent()
                        }
                        activeDestinationKey == DESTINATION_SEARCH -> {
                            searchContent()
                        }
                        activeDestinationKey == DESTINATION_FAVORITES -> {
                            favoritesContent()
                        }
                        activeDestinationKey.startsWith("library:") -> {
                            val libraryIdString = activeDestinationKey.removePrefix("library:")
                            val library =
                                libraries.firstOrNull { it.id.toString() == libraryIdString }
                            if (library != null) {
                                libraryContent(library)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The focus ring on a drawer item (VISION §6): ring only, on the item's own rounded shape, over
 * tv-material's focused fill. Drawn inside the edge, because the destination list clips its items
 * to its bounds and the items fill its width.
 */
@Composable
private fun ephyraDrawerItemBorder(): NavigationDrawerItemBorder {
    val ring = ephyraFocusedBorder(NavigationDrawerItemDefaults.shape().shape, inside = true)
    return NavigationDrawerItemDefaults.border(
        focusedBorder = ring,
        focusedSelectedBorder = ring,
        pressedBorder = ring,
        pressedSelectedBorder = ring,
    )
}

private val DirectionKeys =
    setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)

/**
 * #533: whether the drawer holds focus, so the content must not take it. It holds focus that Main
 * put there (Back from Settings or the profile, Back on Home) or that the user moved there with the
 * D-pad. It does not hold focus that only landed there because the focused element of the content
 * went away, such as Retry while a page reloads: the page takes it back once it is there. Plain
 * fields, so a focus change recomposes nothing.
 */
private class DrawerFocus {
    private var requested = false

    var holds = false
        private set

    private var hasFocus = false
    private var directionKeyAt: Long? = null

    /** Whether the page beside the drawer has focus. */
    var contentHasFocus = false

    /** Main is about to put focus on a drawer item, or moves it inside the drawer. */
    fun hold() {
        if (hasFocus) holds = true else requested = true
    }

    fun onDirectionKey() {
        directionKeyAt = SystemClock.uptimeMillis()
        // Moving inside the drawer: the user is there.
        if (hasFocus) holds = true
    }

    /**
     * A D-pad key came just now, so focus that moves now is the user's. The activity also sees the
     * keys that reach no key modifier here, while nothing has focus.
     */
    fun keyJustPressed(): Boolean {
        val keyAt = directionKeyAt
        return (keyAt != null && SystemClock.uptimeMillis() - keyAt < DIRECTION_KEY_MS) ||
            DirectionKeyClock.pressedWithin(DIRECTION_KEY_MS)
    }

    fun onFocusChanged(focused: Boolean) {
        if (focused && !hasFocus) {
            holds = requested || keyJustPressed()
            requested = false
        }
        if (!focused) holds = false
        hasFocus = focused
    }

    /** Focus waits in the drawer for a destination that is still loading. */
    fun release() {
        holds = false
        requested = false
    }
}

/** How long after a D-pad key a focus change still counts as its result. */
private const val DIRECTION_KEY_MS = 500L

/** Requests focus, or returns false when [this] is not attached or nothing can take focus. */
private fun FocusRequester.tryRequestFocus(): Boolean =
    try {
        requestFocus(FocusDirection.Enter)
    } catch (_: IllegalStateException) {
        false
    }

/** Retries for a few frames, until the target has been laid out. */
private suspend fun requestFocusWhenReady(requester: FocusRequester) {
    repeat(10) {
        withFrameNanos {}
        if (requester.tryRequestFocus()) return
    }
}

/** Placeholder screen for Favorites until #137 lands. */
@Composable
fun PlaceholderDestinationScreen(
    @DrawableRes icon: Int,
    @StringRes titleRes: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().tvSafeAreaPadding().focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                painter = painterResource(id = icon),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            Text(
                text = stringResource(id = titleRes),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

fun CollectionType.drawerIconRes(): Int =
    when (this) {
        CollectionType.Movies -> CoreR.drawable.ic_film
        CollectionType.TvShows -> CoreR.drawable.ic_tv
        CollectionType.Music -> CoreR.drawable.ic_speaker
        CollectionType.Playlists -> CoreR.drawable.ic_list
        else -> CoreR.drawable.ic_library
    }
