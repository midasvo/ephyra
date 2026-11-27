# Downloads

Status: built and in use on phones; the TV app has no downloads. No known bug is open, with the fix of [#912](https://github.com/midasvo/ephyra/issues/912) in [#940](https://github.com/midasvo/ephyra/pull/940). As of 2026-11-23 (`main` at `26e62f27a` plus that pull request).

## Start here

- The queue is `DownloadQueue` (`downloads/.../core/presentation/downloader/`); the bytes are `MediaDownloadEngine` (`downloads/.../utils/download/`); the rows are `DownloaderImpl` through the roles of the download store (#967: `DownloadList`, `DownloadedItemRows`, `DownloadSources`, `DownloadMediaStreams`, `DownloadSegmentsAndTrickplay`, `DownloadUserData`, `QueuedDownloads`).
- Finished or not is `DownloadState` in `domain`: only it names the `.download` suffix (rule R13).
- `downloads` may not use `core`, `data` or an adapter (rule R15): what it needs from them comes through a port in `application` (the roles of the download store, `DownloadSettings`, `CurrentServerId`), and what `core` / `modes/film` need from `downloads` goes through `DownloadQueuePort`, `DownloadDeleter`, and `SmartDownloadChecker` in `application` (so `app/tv` binds `None` without linking `downloads`, #1048).
- Test fast: `./gradlew :downloads:testDebugUnitTest`. On a device: user `tester` (may download) and `nodownload`, Plain Movie or Short Movie, `iptables` to cut the server.
- A download that failed for want of the server goes on by itself when the server is back (`ServerAvailability`, [#912](https://github.com/midasvo/ephyra/issues/912)); one the server refused stays failed.

## What it does

A download keeps an item (a movie or an episode) on the device so it plays without the server. The user picks a quality in `DownloadQualityDialog`. `DownloadQueue` schedules the downloads and runs a few at a time. `MediaDownloadEngine` writes each file over OkHttp and can resume a cut-off transfer. A file is written as `<path>.download` and renamed when it is done: the name says whether it is finished (`DownloadState`). Smart downloads keep the next episodes of a show ready and remove the ones you have watched. There is no offline mode ([#281](https://github.com/midasvo/ephyra/issues/281)): when the server fails or takes over 3 s, the repository shows the Room copy of a downloaded item (`StoredFallback.kt`), and playback picks a finished download before it asks the server.

## Where the code is

| Path | What |
|---|---|
| [`downloads/.../core/presentation/downloader/DownloadQueue.kt`](../../downloads/src/main/java/dev/jdtech/jellyfin/core/presentation/downloader/DownloadQueue.kt) | The scheduler: entries, slots, auto-retry, restore after process death |
| [`downloads/.../utils/DownloaderImpl.kt`](../../downloads/src/main/java/dev/jdtech/jellyfin/utils/DownloaderImpl.kt) | `Downloader`: stores the item rows, starts the engine, finalizes, deletes, the orphan sweep rules |
| [`downloads/.../utils/download/MediaDownloadEngine.kt`](../../downloads/src/main/java/dev/jdtech/jellyfin/utils/download/MediaDownloadEngine.kt) | The OkHttp engine: Range resume, pause on network rules, HTTP errors |
| [`downloads/.../utils/download/ResumeValidators.kt`](../../downloads/src/main/java/dev/jdtech/jellyfin/utils/download/ResumeValidators.kt) | `If-Range` validator per partial file, so a changed source is not appended to ([#850](https://github.com/midasvo/ephyra/issues/850)) |
| [`downloads/.../core/presentation/downloader/SmartDownloads.kt`](../../downloads/src/main/java/dev/jdtech/jellyfin/core/presentation/downloader/SmartDownloads.kt), [`SmartDownloadPlanner.kt`](../../downloads/src/main/java/dev/jdtech/jellyfin/core/presentation/downloader/SmartDownloadPlanner.kt) | Smart downloads; the planner is pure and tested |
| [`downloads/.../core/presentation/downloader/DownloadPumpService.kt`](../../downloads/src/main/java/dev/jdtech/jellyfin/core/presentation/downloader/DownloadPumpService.kt) | Foreground service that keeps the process alive while the queue has work |
| [`downloads/.../work/`](../../downloads/src/main/java/dev/jdtech/jellyfin/work/) | `OrphanSweepWorker`, `ImagesDownloaderWorker` |
| [`downloads/.../di/`](../../downloads/src/main/java/dev/jdtech/jellyfin/di/) | The module's Hilt bindings |
| [`domain/.../models/DownloadState.kt`](../../domain/src/main/kotlin/dev/jdtech/jellyfin/models/DownloadState.kt) | `DownloadState` and `DownloadFiles`: the only place that knows the `.download` suffix |
| [`application/.../repository/DownloadSources.kt`](../../application/src/main/kotlin/dev/jdtech/jellyfin/repository/DownloadSources.kt) | One of the roles of the download store (#967: `DownloadList`, `DownloadedItemRows`, `DownloadSources`, `DownloadMediaStreams`, `DownloadSegmentsAndTrickplay`, `DownloadUserData`, `QueuedDownloads`); the row types are in `StoredDownloads.kt`; Room implementation in [`data/.../database/RoomDownloadStore.kt`](../../data/src/main/java/dev/jdtech/jellyfin/database/RoomDownloadStore.kt) |
| [`application/.../repository/DownloadSettings.kt`](../../application/src/main/kotlin/dev/jdtech/jellyfin/repository/DownloadSettings.kt), [`CurrentServerId.kt`](../../application/src/main/kotlin/dev/jdtech/jellyfin/repository/CurrentServerId.kt) | The settings the engine reads, as ports; implemented over `AppPreferences` in [`core/.../di/AppPreferencesPorts.kt`](../../core/src/main/java/dev/jdtech/jellyfin/di/AppPreferencesPorts.kt) ([#926](https://github.com/midasvo/ephyra/pull/926)) |
| [`application/.../repository/ServerAvailability.kt`](../../application/src/main/kotlin/dev/jdtech/jellyfin/repository/ServerAvailability.kt) | The port that says the server was found to answer again; `ServerReachability` in `core` implements it, `DownloadQueue` collects it ([#912](https://github.com/midasvo/ephyra/issues/912)) |
| [`application/.../repository/StoredFallback.kt`](../../application/src/main/kotlin/dev/jdtech/jellyfin/repository/StoredFallback.kt) | The Room copy when the server fails or is slow (3 s) |
| [`downloads/.../utils/download/DownloadTexts.kt`](../../downloads/src/main/java/dev/jdtech/jellyfin/utils/download/DownloadTexts.kt) | Texts from `core`'s resources; implemented by [`app/phone/.../utils/CoreDownloadTexts.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/utils/CoreDownloadTexts.kt) (with [`core/.../utils/DownloadErrorText.kt`](../../core/src/main/java/dev/jdtech/jellyfin/utils/DownloadErrorText.kt) for `downloadErrorText` and `DownloadText.toUiText`) |
| [`application/.../repository/DownloadQueuePort.kt`](../../application/src/main/kotlin/dev/jdtech/jellyfin/repository/DownloadQueuePort.kt), [`DownloadDeleter.kt`](../../application/src/main/kotlin/dev/jdtech/jellyfin/repository/DownloadDeleter.kt), [`SmartDownloadChecker.kt`](../../application/src/main/kotlin/dev/jdtech/jellyfin/repository/SmartDownloadChecker.kt) | Ports consumed by `core` and `modes/film`; implemented in `downloads`, bound as `.None` on TV in [`app/tv/.../di/TvDownloadsModule.kt`](../../app/tv/src/main/java/dev/jdtech/jellyfin/di/TvDownloadsModule.kt) (#1048) |
| [`core/.../presentation/downloader/DownloaderViewModel.kt`](../../core/src/main/java/dev/jdtech/jellyfin/core/presentation/downloader/DownloaderViewModel.kt) | Collects `DownloadQueuePort.queueEntries` into `DownloaderState` for the item screens |
| [`modes/film/.../presentation/downloads/`](../../modes/film/src/main/java/dev/jdtech/jellyfin/film/presentation/downloads/) | `DownloadsViewModel`, `DownloadedShowViewModel` |
| [`app/phone/.../presentation/film/DownloadsScreen.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/DownloadsScreen.kt), [`DownloadedShowScreen.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/DownloadedShowScreen.kt) | The Downloads screens |
| [`app/phone/.../presentation/film/components/DownloadQualityDialog.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/components/DownloadQualityDialog.kt) | The quality asked for each download; also `DownloadRow`, `DownloaderCard`, `DownloadProgressButton`, `ItemButtonsBar` there |
| [`core/.../core/reachability/`](../../core/src/main/java/dev/jdtech/jellyfin/core/reachability/) | `ServerReachability`: whether the server answers. Not reachable from `downloads` (R15) |

How the screens use it: a ViewModel that downloads gets the `Downloader` interface injected. `DownloaderViewModel` turns the queue's entries into `DownloaderState`. The storage location is `AppPreferences.downloadStorageIndex`, so a download never asks for one; it does ask for a quality (`DownloadQualityDialog`). `ItemButtonsBar` takes `canDownload` and `isDownloaded` overrides, so a season or a show passes a value computed over its episodes instead of `item.isDownloaded()` (which reads `DownloadState`). The Downloads screens never ask the server: they read `DownloadList`, `DownloadSources` and `ItemLists.getDownloads` (`MediaRepositoryImpl` reads the movies and shows through the store role `StoredItems`, #966). Screens that need the server show the unreachable banner. Without the server, `MediaRepositoryImpl.getPlaybackSources` picks a finished download before it asks the server, and user data written meanwhile is marked `toBeSynced` (`PendingUserDataStore`) and sent later by `UserDataSync` and `SyncWorker`.

Strings: the English CE strings of the module are in `downloads/src/main/res/values/strings.xml`, their translations in `values-<lang>/strings_ce.xml`.

## Rules

- **R13**: only `DownloadState.kt` names the `.download` suffix, also inside SQL. Ask `ItemSource.downloadState` or `DownloadFiles`.
- **R15**: `downloads` does not depend on `data`, `server/jellyfin`, `core` or an app outside its tests; no file outside `downloads` names `DownloaderImpl`, `MediaDownloadEngine`, `ResumeValidatorStore`, `SharedPreferencesResumeValidatorStore`, `DownloadHttpClient` or `ImagesDownloaderWorker`. Others use `Downloader`, `DownloadQueue`, `SmartDownloads`.
- **R9, R4**: only the apps depend on `data`; only `data` imports `ServerDatabaseDao`. Room access goes through the roles of the download store.
- **R10**: ids are value classes (`ItemId`, `MediaSourceId`, `MediaStreamId`, … in `domain/.../models/Ids.kt`); no `java.util.UUID` and no `String` id outside the adapter. Don't add a line to `architecture/baseline/` to get past it.
- **R1, R2**: no SDK and no `JellyfinApi` here; the engine gets its URLs through the repository's roles (`PlaybackMedia`, `ItemCatalog`, `CurrentAccount`, #967).
- Project rules (`CLAUDE.md`): the `libre` flavour stays free of Google code; every CE string in 36 languages (`translate-strings` skill); a Room migration gets an independent review when no bot reviewed the PR.

## How to test it

Unit tests, pure modules first:

```bash
./gradlew :domain:test :application:test :architecture:test   # DownloadStateTest, R13, R15
./gradlew :downloads:testDebugUnitTest                         # DownloadQueue*Test, MediaDownloadEngine*Test, ResumeValidatorsTest, SmartDownloadPlannerTest, OrphanSweep*Test, DownloadTrickplayRestartTest
./gradlew :application:test                                    # StoredFallbackTest, MediaRepositoryImplTest (the repository over fakes)
./gradlew :data:testDebugUnitTest                              # StoredItemFallbackTest, UnfinishedDownloadPlaybackTest (the repository over Room)
./gradlew :modes:film:testDebugUnitTest                        # DownloadsViewModelUserSwitchTest, QueueCountsTest, EpisodeDownloadSelectionTest
./gradlew :core:testLibreDebugUnitTest                         # DownloadErrorTextTest (core has a libre flavour)
./gradlew :app:phone:testLibreDebugUnitTest                    # DownloadQualityDialogTest, DownloadStatusTextTest, ServerUnreachableTest
```

One class: add `--tests '*MediaDownloadEngineResumeTest*'`. The `downloads` tests build the real stores over Room (`testImplementation(projects.data)`) and fake the settings with `TestDownloadSettings`.

On a device: the test server (`docs/dev-environment.md`, `tools/test-server/setup.sh`, `test-server` skill) at `http://10.0.2.2:8096` from an emulator.

- Users: `tester` may download; `nodownload` may not (no download button: the server's `canDownload`). Use your own user when someone else tests at the same time (`python3 tools/test-server/user.py add <name>`).
- Items: Plain Movie and Short Movie (the normal case, a quick one); Versions Movie (two versions); Tracks Movie (external `.de.srt`, finalized with the video, [#573](https://github.com/midasvo/ephyra/issues/573)); Image Subs Movie and Tracks Show at a 1.5 Mbps quality (a transcoded download); Test Show, Long Season Show and Resume Show (season downloads, smart downloads); Empty Season Show (nothing to download).
- Without the server, internet still up (phone or tablet AVD): `adb root`, then `adb shell iptables -I OUTPUT -d 10.0.2.2 -p tcp --dport 8096 -j REJECT --reject-with tcp-reset`; `-D` instead of `-I` brings it back. Use the port the app is logged in on: 8097 when it goes through `forward.py` (below). That shows the unreachable banner, the stored fallback and the queue's failure path.
- A slow transfer, to pause or cut one mid-way: `python3 tools/test-server/forward.py 8097 8096 600` (600 kbit/s) and log in to `http://10.0.2.2:8097`; stop the script to cut the connection.
- No network at all: `adb shell cmd connectivity airplane-mode enable`; a queued download waits for the network ([#530](https://github.com/midasvo/ephyra/issues/530)).
- Restart during a download: on the next start `DownloadQueue.restoreAll` resumes from the partial file. The debug build is `nl.midasvo.findroid.ce.debug`.
- Scripted taps: dump the screen (`uiautomator dump`) and check it before each tap.

## Before a PR

```bash
./gradlew ktfmtCheck
./gradlew :domain:test :application:test :architecture:test
./gradlew :downloads:testDebugUnitTest :data:testDebugUnitTest :modes:film:testDebugUnitTest
./gradlew :app:phone:compileLibreDebugKotlin
python3 .claude/skills/translate-strings/scripts/check-translations.py   # when strings changed
```

Or all of it: `./gradlew testDebugUnitTest`.

Then on an emulator: start a download, cut the server off with `iptables` (see above), bring it back, and check what the queue shows.

## Decisions and history

- [#38](https://github.com/midasvo/ephyra/pull/38): Android's DownloadManager replaced by the OkHttp engine with resume ([#26](https://github.com/midasvo/ephyra/issues/26)).
- [#116](https://github.com/midasvo/ephyra/issues/116): smart downloads.
- [#281](https://github.com/midasvo/ephyra/issues/281): no Offline Mode; stored copies while the server is away, reachability banner.
- [#560](https://github.com/midasvo/ephyra/issues/560): the pump service runs as long as the queue has work.
- [#718](https://github.com/midasvo/ephyra/issues/718) (open) and [#724](https://github.com/midasvo/ephyra/pull/724): stored copies looked up by the current server.
- [#848](https://github.com/midasvo/ephyra/issues/848): a failed finalize is not shown as completed.
- [#850](https://github.com/midasvo/ephyra/issues/850): `If-Range` on resume.
- [#897](https://github.com/midasvo/ephyra/issues/897): `DownloadState` instead of the suffix in every file (R13).
- [#899](https://github.com/midasvo/ephyra/issues/899) / [#911](https://github.com/midasvo/ephyra/pull/911): the rows behind the download store (split by role in #967).
- [#912](https://github.com/midasvo/ephyra/issues/912): a failed entry flagged `retryWhenServerReturns` goes back to Pending on `ServerAvailability.serverConfirmed` (a probe that reached the server, or a request answered while it counted as unreachable).
- [#901](https://github.com/midasvo/ephyra/issues/901) / [#914](https://github.com/midasvo/ephyra/pull/914): `downloads` as its own module (R15).
- [#918](https://github.com/midasvo/ephyra/issues/918) / [#926](https://github.com/midasvo/ephyra/pull/926): the engine reads its settings through `DownloadSettings` (part 2; part 1 and rule R17 still open).
- [#1002](https://github.com/midasvo/ephyra/issues/1002): `DownloadQueue.startDownload` keeps the pending row until `downloadItem` writes a source row, so a start that fails online while the server is away survives process death (`restoreAll` skips any pending row whose item already has an active source).
- ADRs: [`docs/architecture/adr/0002-hexagonal-ddd.md`](../architecture/adr/0002-hexagonal-ddd.md); T5, T7 and T9 in [`docs/architecture/5-hexagonal-ddd.md`](../architecture/5-hexagonal-ddd.md). Proposed: [`docs/architecture/adr/0003-playback-and-download-use-cases.md`](../architecture/adr/0003-playback-and-download-use-cases.md) ([#970](https://github.com/midasvo/ephyra/issues/970)), the life of one download as a state machine in `domain` and the queue as a service in `application`, without a Room schema change.

## Pitfalls

- **A failed download resumes by itself only when the server gave no answer.** `DownloadQueue` flags a failure at the start (also after an app restart with the server away) and an engine failure with no HTTP status once its three auto-retries are used (30 s, 2 min, 10 min, `afterFailure`); `ServerAvailability.serverConfirmed` puts them back to Pending ([#912](https://github.com/midasvo/ephyra/issues/912)). A refusal (an HTTP status, a failed finalize) stays failed until Retry. It only fires while the app is in front: the reachability monitor does not probe in the background. A start that failed for a local reason (storage) is flagged too and fails again at once.
- **One item id is stored for one server.** The item tables have the item id as primary key: a download of the same id from a second server replaces the first one's copy, and the queue dedups by item id ([#718](https://github.com/midasvo/ephyra/issues/718)).
- **Re-downloading does not refresh metadata**: the item inserts in `ServerDatabaseDao` use `OnConflictStrategy.IGNORE` (data-02 in [`docs/open-decisions.md`](../open-decisions.md)).
- **The engine is in-process.** It does not survive process death; `restoreAll` puts the partial downloads back as pending and resumes them by Range, and restores queued downloads whose start had not written a source row yet. Without `DownloadPumpService` the transfers stop when the app is closed.
- **Trickplay across restarts and from lists**: `downloadTrickplayData` writes each sheet to disk before inserting the `trickplayInfos` row, and `DownloaderImpl` ensures missing trickplay on resume and finalization (resolving from `ItemCatalog` when the caller or Room copy lacked it, [#1015](https://github.com/midasvo/ephyra/issues/1015)).
- **External subtitles** have their own `.download` name and finalize step ([#573](https://github.com/midasvo/ephyra/issues/573)); check them when you change finalizing.
- **The orphan sweep deletes files**: a finished file with no row goes after 24 h (`ORPHAN_MIN_AGE_MS`). A path change that the rows do not follow loses downloads.
- **`DownloadsViewModel`'s reads, sorting and storage walk run on `ioDispatcher`** (`Dispatchers.IO` in the app). A test passes its own test dispatcher, as `DownloadsViewModelUserSwitchTest` does: on the real IO threads a load the test had already left (cancelled by a newer one) went on after the test, and its return to `Dispatchers.Main` after `resetMain()` failed whichever `runTest` came next in the module, often `FavoritesViewModelUserSwitchTest` or `MediaViewModelUserSwitchTest` ([#921](https://github.com/midasvo/ephyra/issues/921)). A new test of a ViewModel in `modes/film` does the same: no real dispatcher, everything on the test scheduler.
- **Downgrade**: an older build over a newer Room schema can drop the database, the downloads' rows and the login with it (`docs/dev-environment.md`).
