# Settings

Status: `AppPreferences` (over `SharedPreferences`), the settings tree, and its phone and TV screens; adapters and `downloads` read preferences through ports (rule R17). As of 2026-11-25 (`main`).

## Start here

- Preferences are stored in `AppPreferences` (`settings/.../domain/AppPreferences.kt`) over Android's `SharedPreferences` (`<packageName>_preferences` in `core/.../di/SharedPreferencesModule.kt`), with typed `Preference` descriptors (`Boolean`, `Int`, `Long`, `Float`, `String?`, `Set<String>`).
- `SettingsViewModel` (`settings/.../presentation/settings/SettingsViewModel.kt`) defines the nested settings tree (`topLevelPreferences`), walks `indexes` on `loadPreferences`, and filters groups and rows by `AppVariant` (ADR 0008 / #133), `ServerCapabilities` (`ServerFeature`, #769), device/build capability (`KeepPlayerLandscape`, `WideWindow`, `CastBuild`), `minSdk`, and boolean `dependencies`.
- Phone screens live in `app/phone/.../presentation/settings/` (`SettingsScreen.kt` + `components/`). TV routes both top-level (`SettingsScreen.kt`) and nested (`SettingsSubScreen.kt`) categories through `TvSettingsLayout` (`app/tv/.../ce/presentation/settings/TvSettingsLayout.kt`, #347).
- Adapters (`data`, `server/jellyfin`, `ratings/mdblist`, `device`) and `downloads` never reference `AppPreferences` directly (R17, R18); `core/.../di/AppPreferencesPorts.kt` implements the `application` ports (`DownloadSettings`, `CurrentServerId`, `NextUpMaxDays`, `NetworkTimeouts`, `ExternalRatingsSettings`, `ResumeThresholdsStore`) over `AppPreferences`.
- Test fast: `./gradlew :settings:testDebugUnitTest` and `./gradlew :app:tv:testLibreDebugUnitTest --tests '*Settings*'`.

## What it does

`AppPreferences` wraps `SharedPreferences` with `getValue`, `observe` (`callbackFlow` over `OnSharedPreferenceChangeListener`), and `setValue` (`apply()`). If reading a key fails (for example after a type mismatch), `getValue` resets it to `defaultValue` and returns that default. Alongside user-facing settings, `AppPreferences` holds internal state (`currentServer` as `ServerId` via `currentServerId()`/`setCurrentServerId()`, #902; cached session credentials `sessionServerId`, `sessionBaseUrl`, `sessionAccessToken`, `sessionUserId`, `sessionServerType`, #250, #770; `playerBrokenAudioDecoders`; `smartDownloadsSince`; `localNetworkPermissionDecided`; library `sortBy`/`sortOrder`/`filterWatched`; and one-shot migration flags). `backup_rules.xml` and `data_extraction_rules.xml` exclude the whole `sharedpref` domain from backups and device-to-device transfers (#731) so session tokens and `mdbListApiKey` never leave the device.

`BaseApplication.onCreate()` runs one-shot preference migrations at startup:
- `migrateMpvAudioOutput()` (#52) moves `"aaudio"` in `playerMpvAo` to `Constants.MpvAudioOutput.AUDIOTRACK` once (`mpvAoAaudioMigrated`).
- `migrateMediaSegmentActions()` (#271) converts the removed global segment switches and type lists via `MediaSegmentsMigration.actions()` into per-type actions (`INTRO`, `OUTRO`, `RECAP`, `PREVIEW`, `COMMERCIAL`), guarded by `mediaSegmentActionsMigrated`.
- `removeOfflineMode()` (#281) removes `pref_offline_mode` if present.
- Phone's `BaseApplication` calls `migrateMaxBitratePerNetwork()` (#837) to copy `playerMaxBitrate` (Wi-Fi/Ethernet) to `playerMaxBitrateMobile` once when the mobile key is absent; TV's `BaseApplication` calls `useTvImageCacheSize()` to default `imageCacheSize` to `TV_IMAGE_CACHE_SIZE_MB` (100 MB) when unset.

`SettingsViewModel` builds `SettingsState` for a `indexes: List<Int>` path of `PreferenceCategory` string IDs:
- Filters by `supportedDeviceTypes` (`AppVariant.PHONE` or `AppVariant.TV`, injected via each app's `AppVariantModule`), plus `KeepPlayerLandscape.isShown` (#363), `WideWindow.isPossible` (#622), and `CastBuild.isIncluded` (#626, bound via each app/flavor's `CastBuildModule`).
- Filters groups and items by `ServerFeature` (`MEDIA_SEGMENTS`, `TRICKPLAY`, `THEME_SONGS`) against `CurrentServerCapabilities.get()`, and hides the `"server"` option from `playerAutoPlayNext` via `withoutServerChoice()` when `autoPlayNextSetting` is false (#769).
- Clamps `subtitleFontScale` (`50..200`), `smartDownloadsKeepReady` (`>= 1`), and `stillWatchingPromptTimeoutSeconds` (`>= 5`) with `AppPreferences.bounded()` (#559) both on load and on save (`onAction`).
- Formats `PreferenceIntInput` labels with `numberInputText()` (#400) for plurals (`suffixPluralsRes`) and zero states (`zeroLabelRes` such as "Off" or "No limit").
- Computes download storage options (`getStorageOptions()`) on `Dispatchers.IO` only when a `PreferenceDynamicSelect` is on the page, and clears Coil + `HomeCache` via `AppCaches.clear()` (`AppCachesModule`).

Dedicated sub-screens in `settings/` and the apps handle custom flows: `ExternalRatingsSettingsViewModel` (#331), `SubtitleStyleViewModel` (phone's `SubtitleStyleScreen`, #308), `SettingsFileEditViewModel` (phone's `mpv/mpv.conf` and `mpv/input.conf` editor), `HomeRowsScreen` (#142), `AboutScreen` + `LicensesScreen` (and TV's `DeviceProfileScreen`, #211, #384, #492), and TV's `ScreensaverSettingsActivity`/`ScreensaverSettingsScreen` (#140).

## Where the code is

| Path | What |
|---|---|
| [`settings/.../domain/AppPreferences.kt`](../../settings/src/main/java/dev/jdtech/jellyfin/settings/domain/AppPreferences.kt) | Typed `Preference` keys, `SharedPreferences` read/write/observe, startup migrations, `currentServerId` |
| [`settings/.../domain/IntPreferenceBounds.kt`](../../settings/src/main/java/dev/jdtech/jellyfin/settings/domain/IntPreferenceBounds.kt), [`MediaSegmentsMigration.kt`](../../settings/src/main/java/dev/jdtech/jellyfin/settings/domain/MediaSegmentsMigration.kt) | Bounded integer clamping (#559) and pure per-type segment migration (#271) |
| [`settings/.../domain/VideoQualities.kt`](../../settings/src/main/java/dev/jdtech/jellyfin/settings/domain/VideoQualities.kt), [`QualityNetwork.kt`](../../settings/src/main/java/dev/jdtech/jellyfin/settings/domain/QualityNetwork.kt) | Bitrate-only quality ladder (#837, #1021) and metered-network split |
| [`settings/.../domain/WideWindow.kt`](../../settings/src/main/java/dev/jdtech/jellyfin/settings/domain/WideWindow.kt), [`KeepPlayerLandscape.kt`](../../settings/src/main/java/dev/jdtech/jellyfin/settings/domain/KeepPlayerLandscape.kt), [`CastBuild.kt`](../../settings/src/main/java/dev/jdtech/jellyfin/settings/presentation/settings/CastBuild.kt) | Visibility checks for tablet/foldable layout, orientation lock, and Cast settings |
| [`settings/.../domain/AppCaches.kt`](../../settings/src/main/java/dev/jdtech/jellyfin/settings/domain/AppCaches.kt), [`Constants.kt`](../../settings/src/main/java/dev/jdtech/jellyfin/settings/domain/Constants.kt) | Cache-clearing port and default constants (subtitle style, mpv `ao`, still-watching min, network timeouts) |
| [`settings/.../presentation/models/`](../../settings/src/main/java/dev/jdtech/jellyfin/settings/presentation/models/) | `Preference` UI models (`PreferenceSwitch`, `PreferenceSelect`, `PreferenceIntSelect`, `PreferenceDynamicSelect`, `PreferenceIntInput`, `PreferenceLongInput`, `PreferenceCategory`, `PreferenceAction`, `PreferenceFileEdit`, `PreferencePermission`, `PreferenceAppLanguage`, `PreferenceMultiSelect`), `ServerFeature`, `numberInputText` |
| [`settings/.../presentation/settings/SettingsViewModel.kt`](../../settings/src/main/java/dev/jdtech/jellyfin/settings/presentation/settings/SettingsViewModel.kt) | Settings tree (`topLevelPreferences`), filtering, bounds, storage probe, `SettingsAction`/`SettingsEvent` |
| [`settings/.../presentation/ratings/ExternalRatingsSettingsViewModel.kt`](../../settings/src/main/java/dev/jdtech/jellyfin/settings/presentation/ratings/ExternalRatingsSettingsViewModel.kt), [`SubtitleStyleViewModel.kt`](../../settings/src/main/java/dev/jdtech/jellyfin/settings/presentation/subtitle/SubtitleStyleViewModel.kt), [`SettingsFileEditViewModel.kt`](../../settings/src/main/java/dev/jdtech/jellyfin/settings/presentation/settings/SettingsFileEditViewModel.kt) | Sub-screen ViewModels for MDBList ratings (#331), phone subtitle style (#308), and `mpv.conf`/`input.conf` |
| [`core/.../di/AppPreferencesPorts.kt`](../../core/src/main/java/dev/jdtech/jellyfin/di/AppPreferencesPorts.kt), [`AppPreferencesModule.kt`](../../core/src/main/java/dev/jdtech/jellyfin/di/AppPreferencesModule.kt), [`SharedPreferencesModule.kt`](../../core/src/main/java/dev/jdtech/jellyfin/di/SharedPreferencesModule.kt) | `AppPreferences` DI and `application` port implementations (`DownloadSettings`, `CurrentServerId`, `NextUpMaxDays`, `NetworkTimeouts`, `ExternalRatingsSettings`, `ResumeThresholdsStore`) |
| [`core/.../core/settings/SharedPreferencesResumeThresholdsStore.kt`](../../core/src/main/java/dev/jdtech/jellyfin/core/settings/SharedPreferencesResumeThresholdsStore.kt) | Per-server resume thresholds persisted in `SharedPreferences` (#637, #918) |
| [`app/phone/.../presentation/settings/SettingsScreen.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/settings/SettingsScreen.kt), [`components/`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/settings/components/) | Phone settings screen, sub-screens (`AboutScreen.kt`, `LicensesScreen.kt`, `SubtitleStyleScreen.kt`, `HomeRowsScreen.kt`, `ExternalRatingsScreen.kt`, `SettingsFileEditScreen.kt`), and card/dialog components |
| [`app/tv/.../presentation/settings/SettingsScreen.kt`](../../app/tv/src/main/java/dev/jdtech/jellyfin/presentation/settings/SettingsScreen.kt), [`SettingsSubScreen.kt`](../../app/tv/src/main/java/dev/jdtech/jellyfin/presentation/settings/SettingsSubScreen.kt), [`TvSettingsLayout.kt`](../../app/tv/src/main/java/dev/jdtech/jellyfin/ce/presentation/settings/TvSettingsLayout.kt) | TV root/sub-settings screens and shared two-pane D-pad layout (#347, #419, #420) |
| [`app/tv/.../ce/presentation/settings/about/`](../../app/tv/src/main/java/dev/jdtech/jellyfin/ce/presentation/settings/about/) | TV `AboutScreen`, `DeviceProfileScreen`, and `LicensesScreen` (#211) |
| [`app/phone/.../di/AppVariantModule.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/di/AppVariantModule.kt), [`app/tv/.../di/AppVariantModule.kt`](../../app/tv/src/main/java/dev/jdtech/jellyfin/di/AppVariantModule.kt), `CastBuildModule.kt` | `AppVariant.PHONE` / `AppVariant.TV`, `CastBuild` per flavour, and each app's `AppCachesModule` |

## Rules

- **R17** ([`AdapterSettingsTest.kt`](../../architecture/src/test/kotlin/dev/jdtech/jellyfin/architecture/AdapterSettingsTest.kt)): no production file of `data`, `server/jellyfin`, `ratings/mdblist`, `device`, or `downloads` may name `AppPreferences`. Expose new settings needed by adapters or the download engine through a port in `application` and implement it in `core/.../di/AppPreferencesPorts.kt` (#918).
- **R18** ([`AdapterDependencyTest.kt`](../../architecture/src/test/kotlin/dev/jdtech/jellyfin/architecture/AdapterDependencyTest.kt)): no adapter module (`data`, `server/jellyfin`, `ratings/mdblist`, `device`) may depend on a feature module such as `settings` in production.
- **R1, R2, R10**: `settings` stays free of Jellyfin SDK types/`JellyfinApi`, and uses typed IDs (`ServerId`) rather than bare `String` IDs outside the adapter.
- **Translations and `AppVariant`**: every user-visible CE string or plural must use a `ce_` key in `strings.xml` and be translated into all 36 languages (`check-translations.py`), never editing upstream translation files. Variant-only UI decisions use `AppVariant` (ADR 0008), not `FEATURE_LEANBACK`.

## How to test it

Fastest unit test commands:

```bash
./gradlew :settings:testDebugUnitTest
./gradlew :app:tv:testLibreDebugUnitTest --tests '*Settings*'
```

Also relevant when touching ports, backup rules, or phone licenses:

```bash
./gradlew :core:testLibreDebugUnitTest --tests '*SharedPreferencesResumeThresholdsStoreTest*'
./gradlew :app:phone:testLibreDebugUnitTest --tests '*LicenseDialogTextTest*' --tests '*DeviceTransferRulesTest*'
./gradlew :architecture:test --tests '*AdapterSettingsTest*'
```

Unit tests in `settings/src/test/` and `app/tv/src/test/.../settings/`:
- `settings`: `IntPreferenceBoundsTest`, `MaxBitratePerNetworkTest`, `MediaSegmentsMigrationTest`, `RemoveOfflineModeTest`, `VideoQualitiesTest`, `NumberInputTextTest`, `ExternalRatingsSettingsViewModelTest`.
- `app/tv`: `SettingsCapabilitiesTest`, `SettingsTreeVariantTest`, `TvSettingsLayoutTest` (Robolectric Compose D-pad focus + Back behaviour), `TvSettingsNumberStepsTest`.

**What unit tests do not cover:**
- `SettingsScreen` on phone (`app/phone/.../presentation/settings/SettingsScreen.kt`), `SubtitleStyleScreen`, `SettingsFileEditScreen`, and phone dialogs (`SettingsNumberInputDialog`, `SettingsSelectDialog`) — verified manually on a phone/tablet emulator.
- `getStorageOptions()` with multiple physical/emulated storage volumes (`StatFs` on external dirs), Android 13+ `Settings.ACTION_APP_LOCALE_SETTINGS`, runtime permission prompts (`SettingsPermissionCard`), and actual `BaseApplication` startup migrations on an upgraded APK.
- `TvSettingsLayout` visual alignment, overscan safe area, and D-pad traversal across every TV settings sub-screen on a TV emulator (`scripts/tv-dpad.sh`).

## Before a PR

```bash
./gradlew ktfmtCheck
./gradlew :settings:testDebugUnitTest
./gradlew :app:tv:testLibreDebugUnitTest --tests '*Settings*'
./gradlew :app:phone:testLibreDebugUnitTest --tests '*LicenseDialogTextTest*' --tests '*DeviceTransferRulesTest*'
./gradlew :core:testLibreDebugUnitTest --tests '*SharedPreferencesResumeThresholdsStoreTest*'
./gradlew :architecture:test
./gradlew :app:phone:compileLibreDebugKotlin :app:tv:compileLibreDebugKotlin
python3 .claude/skills/translate-strings/scripts/check-translations.py   # when strings changed
```

## Decisions and history

- [TV ADR 0008](../tv-research/adr/0008-tv-flag-in-shared-modules.md) / [#133](https://github.com/midasvo/ephyra/issues/133): `AppVariant` (`PHONE` vs `TV`) injected from each app filters shared settings in `SettingsViewModel` instead of inspecting device hardware.
- [#347](https://github.com/midasvo/ephyra/pull/347), [#524](https://github.com/midasvo/ephyra/pull/524): unified `TvSettingsLayout` for both root and nested TV settings pages, plus D-pad focus return on `Left`/`Back` ([#419](https://github.com/midasvo/ephyra/issues/419)), whole-second stepping for millisecond settings ([#420](https://github.com/midasvo/ephyra/issues/420)), hiding the phone-only `Advanced` file-editor group on TV ([#421](https://github.com/midasvo/ephyra/issues/421)), and screensaver summary spacing ([#422](https://github.com/midasvo/ephyra/issues/422)).
- [#211](https://github.com/midasvo/ephyra/issues/211), [#384](https://github.com/midasvo/ephyra/issues/384), [#503](https://github.com/midasvo/ephyra/pull/503), [#492](https://github.com/midasvo/ephyra/pull/492): dedicated TV `AboutScreen`/`DeviceProfileScreen`/`LicensesScreen`, phone license text fallback (`LicenseDialogTextTest`), and Android 11+ device profile export.
- [#736](https://github.com/midasvo/ephyra/issues/736) / [#793](https://github.com/midasvo/ephyra/pull/793), [#918](https://github.com/midasvo/ephyra/issues/918) / [#927](https://github.com/midasvo/ephyra/pull/927) / [#926](https://github.com/midasvo/ephyra/pull/926): dropped the unused Jellyfin SDK dependency from `settings`, and moved `data`, `server/jellyfin`, and `downloads` off `AppPreferences` onto `application` ports in `AppPreferencesPorts.kt` (rule R17).
- [#769](https://github.com/midasvo/ephyra/issues/769): `ServerFeature` and `withoutServerChoice()` hide settings for features the current server lacks (`ServerCapabilities`), re-evaluated on every `loadPreferences` call.
- [#271](https://github.com/midasvo/ephyra/issues/271), [#281](https://github.com/midasvo/ephyra/issues/281), [#837](https://github.com/midasvo/ephyra/issues/837), [#52](https://github.com/midasvo/ephyra/issues/52): one-shot migrations for per-type media segment actions (`MediaSegmentsMigration`), removing Offline Mode (`removeOfflineMode`), splitting Wi-Fi vs mobile streaming quality (`migrateMaxBitratePerNetwork`), and moving mpv `ao` off `"aaudio"` (`migrateMpvAudioOutput`).
- [#400](https://github.com/midasvo/ephyra/issues/400), [#559](https://github.com/midasvo/ephyra/issues/559), [#1021](https://github.com/midasvo/ephyra/pull/1021): singular/plural and zero-label number formatting (`numberInputText`), clamping bounded integer preferences on both load and save (`AppPreferences.bounded`), and bitrate-only quality labels (`VideoQualities`).
- [#950](https://github.com/midasvo/ephyra/issues/950) / [#958](https://github.com/midasvo/ephyra/pull/958): added `navigate_back` content description to the phone settings top bar Back button.

## Pitfalls

- **Phone `SettingsScreen` reloads after `OnUpdate`, and `PreferenceCategory` matching uses string IDs.** `SettingsViewModel.loadPreferences(indexes)` walks `PreferenceCategory.nameStringResource` down `nestedPreferenceGroups` (for example, TV's `NavigateToSubtitleStyle` must pass both `settings_category_player` and `subtitle_style`). Renaming a category's string resource requires updating any hardcoded navigation `indexes` list too (such as `NavigationRoot.kt` or `SettingsScreen.kt` on TV).
- **Boolean `dependencies` only disable a row; they do not hide it.** On `SettingsBaseCard` (phone) and `TvSettingsRow` (TV), `enabled = false` dims/disables interaction when any `PreferenceBackend<Boolean>` in `dependencies` is `false`. To hide a setting entirely per variant or server capability, use `supportedDeviceTypes` or `serverFeature`, not `dependencies`.
- **TV has no `PreferenceIntInput`/`PreferenceLongInput` text dialog or file editor.** `TvSettingsLayout` edits number settings in the right details pane with `+1`/`-1`/`+10`/`-10` steps (scaled by `1000L` when `suffixRes == R.string.ms`, #420) and clamps at `>= 0`. Phone-only features like `PreferenceFileEdit` (`mpv.conf`/`input.conf`) and `PreferenceIntSelect` streaming quality selectors must declare `supportedDeviceTypes = listOf(DeviceType.PHONE)` so TV does not show an uneditable or broken row (#421).
- **Adding a bounded `Int` preference needs `AppPreferences.bounded()` on both load and save.** Without clamping in `IntPreferenceBounds.kt`, the UI saves and displays raw out-of-range numbers such as `0 s` even if the consumer treats `0` as a minimum like `5 s` (#559).
- **Sensitive keys live in the same `SharedPreferences` file.** `sessionAccessToken` and `mdbListApiKey` are stored in `AppPreferences` alongside UI toggles; keep `sharedpref` excluded in both `backup_rules.xml` and `data_extraction_rules.xml` (`DeviceTransferRulesTest`, #731).
- **Adding a new `Preference` subclass requires updating both UI renderers.** `SettingsGroupCard.kt` (phone) and `TvSettingsPreference` / `TvSettingsDetails` in `TvSettingsLayout.kt` (TV) switch exhaustively over `Preference` subtypes (note TV currently omits `PreferenceDynamicSelect`, `PreferenceIntSelect`, `PreferenceAppLanguage`, and `PreferenceFileEdit`, relying on `supportedDeviceTypes = listOf(DeviceType.PHONE)` — `TvSettingsPreference`'s `when` does not handle them if shown on TV).
- **`SharedPreferences` listeners and migrations.** `AppPreferences.observe` uses `OnSharedPreferenceChangeListener` inside `callbackFlow` (`SharedPreferences` holds weak references to listeners, so keep the flow collected). Startup migrations that flip a default (`migrateMpvAudioOutput`, `migrateMediaSegmentActions`) must keep a one-shot guard flag so a user's deliberate choice afterwards is not overwritten on the next launch.
