# Search

Status: built on phone (full-screen bar, and docked beside the rail on tablets/foldables) and TV (drawer destination with grouped rows), with Room fallback to downloads while the server is away on phone. As of 2026-11-25 (`main` at `cbb5abb7f`).

## Start here

- `SearchViewModel` (`modes/film/.../search/SearchViewModel.kt`) drives search on both phone and TV over the `ItemLists` and `CurrentAccount` repository roles (`SearchState`, `SearchAction`).
- On phone, search lives on the My media tab (`MediaScreen` + `FilmSearchBar` in `app/phone/.../SearchBar.kt`), with open/origin state hoisted in `NavigationRoot.kt` (`MediaSearch`) so Home's search button opens it and closing returns to Home ([#387](https://github.com/midasvo/ephyra/issues/387), [#394](https://github.com/midasvo/ephyra/issues/394), [#439](https://github.com/midasvo/ephyra/issues/439)).
- On TV, `SearchScreen` (`app/tv/.../ce/presentation/film/SearchScreen.kt`) is a side-drawer destination in `MainScreen` ([#128](https://github.com/midasvo/ephyra/issues/128), [ADR 0002](../tv-research/adr/0002-side-navigation-drawer.md)), grouping results into rows per kind (`ItemKind.MOVIE`, `ItemKind.SERIES`, `ItemKind.EPISODE`, `ItemKind.BOX_SET`).
- Data path: `ItemLists.getSearchItems` in `MediaRepositoryImpl` calls `ItemListPort.search` (`JellyfinCatalog.search` in `server/jellyfin`), falling back to `StoredItemLists.search` (`RoomStoredLists.search` in `data`) on phone when the server is unreachable ([#775](https://github.com/midasvo/ephyra/issues/775)).
- Test fast: `./gradlew :modes:film:testDebugUnitTest --tests '*Search*' :app:phone:testLibreDebugUnitTest --tests '*Search*' :app:tv:testLibreDebugUnitTest --tests '*Search*'`.

## What it does

Typing sends `SearchAction.Search(query)` to `SearchViewModel`. Blank queries clear `items` immediately; non-blank queries debounce for `SearchViewModel.SEARCH_DEBOUNCE_MS` (`300L` ms) inside `currentJob`, so a newer keystroke cancels any waiting request before it is sent.

`MediaRepositoryImpl.getSearchItems(query, itemKinds)` delegates to `JellyfinCatalog.search`:
- When `itemKinds == null` (phone default), it queries `ItemKind.MOVIE` and `ItemKind.SERIES` together up to `ItemListPort.SEARCH_LIMIT` (`50`).
- When `itemKinds != null` (TV passes `[MOVIE, SERIES, EPISODE, BOX_SET]` via `SearchViewModel.setup`), it queries each kind in parallel with `ItemListPort.SEARCH_LIMIT_PER_KIND` (`20`) per kind so episodes cannot crowd out movies, shows, or collections ([#128](https://github.com/midasvo/ephyra/issues/128)).

On phone, `MediaScreen` forwards `LocalServerStatus.current.unreachable` to `SearchViewModel.onServerAwayChanged`. Using `ServerAwayReload`, an open non-blank search re-queries when the server goes away or comes back, and `MediaRepositoryImpl` falls back to `RoomStoredLists.search` (escaped `LIKE %...%` over downloaded movies, shows, and episodes in `ServerDatabaseDao`) and sets `downloadsOnly = true` (`ItemsResult.fromStore`). When Room answers before `ServerReachability` flags the outage, `SearchViewModel` schedules one automatic retry after `ServerAwayReload.STORE_RETRY_DELAY_MS`.

Across a user switch, `SearchState.userId` and `SearchState.shownTo(LocalServerSession.current.userId)` prevent showing the previous user's results or errors for even a frame on either phone or TV, and `SearchViewModel` discards results if `account.getUserId()` changed while the request was in flight ([#866](https://github.com/midasvo/ephyra/issues/866)).

On phone, `FilmSearchBar` collapses to a pill in `MediaScreen`, expands full-screen on compact or compact-height windows, and stays docked beside the navigation rail capped at `Layout.textMaxWidth` (`720.dp`) when `useDockedSearch()` is true ([#439](https://github.com/midasvo/ephyra/issues/439), [4-design.md](../tablet/4-design.md#5-media-library-collection-favorites-search-downloads)). On TV, `SearchScreen` lays out one `LazyRow` per non-empty `SearchGroup` under a read-only-until-clicked `OutlinedTextField` with `FocusPosition` / `RestoreFocus` ([ADR 0003](../tv-research/adr/0003-focus-restore.md)) and `TvLoadError` on failures ([#417](https://github.com/midasvo/ephyra/issues/417)).

## Where the code is

| Path | What |
|---|---|
| [`modes/film/.../search/SearchViewModel.kt`](../../modes/film/src/main/java/dev/jdtech/jellyfin/film/presentation/search/SearchViewModel.kt) | Shared `@HiltViewModel`: 300 ms debounce, `setup(itemKinds)`, `ServerAwayReload`, user-switch guard |
| [`modes/film/.../search/SearchState.kt`](../../modes/film/src/main/java/dev/jdtech/jellyfin/film/presentation/search/SearchState.kt), [`SearchAction.kt`](../../modes/film/src/main/java/dev/jdtech/jellyfin/film/presentation/search/SearchAction.kt) | Immutable UI state (`query`, `items`, `loading`, `error`, `downloadsOnly`, `userId`, `shownTo`) and intents |
| [`modes/film/.../ServerAwayReload.kt`](../../modes/film/src/main/java/dev/jdtech/jellyfin/film/presentation/ServerAwayReload.kt) | Tracks server-away reloads and single delayed retry after a store fallback (`STORE_RETRY_DELAY_MS`) |
| [`app/phone/.../film/MediaScreen.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/MediaScreen.kt) | Hosts `FilmSearchBar`, wires `ReloadWhenServerAwayChanges`, `Ctrl+F`, `Esc`, and `shownTo` |
| [`app/phone/.../film/components/SearchBar.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/components/SearchBar.kt) | `FilmSearchBar`: docked vs full-screen layout, keyboard/focus rules, grid results, `DownloadsOnlyLine`, `ErrorState`, `EmptyState` |
| [`app/phone/.../NavigationRoot.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/NavigationRoot.kt) | `rememberSearchExpanded`, `rememberSearchFromHome`, `MediaSearch` (Home search origin and rail/tab handling) |
| [`app/phone/.../utils/Layout.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/utils/Layout.kt) | `useDockedSearch()` and `Layout.textMaxWidth` / `Layout.gridMinCell` |
| [`app/tv/.../ce/presentation/film/SearchScreen.kt`](../../app/tv/src/main/java/dev/jdtech/jellyfin/ce/presentation/film/SearchScreen.kt) | TV search destination (`SearchGroup` rows, IME `readOnly` toggle, `TvLoadError`, `RestoreFocus`) |
| [`app/tv/.../ce/presentation/main/MainScreen.kt`](../../app/tv/src/main/java/dev/jdtech/jellyfin/ce/presentation/main/MainScreen.kt) | Hosts `DESTINATION_SEARCH` in the drawer with `SaveableStateProvider` |
| [`domain/.../ports/ItemListPort.kt`](../../domain/src/main/kotlin/dev/jdtech/jellyfin/domain/ports/ItemListPort.kt) | `search(query, kinds)`, `SEARCH_LIMIT` (`50`), `SEARCH_LIMIT_PER_KIND` (`20`) |
| [`application/.../repository/ItemLists.kt`](../../application/src/main/kotlin/dev/jdtech/jellyfin/repository/ItemLists.kt), [`MediaRepositoryImpl.kt`](../../application/src/main/kotlin/dev/jdtech/jellyfin/repository/MediaRepositoryImpl.kt) | `ItemLists.getSearchItems` combining `ItemListPort.search` and `StoredItemLists.search` |
| [`server/jellyfin/.../JellyfinCatalog.kt`](../../server/jellyfin/src/main/java/dev/jdtech/jellyfin/server/jellyfin/JellyfinCatalog.kt) | `JellyfinCatalog.search` and `searchItems` calling `libraryApi.getItems(searchTerm = ...)` |
| [`application/.../repository/StoredItemLists.kt`](../../application/src/main/kotlin/dev/jdtech/jellyfin/repository/StoredItemLists.kt), [`data/.../RoomStoredLists.kt`](../../data/src/main/java/dev/jdtech/jellyfin/database/RoomStoredLists.kt), [`ServerDatabaseDao.kt`](../../data/src/main/java/dev/jdtech/jellyfin/database/ServerDatabaseDao.kt) | Downloaded-items search fallback via escaped SQL `LIKE` queries (`searchMoviesOfServer`, `searchShowsOfServer`, `searchEpisodesOfServer`) |

## Rules

- **R1, R2**: no Jellyfin SDK types or `JellyfinApi` outside `server/jellyfin`. Search goes through `ItemLists` (`MediaRepositoryImpl`) and `ItemListPort` (`JellyfinCatalog`).
- **R4**: no production file outside `data` imports `ServerDatabaseDao` (`MediaRepositoryImpl` reads downloads through `StoredItemLists`, implemented by `RoomStoredLists`).
- **R10**: `ItemId`, `UserId`, and `ServerId` are value classes; never use bare `String` or `java.util.UUID` ids outside `server/jellyfin`.
- **TV focus & keyboard ([ADR 0003](../tv-research/adr/0003-focus-restore.md))**: no `LaunchedEffect(true) { focusRequester.requestFocus() }` on TV `SearchScreen`; use `FocusPosition` / `RestoreFocus` and keep the text field `readOnly` until OK is pressed so D-pad Up from results does not open the IME.
- **TV has no offline mode ([ADR 0007](../tv-research/adr/0007-no-offline-on-tv.md))**: TV `SearchScreen` does not wire `ServerAwayReload` or show `DownloadsOnlyLine`.

## How to test it

```bash
./gradlew :modes:film:testDebugUnitTest --tests '*Search*' --tests '*ServerAwayReloadTest*'
./gradlew :app:phone:testLibreDebugUnitTest --tests '*Search*' :app:tv:testLibreDebugUnitTest --tests '*Search*'
```

Unit and Robolectric tests covering search:
- `modes/film`: `SearchViewModelTest` (debounce, blank query cancellation), `SearchViewModelUserSwitchTest` (user switch clearing, `shownTo`, in-flight user mismatch), `ServerAwayReloadTest` (server-away reloads and `STORE_RETRY_DELAY_MS` retry).
- `app/phone`: `MediaSearchTest` (Home search origin and tab/rail rules across recreation), `SearchExpandedTest` (expanded state survives activity recreation), `SearchBarDockedTest` (docked width cap vs full-screen across window size classes), `SearchBarKeyboardTest` (Tab focus, Enter/typing/tap/mouse expansion, Escape shortcut).
- `app/tv`: `SearchErrorTest` (`TvLoadError` message, Retry focus flow, hiding stale results on error), `MainScreenTest` / `MainFocusReturnTest` / `DrawerSafeAreaTest` (drawer Search destination).
- `data` & `testing`: `StoredListsFallbackTest` (Room search by name and `ItemKind`, LIKE escaping, `fromStore`); `ItemListContract` (live/recorded server search contract).

**What unit tests do not cover:**
- Live Jellyfin server search relevance, ranking, or latency against `JellyfinCatalog` (replay recordings live in `server/jellyfin/src/test/resources/contract/`).
- Real soft-keyboard (`IME`) show/hide interactions on Android TV when moving between `OutlinedTextField` and result rows.
- Physical hardware-keyboard navigation and mouse/touch transitions in `FilmSearchBar` on Android devices.

## Before a PR

```bash
./gradlew ktfmtCheck
./gradlew :modes:film:testDebugUnitTest --tests '*Search*' --tests '*ServerAwayReloadTest*'
./gradlew :app:phone:testLibreDebugUnitTest --tests '*Search*' :app:tv:testLibreDebugUnitTest --tests '*Search*'
./gradlew :data:testDebugUnitTest --tests '*StoredListsFallbackTest*'
./gradlew :app:phone:compileLibreDebugKotlin :app:tv:compileLibreDebugKotlin
```

If strings changed, run `python3 .claude/skills/translate-strings/scripts/check-translations.py`.

## Decisions and history

- [#128](https://github.com/midasvo/ephyra/issues/128) / [#148](https://github.com/midasvo/ephyra/pull/148): CE TV `SearchScreen` in the side drawer with `SearchViewModel.setup(itemKinds)` and parallel per-kind server queries (`SEARCH_LIMIT_PER_KIND = 20`) so episodes do not crowd out other kinds.
- [#387](https://github.com/midasvo/ephyra/issues/387) / [#488](https://github.com/midasvo/ephyra/pull/488): `rememberSearchExpanded` saved across recreation and `focusedForExpansion` guard so rotation/theme switch does not collapse search or pop up the keyboard over results.
- [#394](https://github.com/midasvo/ephyra/issues/394) / [#542](https://github.com/midasvo/ephyra/pull/542): `MediaSearch` remembers `rememberSearchFromHome` so closing a search opened from Home returns to Home.
- [#417](https://github.com/midasvo/ephyra/issues/417) / [#537](https://github.com/midasvo/ephyra/pull/537): TV search shows `TvLoadError` (`takeFocus = false`) with Retry (moving focus to the first result on success) and hides stale items on failure.
- [#430](https://github.com/midasvo/ephyra/issues/430) / [#519](https://github.com/midasvo/ephyra/pull/519): Tab onto the collapsed `FilmSearchBar` does not expand it; Enter, typing, tap, or click opens it, and `Esc` closes it.
- [#439](https://github.com/midasvo/ephyra/issues/439) / [#536](https://github.com/midasvo/ephyra/pull/536): docked search beside the navigation rail on medium/expanded non-compact-height windows (`useDockedSearch()`), field capped at `Layout.textMaxWidth`.
- [#775](https://github.com/midasvo/ephyra/issues/775) / [#778](https://github.com/midasvo/ephyra/pull/778), #779: search falls back to downloaded items in Room while the server is away (`RoomStoredLists.search`, `DownloadsOnlyLine`, `ServerAwayReload`).
- [#866](https://github.com/midasvo/ephyra/issues/866) / [#873](https://github.com/midasvo/ephyra/pull/873): `SearchState.userId` + `shownTo` and ViewModel user checks so another user's results or errors never appear after a profile/user switch.
- [#967](https://github.com/midasvo/ephyra/issues/967) / [#1009](https://github.com/midasvo/ephyra/pull/1009): split repository/server catalog ports into `ItemLists` and `ItemListPort`.

## Pitfalls

- **`SearchState.items` keeps the previous query's results on error.** On a failed request, `SearchViewModel` emits `_state.value.copy(query = query, loading = false, error = e, ...)`, leaving `items` from the earlier query in state. TV `SearchScreenLayout` checks `state.error` (when `query.isNotBlank() && state.query == query`) to clear `groups` and hide those stale items ([#417](https://github.com/midasvo/ephyra/issues/417)). On phone, `FilmSearchBar` hides only `DownloadsOnlyLine` on `state.error != null` and still renders stale `state.items` in the `LazyVerticalGrid` beneath the `ErrorState` (`EmptyState`) overlay, which sets no opaque background.
- **Material 3's search field expands on focus by default.** Removing the `onExpandedChange` input-mode guard or `PressInteraction.Press` collector breaks keyboard Tab navigation past the collapsed bar on `MediaScreen` ([#430](https://github.com/midasvo/ephyra/issues/430)).
- **TV `OutlinedTextField` must stay `readOnly` when not actively editing.** Making it editable while merely focused causes Android TV's IME to pop open whenever D-pad Up moves from the result rows back to the field ([#128](https://github.com/midasvo/ephyra/issues/128)).
- **Re-focusing on rotation pops up the keyboard over phone results.** `FilmSearchBar` gates `focusRequester.requestFocus()` behind `focusedForExpansion` (`rememberSaveable`) so activity recreation keeps search open without re-triggering the IME ([#387](https://github.com/midasvo/ephyra/issues/387)).
- **`MediaSearch` origin must stay paired with rail/tab navigation.** On docked tablet search, tapping My media in the rail turns a Home-originated search into a My media search, and leaving the tab clears both `expanded` and `fromHome` ([#394](https://github.com/midasvo/ephyra/issues/394), [#439](https://github.com/midasvo/ephyra/issues/439)).
- **TV `SearchScreen` needs an explicit re-search after a user switch.** Unlike phone `MediaScreen` (where `FilmSearchBar`'s `LaunchedEffect(query)` or `MediaRoute` re-entry runs), TV's `SearchScreenLayout` uses `LaunchedEffect(state.userId)` to re-dispatch `SearchAction.Search(query)` when `shownTo` yields the loading placeholder ([#866](https://github.com/midasvo/ephyra/issues/866)).
- **`RoomStoredLists.search` only searches downloaded movies, shows, and episodes.** Collections (`ItemKind.BOX_SET`) are not stored in Room, and `SearchGroup.COLLECTIONS` on TV has no offline fallback (TV has no offline mode per [ADR 0007](../tv-research/adr/0007-no-offline-on-tv.md)).
