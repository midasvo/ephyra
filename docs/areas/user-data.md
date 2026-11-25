# User data and sync

Status: works on phone and TV; no open bugs known. As of 2026-11-23 (`main` at `26e62f27a`).

## Start here

- The rules (played, favourite, resume position, last played, who wins in a sync) are `PlayState`, `PlayStop` and `SyncMerge` in `domain/.../domain/userdata/` and nowhere else (rule R14). Times are parameters; nothing there reads a clock.
- The writes are in `MediaRepositoryImpl` (`markAsPlayed`, `markAsUnplayed`, `markAsFavorite` of the role `UserDataUpdates`; `postPlaybackProgress`, `postPlaybackStop` of the role `PlaybackReports`, #967), through `reportUserData` and `writeAndReport` (`UserDataReport.kt` in `application`, over the store role `DownloadUserData` since #966): write the row, mark it `toBeSynced`, ask the server, unmark when it answered.
- What is owed to the server is `PendingUserDataStore` (port in `application`, Room in `data`). `UserDataSync` and `SyncWorker` in `core` send it, through `UserDataSyncPort` (`JellyfinUserDataSync`). The current user's marks go through `UserDataPort` (`JellyfinUserData`).
- Only downloads have a row; for any other item a write is the server call alone. State is per user: `AccountKey(serverId, userId)`.
- Test fast: `./gradlew :domain:test :data:testDebugUnitTest`, and `./gradlew :core:testLibreDebugUnitTest --tests '*Sync*'`.

## What it does

Played, favourite, resume position and last played date belong to a user and an item. The server keeps them. For a download the app keeps a copy in Room (`UserDataEntity`, table `userdata`), so the item can be watched and marked without the server.

A change goes to the row first and is marked `toBeSynced` in the same transaction. Then the request goes out. If it reaches the server, the mark is taken off. If not, the mark stays and `UserDataSync.request` asks WorkManager for a `SyncWorker` run. The worker visits each saved server and user with marked rows, reads what the server has, lets `PlayState.mergeWithServer` decide and sends. If another device played the item later, the server's progress replaces the local one (`SyncMerge.TakeServer`); otherwise the local state is sent, with its date only when newer (`SyncMerge.SendLocal`). The worker then refreshes the rows of downloads from the server, except the item open in the player.

Playback reports are the other flow: [playback-reporting](playback-reporting.md). Its progress and stop write user data through the same `reportUserData`; a stop becomes a `PlayState` through `PlayStop.of`, with the server's resume thresholds (#637).

## Where the code is

| Path | What |
|---|---|
| [`domain/.../domain/userdata/PlayState.kt`](../../domain/src/main/kotlin/dev/jdtech/jellyfin/domain/userdata/PlayState.kt) | `markedPlayed`, `markedUnplayed`, `afterUnplayedReport`, `withFavorite`, `progressed`, `stopped` |
| [`domain/.../domain/userdata/PlayStop.kt`](../../domain/src/main/kotlin/dev/jdtech/jellyfin/domain/userdata/PlayStop.kt), [`SyncMerge.kt`](../../domain/src/main/kotlin/dev/jdtech/jellyfin/domain/userdata/SyncMerge.kt) | What a stop writes; `mergeWithServer`, `refreshedFromServer` |
| [`data/.../repository/MediaRepositoryImpl.kt`](../../data/src/main/java/dev/jdtech/jellyfin/repository/MediaRepositoryImpl.kt) | The writes, `reportUserData`, `currentUserId` |
| [`application/.../repository/UserDataReport.kt`](../../application/src/main/kotlin/dev/jdtech/jellyfin/repository/UserDataReport.kt) | `writeAndReport`, over the role `DownloadUserData` (#966) |
| [`application/.../repository/DownloadUserData.kt`](../../application/src/main/kotlin/dev/jdtech/jellyfin/repository/DownloadUserData.kt) | `writeAndMarkToBeSynced` (one transaction, gives `MarkedUserData`), `markToBeSynced`, `clearToBeSynced`, `updatePlayState`; Room in `RoomDownloadStore` |
| [`data/.../database/ServerDatabaseDao.kt`](../../data/src/main/java/dev/jdtech/jellyfin/database/ServerDatabaseDao.kt) | `updatePlayState`, `writeAndMarkToBeSynced`, `clearUserDataToBeSynced` |
| [`application/.../repository/PendingUserDataStore.kt`](../../application/src/main/kotlin/dev/jdtech/jellyfin/repository/PendingUserDataStore.kt) | The sync's store port; Room in [`data/.../database/RoomPendingUserDataStore.kt`](../../data/src/main/java/dev/jdtech/jellyfin/database/RoomPendingUserDataStore.kt) |
| [`core/.../work/UserDataSync.kt`](../../core/src/main/java/dev/jdtech/jellyfin/work/UserDataSync.kt) | The `UserDataSyncRequester`: unique work, restart when the server is back |
| [`core/.../work/SyncWorker.kt`](../../core/src/main/java/dev/jdtech/jellyfin/work/SyncWorker.kt) | `doWork`, `syncUserData`, `refreshFromServer`, retry rules |
| [`domain/.../domain/ports/UserDataPort.kt`](../../domain/src/main/kotlin/dev/jdtech/jellyfin/domain/ports/UserDataPort.kt), [`UserDataSyncPort.kt`](../../domain/src/main/kotlin/dev/jdtech/jellyfin/domain/ports/UserDataSyncPort.kt) | The server ports |
| [`server/jellyfin/.../JellyfinUserData.kt`](../../server/jellyfin/src/main/java/dev/jdtech/jellyfin/server/jellyfin/JellyfinUserData.kt), [`JellyfinUserDataSync.kt`](../../server/jellyfin/src/main/java/dev/jdtech/jellyfin/server/jellyfin/JellyfinUserDataSync.kt) | The Jellyfin adapters |
| [`domain/.../models/AccountKey.kt`](../../domain/src/main/kotlin/dev/jdtech/jellyfin/models/AccountKey.kt) | One user on one server |

## Rules

- **R14**: the play state rules are declared in `domain` alone; `data` and the sync compare no dates. A new rule goes into `PlayState`.
- **R12**: tick conversions are `Ticks`. **R10**: ids are value classes.
- **R4, R9**: only `data` imports `ServerDatabaseDao`; only the apps depend on `data`. The sync reaches Room through `PendingUserDataStore`, the repository through `DownloadUserData` (#966: `MediaRepositoryImpl` names no DAO).
- **R1, R2**: no SDK and no `JellyfinApi` outside `server/jellyfin`.
- A Room migration gets an independent review when no bot reviewed the PR (`CLAUDE.md`).

## How to test it

```bash
./gradlew :domain:test :application:test :architecture:test   # PlayStateTest, PlayStopTest, SyncMergeTest, R14
./gradlew :data:testDebugUnitTest --tests '*PlayStateWritesTest' --tests '*PendingUserDataSyncTest' --tests '*UserDataReportTest'
./gradlew :core:testLibreDebugUnitTest --tests '*Sync*'       # SyncWorkerRunTest, SyncWorkerDecisionsTest, LastPlayedDateSyncTest
```

On a device (`docs/dev-environment.md`, `test-server` skill): users `tester` and `second` on one server, a downloaded Short Movie for both (so both have a row), Resume Show for positions. Marking items on the server is free during tests.

- Server away: `adb root`, `adb shell iptables -I OUTPUT -d 10.0.2.2 -p tcp --dport 8096 -j REJECT --reject-with tcp-reset` (`-D` undoes it). Mark the item played, unplayed, favourite; watch part of it. The row keeps `toBeSynced`; with the server back the sync sends it.
- A request held open (#907): `tools/test-server/forward.py --hold <METHOD> <path-prefix>` holds one chosen request until Enter or `--release-file` (its rate limit slows only long answers). Or hold the call in a unit test, with a fake whose call suspends or switches the user (`PlayStateWritesTest`), and read the rows. On the phone the race did not show (emulator, a proxy holding `DELETE /UserPlayedItems` 15 s, switch from Home): the mark runs in the item page's `viewModelScope`, and the switch is on Home only, so the page and its mark are most likely gone before the server answers.
- Look at the rows with `run-as nl.midasvo.findroid.ce.debug` on the database: `userdata` has `played`, `favorite`, `playbackPositionTicks`, `lastPlayedDate`, `toBeSynced`.

## Before a PR

```bash
./gradlew ktfmtCheck
./gradlew :domain:test :application:test :architecture:test
./gradlew :data:testDebugUnitTest :core:testLibreDebugUnitTest
```

A new rule in `PlayState` comes with a test in `PlayStateTest` or `SyncMergeTest`. Then run the server-away steps on an emulator. A change to how `SyncWorker` treats the item open in the player (`playingHere`) also needs [player](player.md) checked.

## Decisions and history

- [#281](https://github.com/midasvo/ephyra/issues/281), [#552](https://github.com/midasvo/ephyra/issues/552): no offline mode; rows are written and marked first; the sync runs when the server is back.
- [#584](https://github.com/midasvo/ephyra/issues/584): the sync ends a stale play session of this device.
- [#633](https://github.com/midasvo/ephyra/issues/633), [#635](https://github.com/midasvo/ephyra/issues/635): last played date in the sync; the server's newer progress wins; the refresh of downloads.
- [#637](https://github.com/midasvo/ephyra/issues/637): the local stop follows the server's resume thresholds.
- [#762](https://github.com/midasvo/ephyra/issues/762), [#770](https://github.com/midasvo/ephyra/issues/770): the sync uses `UserDataSyncPort`, by server type.
- [#898](https://github.com/midasvo/ephyra/issues/898) / [#906](https://github.com/midasvo/ephyra/pull/906): `PlayState` owns the rules. [#899](https://github.com/midasvo/ephyra/issues/899): `PendingUserDataStore`, `AccountKey`.

## Pitfalls

- **Read the user once per write (#907).** `currentUserId` is a getter on `server.session.userId`: every read can answer another user after a profile switch. `markAsUnplayed` used to read it again after the request, so a switch while the request waited cleared the new user's date; it now reads it once and hands it to `reportUserData`. A new write that touches the row after a server call does the same.
- **A request is pinned to its server, the user id is not.** `JellyfinUserData` keeps the `ActiveServer` it was made for; the id used for the local row is a separate read.
- **An unplayed mark keeps the last-played date until the server has it** (`markedUnplayed`, `afterUnplayedReport`): the sync needs it to see the mark is not older than the play.
- **The server's date is a local date-time**; `toPlayState` and `epochMillisOf` in `SyncWorker.kt` convert it.
- **The refresh leaves marked rows and the open item alone**; overwriting them would lose offline progress (#635).
- **A write for a streamed item is the server call only.** If it fails, it is lost.
- **A sync asked during a run does nothing** (unique work, `KEEP`); `needsAnotherRun` covers a row marked meanwhile. A refused login (401) leaves the row marked for the next sync.
