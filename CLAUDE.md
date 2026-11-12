# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

The app is **Ephyra**, a fork of [Findroid](https://github.com/jarnedemeulemeester/findroid). It was called Findroid CE until #172. The repo is `midasvo/ephyra` (renamed from `midasvo/findroid-ce` in #174; GitHub redirects the old URL). The application ID, the Kotlin packages and internal names (`FindroidItem`, `FindroidTheme`, `Theme.Findroid`) keep the old names on purpose.

## Build Commands

```bash
# Debug APK (most common during development)
./gradlew :app:phone:assembleLibreDebug

# Release APK
./gradlew :app:phone:assembleLibreRelease

# TV debug APK
./gradlew :app:tv:assembleLibreDebug

# Compile only (faster feedback, no APK)
./gradlew :app:phone:compileLibreDebugKotlin
./gradlew :app:tv:compileLibreDebugKotlin
./gradlew :modes:film:compileDebugSources

# Full build (all modules)
./gradlew build

# Clean
./gradlew clean
```

```bash
# Formatting check (runs in CI)
./gradlew ktfmtCheck

# Format the tree
./gradlew ktfmtFormat

# Unit tests (CI does not run them: run them yourself before opening a PR)
./gradlew testDebugUnitTest
```

CI on a pull request runs `ktfmtCheck`, the CE translation check and `assembleDebug`. The build needs JDK 21.

## Module Structure

```
app/phone        — Phone UI: screens, navigation, Compose layouts
app/tv           — TV UI: separate screen implementations for Android TV
core             — Shared DI modules, DownloaderViewModel, MainViewModel, common Composables
data             — JellyfinRepository interface + Room database + online/offline implementations
modes/film       — Feature ViewModels and State/Action classes for home, library, search, episode, movie, show, season
player/core      — Player models shared by the player and its callers (PlayerItem, Track, Trickplay)
player/local     — ExoPlayer + mpv PlayerViewModel and playback logic
setup            — Server discovery and login onboarding flow
settings         — AppPreferences (DataStore) and settings screen
baselineprofile  — Macrobenchmark module for app/phone: baseline profile and startup benchmark
```

The `app/phone` module depends on all other modules. ViewModels live in `modes/film`, `setup`, `settings`, and `player/local` — NOT in `app/phone` or `app/tv`. Screens in the app modules consume state from those ViewModels.

**TV:** new TV screens and components go in `app/tv/src/main/java/dev/jdtech/jellyfin/ce/`, next to upstream's screens instead of rewriting them, and `NavigationRoot.kt` points each route at the CE one (ADR 0001). The TV decisions are in `docs/tv-research/adr/`.

## Architecture

**MVI-style MVVM with Unidirectional Data Flow:**

Each screen follows this pattern:
- `FooState` — immutable data class holding all UI state
- `FooAction` — sealed interface of user intents
- `FooViewModel` — `@HiltViewModel`, exposes `StateFlow<FooState>`, handles `onAction()`
- `FooScreen` — stateful Composable that wires ViewModel to layout
- `FooScreenLayout` — stateless Composable that takes state + callbacks (used for previews)

Events that fire once (navigation, toasts) use a `Channel<FooEvent>` exposed as `Flow`, observed via `ObserveAsEvents()`.

**Dependency Injection:** Hilt throughout. DI modules are in `core/src/main/java/dev/jdtech/jellyfin/di/`.

**Navigation:** Navigation 3 with `@Serializable` route objects. `NavigationRoot.kt` in `app/phone` is the single source of truth for the nav graph.

**Offline mode:** `RepositoryModule` provides `JellyfinRepositoryImpl` or `JellyfinRepositoryOfflineImpl`, depending on `isOfflineModeActive()` (the preference, or no connection). The UI reads the same state from the `LocalOfflineMode` CompositionLocal.

## Key Patterns

**Identity:** the visual identity (palette, type, shape, motion, glow rules) is specified in `docs/identity/VISION.md`. Quote its principles in reviews; don't invent colours outside its token tables.

**Downloading:** `Downloader` interface (in `core`) is injected into ViewModels that need it. `DownloaderViewModel` (also in `core`) collects the entries of `DownloadQueue` and exposes `DownloaderState`. Storage index is read from `AppPreferences.downloadStorageIndex`, so downloads don't ask for a storage location. Each download does ask for a quality, through `DownloadQualityDialog`.

**ItemButtonsBar:** The shared download/play/favorite buttons component. Accepts `canDownload` and `isDownloaded` overrides so aggregate items (seasons, shows) can pass computed values instead of deriving from the item directly.

**Build variants:** Only one flavor (`libre`). Build types are `debug`, `release`, `staging`. Always use `Libre` variants (e.g. `assembleLibreDebug`, `compileLibreDebugKotlin`).

**App ID:** `nl.midasvo.findroid.ce` (this is a fork — do not revert to the upstream `dev.jdtech.jellyfin` ID, and do not change it to match the Ephyra name: existing installs update over it).

**Version:** `EphyraVersion` in `buildSrc/src/main/kotlin/EphyraVersion.kt`: `NAME` (semver, from 2.0.0) with the literals `CODE` and `TV_CODE`, which must match it. Only a release PR changes them. Upstream's `Versions.APP_NAME` / `APP_CODE` are unused. Releases (`vX.Y.Z`) go through the `release` skill; merging Findroid's changes is the separate `upstream-sync` skill and never releases anything.

**App name:** `app_name` in `core/src/{main,debug,staging}/res/values/strings.xml` (`Ephyra`, `Ephyra Debug`, `Ephyra Beta`). The Jellyfin client name follows the app label. Where an upstream string names the app (the welcome screen, the local network rationale), CE uses its own `ce_` key instead of editing upstream's translations. The launcher icons and the TV banner are generated by `images/branding/ephyra/make-ephyra.sh`.

**Player PRs:** anything under `player/local` or the player screens runs `docs/mpv-checklist.md` on both backends before merge. It includes the display refresh-rate vote check: ExoPlayer must keep its default frame-rate strategy and mpv must hint the surface on every device, or phones play at their maximum refresh rate (#338). `player/local`'s `PlayerInvariantsTest` fails the build if either is undone.

**AppVariant:** An `AppVariant` enum (`PHONE`, `TV`) is provided by Hilt modules in `app/phone` and `app/tv`, allowing shared ViewModels (e.g. `SettingsViewModel`) to tailor behavior per variant instead of inspecting device hardware.

## Commits and Pull Requests

- **Conventional commits**, on every commit: `type(scope): summary`, such as `fix(player): …` or `feat(profiles): …`. The release skill picks the next version from them: `feat` is a minor release, `type!:` or a `BREAKING CHANGE` footer a major one, anything else a patch. Use `feat` only for something a user gets.
- Branches are `feat/<issue>-<slug>`, `fix/<issue>-<slug>` or `docs/<slug>`. The PR title follows the same convention and names the issue.
- Pull requests are merged with a merge commit, once the checks are green. `main` has no required checks, so `gh pr merge --auto` merges at once: wait for the checks yourself.
- Don't edit `CHANGELOG.md` or the version in a feature PR. The release skill writes both.

## Docs

Read the one that fits the change before starting:

| Change | Doc |
|---|---|
| Colours, type, shape, motion | `docs/identity/VISION.md` |
| `player/local`, `PlayerViewModel`, a player screen or the device profile | `docs/mpv-checklist.md`: run it on both backends |
| Phone layouts at tablet width | `docs/tablet/4-design.md`, test sizes in `docs/tablet/6-test-matrix.md` |
| TV screens | `docs/tv-research/adr/` |
| Startup or scrolling performance | `docs/performance.md` |
| Screenshots for the README, the site or the Play listing | `docs/screenshots.md` |
| Track selection or subtitle styling | `docs/track-test-series.md` |
| Google Play | `docs/play-store.md` |
| A server or an emulator to test on | `docs/dev-environment.md`, through the `test-server` and `test-devices` skills |

The numbered files in `docs/mpv-research/`, `docs/tablet/` and `docs/tv-research/` are research from before the work was done. Where they differ from the code, the code is right.

## Translations & Localization

All user-visible strings and plurals that CE adds must be translated into all 36 supported languages. The `translate-strings` skill has the rules and the language list; use it for every string change.
- Upstream's translations come from Weblate into `values-<lang>/strings.xml`. Never edit those files. To change the text of an upstream string, add a CE key (`ce_<name>`) and use that instead.
- CE-only translations live in `<module>/src/main/res/values-<lang>/strings_ce.xml`, so upstream syncs don't conflict.
- `python3 .claude/skills/translate-strings/scripts/check-translations.py` verifies keys, placeholders and plural quantities. CI fails a PR with an incomplete CE string.
