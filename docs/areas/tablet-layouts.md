# Tablet layouts

Status: built in `app/phone` for windows of medium width (600 dp) and up, including tablets, unfolded foldables, split-screen, and desktop windows (all 12 roadmap items shipped in v2.2.0, with follow-up fixes through [#917](https://github.com/midasvo/ephyra/issues/917)). As of 2026-11-25 (`main`).

## Start here

- Every Compose layout decision keys on `WindowSizeClass` through [`Layout.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/utils/Layout.kt): `WidthClass` (`Compact`, `Medium`, `Expanded`), `currentWidthClass()`, `isCompactHeight()`, `useWideItemLayout()`, `useDockedSearch()`, `useSeasonListDetail()`, and the `Layout` spacing/sizing tokens.
- At `Medium` and `Expanded` width (with non-compact height), `MovieScreen`, `ShowScreen`, and `EpisodeScreen` branch to [`MovieWideLayout.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/MovieWideLayout.kt), [`ShowWideLayout.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/ShowWideLayout.kt), and [`EpisodeWideLayout.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/EpisodeWideLayout.kt), sharing [`ItemHeroHeader.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/components/ItemHeroHeader.kt).
- At `Expanded` width (with non-compact height), `SeasonScreen` and loaded `EpisodeScreen` destinations render the list-detail in [`SeasonWideLayout.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/SeasonWideLayout.kt).
- Grids (`LibraryScreen`, `CollectionGrid`, `FilmSearchBar` results, `MediaScreen`) use [`GridCellsAdaptiveWithMinColumns.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/utils/GridCellsAdaptiveWithMinColumns.kt) with `minColumns = 2`; `DownloadsScreen` switches to two columns at `Expanded` and caps full-width rows at `Layout.textMaxWidth` (`720.dp`).
- Player wide-window behaviour (orientation, edge gestures, expanded control dimensions, episode side sheet, tabletop fold posture, keyboard/mouse) lives in `PlayerActivity.kt`, `PlayerOrientation.kt`, `PlayerGestureHelper.kt`, `PlayerEpisodeSheet.kt`, and `values-w840dp-h480dp/dimens.xml`.
- Wide-window settings visibility (`playerKeepLandscape`, `sideBackdrop`) is decided in `SettingsViewModel` via [`WideWindow.kt`](../../settings/src/main/java/dev/jdtech/jellyfin/settings/domain/WideWindow.kt) and `KeepPlayerLandscape`.
- Design and test references: [4-design.md](../tablet/4-design.md), [6-test-matrix.md](../tablet/6-test-matrix.md), and [README.md](../tablet/README.md).
- Test fast: `./gradlew :app:phone:testLibreDebugUnitTest --tests '*Layout*' --tests '*HeroBackdropWidth*' --tests '*ItemHeroHeader*' --tests '*SeasonListState*' --tests '*DownloadsWidthCap*' --tests '*SearchBarDocked*' --tests '*HomeCarouselPages*' --tests '*PlayerOrientation*' --tests '*KeepLandscapeSetting*' --tests '*PlayerLetterbox*'`.

## What it does

### Width classes, shell, Home, and grids

`currentWidthClass()` maps Material `WindowSizeClass` width breakpoints (`WIDTH_DP_MEDIUM_LOWER_BOUND` at 600 dp, `WIDTH_DP_EXPANDED_LOWER_BOUND` at 840 dp) to `WidthClass.Compact`, `WidthClass.Medium`, and `WidthClass.Expanded`. `isCompactHeight()` checks `!isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)` (< 480 dp, i.e., a phone in landscape). `Layout` provides fixed caps (`textMaxWidth = 720.dp`, `settingsMaxWidth = 640.dp`, `formMaxWidth = 480.dp`, `topBarHeight = 64.dp`) and width-class tokens (`margin` 16/24/24 dp, `gutter` 12/16/16 dp, `cardHorizontal` 260/300/320 dp, `cardVertical` 150/170/180 dp, `gridMinCell` 160/170/180 dp, `posterInHeader` 0/160/200 dp).

`NavigationRoot.kt` forces `NavigationSuiteType.NavigationRail` at `WIDTH_DP_EXPANDED_LOWER_BOUND` and delegates below that to `NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo` (rail at Medium, bottom navigation bar at Compact). `rememberSafePadding(handleStartInsets = false)` zeroes start insets at Medium and Expanded where the rail handles them.

On Home, `HomeCarousel` (`carouselPageCount`) displays 1 / 2 / 3 pages by `WidthClass`, capping `Expanded` at 2 pages when `isCompactHeight()` is true ([#438](https://github.com/midasvo/ephyra/issues/438)), and `ItemCard` widths follow `Layout.cardHorizontal` and `Layout.cardVertical`. On `MediaScreen`, the collapsed `FilmSearchBar` is capped at `Layout.textMaxWidth`, and when `useDockedSearch()` holds (`WidthClass != Compact && !isCompactHeight()`), opening search keeps the rail visible (`showBottomBar`) and docks the input field at the start up to `720.dp` while results fill the content width beside the rail ([#439](https://github.com/midasvo/ephyra/issues/439)).

`LibraryScreen`, `CollectionGrid`, and `FilmSearchBar` results use `GridCellsAdaptiveWithMinColumns` with `Layout.gridMinCell(currentWidthClass())` and `minColumns = 2`; `MediaScreen` uses 160 / 240 / 320 dp breakpoints with `minColumns = 2`. `DownloadsScreen` swaps the collapsing top bar for a pinned `TopAppBar` above `Compact` (matching `FavoritesScreen`), switches to `GridCells.Adaptive(minSize = 480.dp)` at `WidthClass.Expanded`, and wraps full-width spans (storage summary, active download header and rows) in `RowWidth` (`Modifier.widthIn(max = Layout.textMaxWidth).fillMaxWidth()` inside a full-width `Box`, [#434](https://github.com/midasvo/ephyra/issues/434)). `DownloadedShowScreen` caps its episode list rows at `Layout.textMaxWidth`. `PersonScreen` switches at `WIDTH_DP_MEDIUM_LOWER_BOUND` to a two-column row starting below the top bar (`safePadding.top + Layout.topBarHeight`, [#436](https://github.com/midasvo/ephyra/issues/436)), with photo height `Layout.posterInHeader * 1.6f` and text capped at `Layout.textMaxWidth`. Settings screens (`ExternalRatingsScreen`, `HomeRowsScreen`, `LicensesScreen`, `ProfilePinScreen`) cap rows at `Layout.settingsMaxWidth`, and `ServerAddressesScreen` caps at `Layout.formMaxWidth`.

### Item hero layouts (`MovieWideLayout`, `ShowWideLayout`, `EpisodeWideLayout`)

When `useWideItemLayout()` holds (`WidthClass != Compact && !isCompactHeight()`), `MovieScreen`, `ShowScreen`, and `EpisodeScreen` render their `*WideLayout` over `ItemHeroHeader` while keeping dialogs/sheets like `MediaInfoSheet` (`rememberSaveable`) hoisted above the width branch so they survive rotation or folding ([#510](https://github.com/midasvo/ephyra/issues/510)).

`ItemHeroHeader` computes `heroHeight` via `Layout.heroHeight(maxWidth, windowHeight)` (`maxOf(320.dp, minOf(width * 9f / 16f, windowHeight * 0.45f))`) — or `collapsedHeroHeight(safeTop, posterOverlap)` when `artwork.hasArtwork` is false ([#433](https://github.com/midasvo/ephyra/issues/433)). The backdrop is cropped from `Alignment.TopCenter` with `drawTopScrimOverArt()` (`TopScrimOverArtHeight = 96.dp`), `LightStatusBarOverArtwork`, and a bottom `drawFadeIntoPage` (`FadeIntoPageHeight = 80.dp`, [#631](https://github.com/midasvo/ephyra/issues/631)). When `LocalSideBackdrop.current` is enabled ("Backdrop beside the text", [#622](https://github.com/midasvo/ephyra/issues/622)), `Layout.heroBackdropWidth` keeps a 16:9 backdrop at the end side and fades its start `40%` (`SideFadeShare`) into the page background whenever `heroHeight * 16/9 < width * 0.8f` (`SideBackdropMaxShare`). The poster (`Layout.posterInHeader(widthClass)`, or `1.6f *` that for an `Episode`'s 16:9 still) hangs half its height over the backdrop's bottom edge, and `heroStacked` moves the text column below the poster whenever `innerWidth - posterWidth - gap < 360.dp` (`HeroMinColumnWidth`, [#429](https://github.com/midasvo/ephyra/issues/429)).

### Season list-detail (`SeasonWideLayout`)

When `useSeasonListDetail()` holds (`WidthClass.Expanded && !isCompactHeight()`), `SeasonScreen` (and `EpisodeScreen` once `state.episode?.id == episodeId`) renders `SeasonListDetail` / `SeasonWideLayout` using `ListDetailPaneScaffold` (`maxHorizontalPartitions = 2`, `horizontalPartitionSpacerSize = 0.dp`, `1.dp` `outlineVariant` `VerticalDivider`, list pane width `(windowWidth * 0.4f).coerceIn(360.dp, 480.dp)`).

The list pane shows `SeasonListHeader` (`ItemHeader`), `ItemButtonsBar`, optional `DownloadSummary`, and `CompactEpisodeRow` items. `defaultSelectedEpisode` picks the first `!played && !missing` episode (falling back to first non-missing, then first) so the detail pane is never empty; `rememberSeasonListState` / `restorePosition` preserves the scroll offset across rotation and late window-inset arrival ([#435](https://github.com/midasvo/ephyra/issues/435)). The detail pane renders `EpisodeWideContent` keyed on `episodeId` with its own `EpisodeViewModel` and `DownloaderViewModel`, reloading the season list when `played`, `favorite`, or download status changes. `ItemTopBar` (with the series breadcrumb button) spans only the list pane, `DetailPaneStatusBarBackground` shields the status bar over the scrolled detail pane, and `LightStatusBarOverArtwork` turns status-bar icons light only while both panes have artwork under the bar ([#437](https://github.com/midasvo/ephyra/issues/437)).

### Player, foldables, input, and Cast

- **Orientation (`PlayerOrientation.kt`, `PlayerActivity.kt`)**: `playerOrientation` returns `SCREEN_ORIENTATION_LOCKED` when controls are locked, `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` when the maximum window's shorter side is `< 600` dp (`COMPACT_WIDTH_DP`), `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` when `playerKeepLandscape` is enabled outside `multiWindow`, `pictureInPicture`, `foldHalfOpened`, and `!landscapeRefused`, and `SCREEN_ORIENTATION_UNSPECIFIED` otherwise ([#289](https://github.com/midasvo/ephyra/pull/289), [#363](https://github.com/midasvo/ephyra/issues/363)). `isLetterboxed` + `refusesLandscape` detects displays that ignore orientation requests (`wm get-ignore-orientation-request`) by boxing the player into a landscape window under `80%` (`LETTERBOX_AREA_FRACTION`) of an upright maximum window, latching `landscapeRefused` for the rest of that player session ([#363](https://github.com/midasvo/ephyra/issues/363), [#917](https://github.com/midasvo/ephyra/issues/917)).
- **Edge gestures (`PlayerGestureHelper.kt`)**: `outsideVerticalGestureEdgeZones` restricts vertical brightness and volume swipes to edge zones `(widthDp * 0.2f).coerceIn(96f, 240f)` dp wide when `playerView` is at least `840 x 480` dp (`Constants.GESTURE_EDGE_ZONE_*`); swipes starting in the centre dead zone are absorbed until finger lift ([#289](https://github.com/midasvo/ephyra/pull/289)).
- **Expanded controls and episode sheet (`PlayerEpisodeSheet.kt`, `values-w840dp-h480dp/dimens.xml`)**: `isExpandedWindow()` (`screenWidthDp >= 840 && screenHeightDp >= 480`) enables `values-w840dp-h480dp/dimens.xml` sizes (re-applied on `onConfigurationChanged` via `applyPlayerDimens()`) and shows the Episodes button outside PiP. `PlayerEpisodeSheet` opens over the right 30% (`320`–`420.dp`), reporting its bounds (`onBoundsChanged`) so `PlayerActivity` fades overlapping top-row, transport, seek-row, and skip controls ([#456](https://github.com/midasvo/ephyra/issues/456), [#523](https://github.com/midasvo/ephyra/issues/523)) and moves keyboard focus on open (`#588`).
- **Tabletop posture & input**: `WindowInfoTracker` updates `foldHalfOpened` and `tabletopFold`; `applyTabletopLayout()` sets bottom/top margins so `video_area` + `subtitleView` sit above the horizontal hinge and `lower_controls`, `locked_player_view`, `gesturePanels`, and `episodeSheet` sit below it (ignored in PiP). `PlayerActivity` handles keyboard shortcuts (Space, Left/Right, Up/Down, M, L, Esc, media keys) and mouse hover/wheel (`dispatchGenericMotionEvent`), while `KeyboardShortcuts` (`MainActivity.dispatchKeyEvent`) routes `Ctrl+F` and `Esc` in Compose.
- **Settings visibility & Cast**: `SettingsViewModel` gates `playerKeepLandscape` (`KeepPlayerLandscape.isShown`) and `sideBackdrop` on `WideWindow.isPossible(context)` (maximum window shorter side `>= 600` dp or `PackageManager.FEATURE_SENSOR_HINGE_ANGLE` on API 30+). `CastHost` shows `CastPlayerPanel` as a side panel when `widthClass == WidthClass.Expanded && !isCompactHeight()`.

## Where the code is

| Path | What |
|---|---|
| [`app/phone/.../presentation/utils/Layout.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/utils/Layout.kt) | `WidthClass`, `currentWidthClass()`, `isCompactHeight()`, `Layout` tokens, `heroHeight`, `heroBackdropWidth`, `useWideItemLayout()`, `useDockedSearch()`, `useSeasonListDetail()` |
| [`app/phone/.../utils/GridCellsAdaptiveWithMinColumns.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/utils/GridCellsAdaptiveWithMinColumns.kt) | Adaptive grid columns enforcing `minColumns` (used by `LibraryScreen`, `CollectionGrid`, `SearchBar`, `MediaScreen`) |
| [`app/phone/.../film/components/ItemHeroHeader.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/components/ItemHeroHeader.kt) | Shared wide hero header, `heroStacked`, `collapsedHeroHeight`, `HeroTitle`/`HeroSubtitle`/`HeroMetadataLine`/`HeroChips` |
| [`app/phone/.../presentation/utils/ScrimOverArt.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/utils/ScrimOverArt.kt), [`LightStatusBar.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/utils/LightStatusBar.kt), [`LocalSideBackdrop.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/utils/LocalSideBackdrop.kt) | Top status-bar scrim (`TopScrimOverArtHeight`), bottom/side backdrop fades, reference-counted light status-bar icons, and side-backdrop composition local |
| [`app/phone/.../film/MovieWideLayout.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/MovieWideLayout.kt), [`ShowWideLayout.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/ShowWideLayout.kt), [`EpisodeWideLayout.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/EpisodeWideLayout.kt) | Wide item layouts (`EpisodeWideContent` shared with Season detail pane) |
| [`app/phone/.../film/SeasonWideLayout.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/SeasonWideLayout.kt) | `SeasonListDetail`, `SeasonWideLayout`, `CompactEpisodeRow`, `defaultSelectedEpisode`, `rememberSeasonListState`, `SeasonEpisodeDetailPane` |
| [`app/phone/.../film/MovieScreen.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/MovieScreen.kt), [`ShowScreen.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/ShowScreen.kt), [`EpisodeScreen.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/EpisodeScreen.kt), [`SeasonScreen.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/SeasonScreen.kt) | Width/height branch points and state hoisted above them (`mediaInfoOpen`, `selectedEpisodeId`, `episodeListState`) |
| [`app/phone/.../film/components/HomeCarousel.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/components/HomeCarousel.kt), [`ItemCard.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/components/ItemCard.kt), [`SearchBar.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/components/SearchBar.kt) | Carousel `carouselPageCount`, `ItemCard` width scaling, docked `FilmSearchBar` (`docked` mode) |
| [`app/phone/.../film/DownloadsScreen.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/DownloadsScreen.kt), [`DownloadedShowScreen.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/DownloadedShowScreen.kt), [`PersonScreen.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/PersonScreen.kt), [`MediaScreen.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/MediaScreen.kt), [`LibraryScreen.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/LibraryScreen.kt), [`CollectionGrid.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/components/CollectionGrid.kt) | 2-column Downloads at Expanded + `RowWidth` cap, Person two-column start below top bar, grid column sizing |
| [`app/phone/.../NavigationRoot.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/NavigationRoot.kt), [`MainActivity.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/MainActivity.kt), [`presentation/utils/KeyboardShortcuts.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/utils/KeyboardShortcuts.kt), [`presentation/cast/CastHost.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/cast/CastHost.kt) | Forced rail at Expanded, rail preserved for `dockedSearch`, `Ctrl+F`/`Esc` shortcuts, Cast side panel |
| [`app/phone/.../PlayerOrientation.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/PlayerOrientation.kt), [`PlayerActivity.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/PlayerActivity.kt), [`utils/PlayerGestureHelper.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/utils/PlayerGestureHelper.kt), [`presentation/player/PlayerEpisodeSheet.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/player/PlayerEpisodeSheet.kt) | Player orientation + letterbox detection, edge gesture zones (`core/.../Constants.kt`), expanded controls/sheet, tabletop posture, keyboard/mouse |
| [`app/phone/src/main/res/values-w840dp-h480dp/dimens.xml`](../../app/phone/src/main/res/values-w840dp-h480dp/dimens.xml) | Expanded player control, preview, and gesture-panel dimensions |
| [`settings/.../domain/WideWindow.kt`](../../settings/src/main/java/dev/jdtech/jellyfin/settings/domain/WideWindow.kt), [`KeepPlayerLandscape.kt`](../../settings/src/main/java/dev/jdtech/jellyfin/settings/domain/KeepPlayerLandscape.kt), [`SettingsViewModel.kt`](../../settings/src/main/java/dev/jdtech/jellyfin/settings/presentation/settings/SettingsViewModel.kt) | Wide-window capability check (`>= 600` dp shorter side or hinge sensor) gating wide-window settings |
| [`scripts/avds.sh`](../../scripts/avds.sh), [`scripts/tablet-avds.sh`](../../scripts/tablet-avds.sh) | Creates and controls the test AVDs (`ce_phone_tmp`, `ce_tablet_8`, `ce_tablet_10`, `ce_fold`); `tablet-avds.sh` forwards to `avds.sh` |

## Rules

- **Size class, never device ([4-design.md §1](../tablet/4-design.md))**: every layout branch keys on `WindowSizeClass` (`Layout.kt`) or window metrics (`isExpandedWindow()`, `outsideVerticalGestureEdgeZones`, `WindowMetricsCalculator`). Never add `isTablet`, a tablet `AppVariant`, or `smallestScreenWidthDp` checks.
- **Compact unchanged and compact-height guard**: windows `< 600` dp keep the phone layout; `isCompactHeight()` (< 480 dp, phones in landscape) falls back out of `useWideItemLayout()`, `useDockedSearch()`, `useSeasonListDetail()`, `CastHost` side panel, 3-page `HomeCarousel`, and `values-w840dp-h480dp`.
- **Same ViewModels and state**: wide layouts consume the same `FooState` and emit the same `FooAction` as compact layouts; never create tablet-specific ViewModels.
- **Upstream-friendly**: keep wide item/season layouts in CE `*WideLayout.kt` and `ItemHeroHeader.kt` files with one branch at the top of the upstream `*Screen.kt` composable, and hoist shared UI flags (`mediaInfoOpen`, `selectedEpisodeId`, `episodeListState`) above that branch.
- **Measure, don't stretch**: cap running text and list rows at `Layout.textMaxWidth` (`720.dp`), settings at `Layout.settingsMaxWidth` (`640.dp`), and setup forms at `Layout.formMaxWidth` (`480.dp`). Never apply `fillMaxWidth` to `AlertDialog` or `ModalBottomSheet` containers, and wrap full-span `LazyVerticalGrid` items in a `Box` (`RowWidth`) because grid items ignore a bare `widthIn`.
- **Cast dual-flavour**: `CastHost.kt` compiles in both `libre` and `proprietary` (`compileLibreDebugKotlin` and `compileProprietaryDebugKotlin`).
- **Player checklist**: changes touching `PlayerActivity.kt`, `PlayerGestureHelper.kt`, or `PlayerOrientation.kt` must also pass [docs/mpv-checklist.md](../mpv-checklist.md) on both backends and `PlayerInvariantsTest` (`:player:local:testDebugUnitTest`).

## How to test it

Unit and Robolectric tests (no device):

```bash
./gradlew :app:phone:testLibreDebugUnitTest \
  --tests '*Layout*' \
  --tests '*HeroBackdropWidth*' \
  --tests '*ItemHeroHeader*' \
  --tests '*SeasonListState*' \
  --tests '*DownloadsWidthCap*' \
  --tests '*SearchBarDocked*' \
  --tests '*HomeCarouselPages*' \
  --tests '*PlayerOrientation*' \
  --tests '*KeepLandscapeSetting*' \
  --tests '*PlayerLetterbox*'
```

Additional layout/player-UI tests in `app/phone/src/test/`: `HomeSectionSizesTest`, `SearchExpandedTest`, `EpisodeSheetCoverTest`, `PlayerControlsInsetsTest`, `PlayerTopRowTest`, `PlayerKeyFocusTest`, `HeroArtworkTest`, `DownloadsHeaderTest`.

On an emulator or device, run the five window sizes in [6-test-matrix.md](../tablet/6-test-matrix.md) using [`scripts/avds.sh`](../../scripts/avds.sh) (Git Bash on Windows, Linux, or macOS; needs `ANDROID_HOME`):

```bash
scripts/avds.sh up ce_tablet_10
scripts/avds.sh portrait ce_tablet_10
scripts/avds.sh landscape ce_tablet_10
scripts/avds.sh shot ce_tablet_10 out.png
```

**What unit tests do not cover:**
- Visual layout, backdrop crops, status-bar scrims, and taskbar/cutout insets across all five physical/AVD window sizes (`412x915`, `915x412`, `1024x640`, `1280x800` both orientations, `841x701` fold, `1600x900` resizable).
- Real foldable hinge posture (`adb emu posture 2` in portrait for tabletop), split-screen resizing (`am start --windowingMode 6`), and live fold/unfold without video rebuffering.
- Per-display `wm get-ignore-orientation-request` behaviour on real Android 12L–17 tablets for `playerKeepLandscape`, plus hardware keyboard/mouse and picture-in-picture transitions during playback.

## Before a PR

```bash
./gradlew ktfmtCheck
./gradlew :app:phone:testLibreDebugUnitTest \
  --tests '*Layout*' \
  --tests '*HeroBackdropWidth*' \
  --tests '*ItemHeroHeader*' \
  --tests '*SeasonListState*' \
  --tests '*DownloadsWidthCap*' \
  --tests '*SearchBarDocked*' \
  --tests '*HomeCarouselPages*' \
  --tests '*PlayerOrientation*' \
  --tests '*KeepLandscapeSetting*' \
  --tests '*PlayerLetterbox*'
./gradlew :app:phone:compileLibreDebugKotlin :app:phone:compileProprietaryDebugKotlin
```

If the change touches screens in the [§12 screen matrix](../tablet/4-design.md#12-screen-matrix), run the checks in [6-test-matrix.md](../tablet/6-test-matrix.md) and review `@PreviewScreenSizes` or emulator screenshots; if it touches `PlayerActivity.kt` or `PlayerGestureHelper.kt`, run [docs/mpv-checklist.md](../mpv-checklist.md) on both ExoPlayer and mpv.

## Decisions and history

- [#288](https://github.com/midasvo/ephyra/pull/288), [#292](https://github.com/midasvo/ephyra/pull/292): tablet research, design rules (`D1`–`D7` in [4-design.md](../tablet/4-design.md)), `WidthClass`, and `Layout.kt` tokens.
- [#289](https://github.com/midasvo/ephyra/pull/289): `playerOrientation` (unconstrained on `>= 600` dp shorter side) and edge-only brightness/volume swipe zones (`>= 840 x 480` dp).
- [#291](https://github.com/midasvo/ephyra/pull/291): `ItemHeroHeader` and `*WideLayout` for Movie, Show, and Episode.
- [#302](https://github.com/midasvo/ephyra/pull/302): test matrix ([6-test-matrix.md](../tablet/6-test-matrix.md)), AVD script (`scripts/avds.sh`), and 10-inch store screenshots.
- [#363](https://github.com/midasvo/ephyra/issues/363) / [#574](https://github.com/midasvo/ephyra/pull/574), [#917](https://github.com/midasvo/ephyra/issues/917) / [#925](https://github.com/midasvo/ephyra/pull/925): "Keep the player in landscape" setting gated by `WideWindow` / `KeepPlayerLandscape`, with letterbox detection (`isLetterboxed`) and shown across Android versions including Android 16+.
- [#429](https://github.com/midasvo/ephyra/issues/429), [#432](https://github.com/midasvo/ephyra/issues/432) / [#623](https://github.com/midasvo/ephyra/pull/623), [#622](https://github.com/midasvo/ephyra/issues/622) / [#703](https://github.com/midasvo/ephyra/pull/703): header stacking below `360` dp column width, landscape hero height cap (`45%` / `320` dp min), and optional 16:9 side backdrop (`LocalSideBackdrop`).
- [#434](https://github.com/midasvo/ephyra/issues/434), [#435](https://github.com/midasvo/ephyra/issues/435) / [#529](https://github.com/midasvo/ephyra/pull/529), [#436](https://github.com/midasvo/ephyra/issues/436) / [#528](https://github.com/midasvo/ephyra/pull/528), [#437](https://github.com/midasvo/ephyra/issues/437), [#438](https://github.com/midasvo/ephyra/issues/438), [#439](https://github.com/midasvo/ephyra/issues/439) / [#536](https://github.com/midasvo/ephyra/pull/536): `DownloadsScreen` `RowWidth` cap, Season list scroll restoration across rotation, `PersonScreen` top-bar offset, Season detail status-bar scrim, `HomeCarousel` window-width page count, and docked search beside the rail.
- [#456](https://github.com/midasvo/ephyra/issues/456), [#523](https://github.com/midasvo/ephyra/issues/523): `PlayerEpisodeSheet` reports its bounds so `PlayerActivity` fades overlapping controls.

## Pitfalls

- **`LazyVerticalGrid` items ignore a bare `widthIn(max = ...)` ([#434](https://github.com/midasvo/ephyra/issues/434)).** A grid measures each cell or `maxLineSpan` slot at exact width; wrap the content inside a full-width `Box` (`RowWidth` in `DownloadsScreen`) so the inner row stops at `720.dp` aligned to the start.
- **Phones in landscape are `Medium` or `Expanded` wide (~915x412 dp).** Always guard tall or multi-pane wide UI with `!isCompactHeight()` (or `h480dp` resource qualifiers for Views), or phones in landscape get clipped heroes, cramped docked search, 3-card carousels, or oversized player controls.
- **Pane width can be narrower than `WidthClass` suggests ([#429](https://github.com/midasvo/ephyra/issues/429)).** In `SeasonWideLayout` (panes 481–614 dp on 841–1024 dp windows) and 600 dp split-screen, `ItemHeroHeader` must inspect its own `BoxWithConstraints` `innerWidth` (`heroStacked`), not `currentWidthClass()`, or the action bar and text squeeze/clip.
- **`HomeCarousel` and `SearchBar` must measure from window size, not slot width ([#438](https://github.com/midasvo/ephyra/issues/438), [#439](https://github.com/midasvo/ephyra/issues/439)).** Counting carousel pages from the pager's width minus the 80 dp rail and margins drops a page on an 8-inch portrait tablet and foldable; and a docked `SearchBar` needs `wrapContentWidth(Alignment.Start).widthIn(max = Layout.textMaxWidth).fillMaxWidth()` on the input slot so results still use the full content width.
- **State hoisted below the width branch is lost on rotate or fold ([#510](https://github.com/midasvo/ephyra/issues/510), [#435](https://github.com/midasvo/ephyra/issues/435), [#517](https://github.com/midasvo/ephyra/issues/517)).** Keep `mediaInfoOpen`, `selectedEpisodeId`, and `rememberSeasonListState()` above `useWideItemLayout()` / `useSeasonListDetail()`. `rememberSeasonListState` must also re-apply scroll position across frames (`restorePosition`) because `LazyColumn` initially measures before window insets arrive and after rotating through a shorter list.
- **`PlayerActivity` handles `configChanges` itself.** `applyPlayerDimens()` and `updateEpisodesButton()` must run in `onConfigurationChanged`, and `PlayerGestureHelper` must read `playerView.width`/`height` at gesture time rather than caching display bounds.
- **System-bar icons and Android `ignore-orientation-request` behave differently on tablets ([#437](https://github.com/midasvo/ephyra/issues/437), [#363](https://github.com/midasvo/ephyra/issues/363), [#917](https://github.com/midasvo/ephyra/issues/917)).** In `SeasonListDetail`, only request light status-bar icons when both panes have artwork under the bar; in `PlayerActivity`, `playerKeepLandscape` uses `isLetterboxed` to back off if a display boxes a forced-landscape window inside an upright screen.
