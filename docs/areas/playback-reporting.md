# Playback reporting

Status: works on phone, TV and Cast; no open bugs known. As of 2026-11-23 (`main` at `26e62f27a`).

## Start here

- The local player's reports come from `PlaybackReporter` (`player/local/.../domain/`), which `PlayerViewModel` builds and tells what happened. `PlaybackStartGate` holds a start until the item has loaded; `playbackStopReport` decides which stop is owed.
- The chain: `PlaybackReporter` to `PlaybackReports.postPlaybackStart/Progress/Stop` (`MediaRepositoryImpl`) to `PlaybackPort.reportStart/Progress/Stopped` with a `PlaybackReport` to `JellyfinPlayback`, which builds the SDK's `PlaybackStartInfo`, `PlaybackProgressInfo` and `PlaybackStopInfo`.
- What plays per item is `PlaySessions` (`domain/.../repository/PlaySessions.kt`): the play session and the version as one `PlayingStream` (#909), and the play method (Transcode with the transcode's session, else DirectPlay without one). The way through the layers: the `PlayerItem` → `PlaybackReporter.onStreamQueued` → `PlaybackReports.setPlaySession` → `PlaySessions` → `MediaRepositoryImpl.postPlayback*` builds the `PlaybackReport` → `JellyfinPlayback`.
- Other senders: TV's `TvProgressReports` (loop only), Cast's `CastPlaybackReporter` (proprietary flavour).
- Rules: R1, R2 (no SDK outside the adapter), R12 (`Ticks`), R14 (`PlayStop` and `PlayState`), R6 (no engine package).
- Test fast: `./gradlew :player:local:testDebugUnitTest --tests '*PlaybackReporter*' --tests '*PlaybackStartGate*' --tests '*PlaybackStopReport*'`; request bodies: `./gradlew :server:jellyfin:testDebugUnitTest --tests '*PlaybackReportRequestTest'`.

## What it does

While an item plays, the app tells the server three things, so its dashboard, "now playing", resume position and played state follow.

- **Start** (`POST /Sessions/Playing`): once per item, after the player has loaded it (#601), with the position (#868).
- **Progress** (`.../Progress`): every 5 s while playing, and at once on pause and resume (#674). One at a time; a newer one replaces a waiting one.
- **Stop** (`.../Stopped`): when the item is left (next, previous, end, close). It carries the play session, so the server ends a transcode at once.

Order matters, in two ways. Between items: the previous item's stop must reach the server before the next item's start, or it can clear the session's now-playing item; `stopJob?.join()` in `PlayerViewModel.onMediaItemTransition` holds the start until then. Within one item: a stop waits up to 5 s for its own start (`START_REPORT_WAIT_MS`), and progress waits for the start.

Progress and stop also write user data through `reportUserData`: a downloaded item gets its position and a `toBeSynced` mark ([user-data](user-data.md)). The start writes nothing locally.

With the server away, `PlaybackReporter` catches and logs each failure; playback goes on. A download's progress and stop are kept in its row and sent by `SyncWorker`, which also ends a play session the server still shows for this device (#584). A streamed item has no row: a lost report is lost.

## Where the code is

| Path | What |
|---|---|
| [`player/local/.../domain/PlaybackReporter.kt`](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/domain/PlaybackReporter.kt) | `onItemTransition`, `onTransitionHandled`, `onStreamReplaced`, `sendPlaybackStart`, `reportStopped`, `reportProgress` |
| [`player/local/.../domain/PlaybackStartGate.kt`](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/domain/PlaybackStartGate.kt), [`PlaybackStopReport.kt`](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/domain/PlaybackStopReport.kt) | The start gate; which stop is owed |
| [`player/local/.../presentation/PlayerViewModel.kt`](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/presentation/PlayerViewModel.kt) | `onMediaItemTransition`, `swapInQueue`, `updatePlaybackProgress`, `reportedPlayer` |
| [`application/.../repository/PlaybackReports.kt`](../../application/src/main/kotlin/dev/jdtech/jellyfin/repository/PlaybackReports.kt) | `postPlaybackStart`, `postPlaybackProgress`, `postPlaybackStop`, `setPlaySession` |
| [`application/.../repository/MediaRepositoryImpl.kt`](../../application/src/main/kotlin/dev/jdtech/jellyfin/repository/MediaRepositoryImpl.kt) | Builds each `PlaybackReport`; progress and stop through `reportUserData` |
| [`domain/.../repository/PlaySessions.kt`](../../domain/src/main/kotlin/dev/jdtech/jellyfin/repository/PlaySessions.kt) | The transcode session per item; `playReport` |
| [`domain/.../domain/ports/PlaybackPort.kt`](../../domain/src/main/kotlin/dev/jdtech/jellyfin/domain/ports/PlaybackPort.kt) | `PlaybackReport` and the three report calls |
| [`server/jellyfin/.../JellyfinPlayback.kt`](../../server/jellyfin/src/main/java/dev/jdtech/jellyfin/server/jellyfin/JellyfinPlayback.kt) | The request bodies; `playMethod()` |
| [`application/.../repository/PlaybackEvents.kt`](../../application/src/main/kotlin/dev/jdtech/jellyfin/repository/PlaybackEvents.kt) | Tells screens an item was reported, so they reload |
| [`app/tv/.../ce/presentation/player/TvProgressReports.kt`](../../app/tv/src/main/java/dev/jdtech/jellyfin/ce/presentation/player/TvProgressReports.kt) | TV: the 5 s loop with a 1 s guard, used in `TvPlayerScreen.kt` |
| [`player/cast/src/proprietary/.../CastPlaybackReporter.kt`](../../player/cast/src/proprietary/java/dev/jdtech/jellyfin/player/cast/CastPlaybackReporter.kt) | Cast: which reports a receiver's state owes; `CastPlayerControllerImpl.kt` sends them. The "item ran out, position 0 for a moment" rule is `CastPositions.ranOut` in `src/main`, which the segment skip reads too (#851) |

On the phone the progress loop is in `PlayerActivity.kt` and a report on pause in `BasePlayerActivity.kt`. Both call `PlayerViewModel.updatePlaybackProgress`, as TV does.

## Rules

- **R1, R2**: SDK types stay in `server/jellyfin`; everything else uses `PlaybackReport`.
- **R12**: positions go through `Ticks`. **R14**: what a stop writes locally is `PlayStop` and `PlayState`. **R6**: `PlaybackReporter` reads the player through `ReportedPlayer`. **R10**: ids are `ItemId` and `MediaSourceId`.
- A report runs in `reportScope` (the application's), not the ViewModel's: the stop must outlive the player.
- **The previous item's stop goes before the next item's start.** `PlaybackReporter.sendPlaybackStart` waits for the stops still in flight (`stopsInFlight`) before it records and sends a start, whichever way the start comes: a transition, a ready state, a quality switch (a second transition on ExoPlayer, `onStreamReplaced` on mpv). A transition that a newer one overtook while it waited sends nothing (`ItemTransition`). Before #890 the order was kept only by `stopJob?.join()` in `PlayerViewModel.onMediaItemTransition`, and a switch went around it.
- `PlayerInvariantsTest` reads the source of `onMediaItemTransition` with a regex and expects `playbackReporter.onTransitionHandled(` in it: change that signature or call and the test fails; look there first.

## How to test it

```bash
./gradlew :player:local:testDebugUnitTest --tests '*PlaybackReporter*' --tests '*PlaybackStartGate*' --tests '*PlaybackStopReport*'
./gradlew :server:jellyfin:testDebugUnitTest --tests '*PlaybackReportRequestTest'
./gradlew :domain:test :application:test :architecture:test        # PlaySessionsTest, R1, R2, R12, R14
./gradlew :app:tv:testLibreDebugUnitTest --tests '*TvProgressReports*'
./gradlew :player:cast:testProprietaryDebugUnitTest --tests '*CastPlaybackReporterTest*'
```

Which test pins what: `PlaybackReportRequestTest` (`server/jellyfin`, Robolectric with a stand-in server) the JSON bodies; `PlaybackReporterTest` the order and timing, in virtual time; `PlaySessionsTest` what is stored per item; `PlayerInvariantsTest` the calls in `onMediaItemTransition`.

On a device: the test server (`test-server` skill, `docs/dev-environment.md`). What the server was told is in `GET /Sessions`: `NowPlayingItem`, `PlayState.MediaSourceId`, `PlayState.PlayMethod`, `PlayState.PositionTicks`. Call it with a DeviceId of your own in the `Authorization` header (`tools/test-server/jf.py` has the login and call helpers and its own DeviceId); a DeviceId the app uses signs the app out. The dashboard's activity log says only that something is playing: no version, no method.

- Versions Movie: pick a version, play, read the session (#909).
- Plain Movie: start, pause, resume, leave: one start, progress, one stop at the right position.
- A quality switch on both ExoPlayer and mpv: one new start after each switch (#870).
- Next while the server holds the stop (#890). The steps: cap the quality, play an episode, press Next (the proxy now holds that episode's stop), switch the next episode to Original quality while the stop is held, and let the stop through. The next episode must then have exactly one start, after the stop, with the switched stream's `PlayMethod` (DirectPlay). In a unit test: a fake whose `postPlaybackStop` suspends until the test releases it (`PlaybackReporterTest`), switch during the wait, count the starts and their play method. On a device: a proxy that holds `POST /Sessions/Playing/Stopped`: `tools/test-server/forward.py <port> 8096 --hold POST /Sessions/Playing/Stopped --log` (press Enter or create the `--release-file` to let it through; `--log` numbers each request and logs when the forwarder passed it on (close to, not proof of, the server's order across connections) and the first 1000 bytes of its body), with the app's address pointed at it (in the app's database, as root: `update serverAddresses set address='<proxy>' where address='<old>'`, and set it back after the test; without the `where` every saved server moves). Two traps for a proxy of your own on Windows: `SO_REUSEADDR` binds silently to a port another process already uses (`forward.py` uses `SO_EXCLUSIVEADDRUSE` there and refuses a busy port); and a hard close of the client socket at the server's EOF gives mpv "Connection reset by peer" on `master.m3u8` (ExoPlayer copes): half-close instead (`shutdown(SHUT_WR)`, which `forward.py` does since [#941](https://github.com/midasvo/ephyra/issues/941)). Have the proxy log every `/Sessions/Playing` request in order with its body, and count the starts and read each one's `PlayMethod` there: `GET /Sessions` shows only the current state, which the next progress report changes.
- A quality cap set during a test stays in the preferences (`pref_player_max_bitrate`): the next test then starts transcoded. Reset it to Auto first, or set a cap on purpose before a #890 test, so that the switch to Original changes the play method.
- Leave while the item loads: no start, progress or stop for it.

## Before a PR

```bash
./gradlew ktfmtCheck
./gradlew :domain:test :application:test :architecture:test
./gradlew :player:local:testDebugUnitTest :server:jellyfin:testDebugUnitTest :data:testDebugUnitTest
```

Anything under `player/local` runs `docs/mpv-checklist.md` on both backends ([player](player.md)). A change to `PlaybackReport` changes the pinned bodies in `PlaybackReportRequestTest`. A change in `player/cast` compiles in both flavours and needs a real receiver for anything about a receiver.

## Decisions and history

- [#461](https://github.com/midasvo/ephyra/issues/461), [#601](https://github.com/midasvo/ephyra/issues/601): a stop is owed once the start was sent; the start waits for the item to load.
- [#599](https://github.com/midasvo/ephyra/issues/599), [#728](https://github.com/midasvo/ephyra/issues/728): Transcode only with its session; the session follows the stream that plays.
- [#674](https://github.com/midasvo/ephyra/issues/674): pause and resume at once, in order. [#868](https://github.com/midasvo/ephyra/issues/868): the start carries the position.
- [#757](https://github.com/midasvo/ephyra/issues/757), [#765](https://github.com/midasvo/ephyra/issues/765): `PlaybackPort`; `PlaybackReporter` out of `PlayerViewModel`.
- [#870](https://github.com/midasvo/ephyra/issues/870) / [#889](https://github.com/midasvo/ephyra/pull/889): a quality switch on mpv reports its new stream (`onStreamReplaced`).
- [#846](https://github.com/midasvo/ephyra/issues/846): leaving without a stop still lets screens reload.

## Pitfalls

- **The reports name the version (#909).** `setPlaySession` records the version of the stream that is loaded with its play session, as one `PlayingStream` per item in `PlaySessions` (`domain/.../repository/PlaySessions.kt`), and every report sends it as `MediaSourceId`. A report reads the session and the version from that one value. Without it the server shows the item's first version. `PlaySessions` is keyed by item id: the last stream loaded for an item is the one named.
- **A start goes out after the previous item's stop, once (#890).** `onMediaItemTransition` takes the item before it waits for the previous item's stop. A quality or audio switch in that wait (ExoPlayer: a second transition; mpv: `onStreamReplaced`) used to send the new stream's start ahead of the stop, and the waiting transition a second start after it. Now `PlaybackReporter` numbers the transitions (`ItemTransition`): one handled after a newer one sends nothing. And `sendPlaybackStart` waits for the stops still being sent (`stopsInFlight`), whichever way the start comes. A new way into `sendPlaybackStart` keeps both.
- **A start before the stream is fetched says DirectPlay**; `PlaySessions.playReport` sends Transcode only with a known session, and the first progress corrects it.
- **A quality switch leaves the old transcode to the server's timer**: there is no call to end it.
- **No start, no progress.** `reportProgress` returns while `playing?.startJob` is null: the server marks an item without a runtime played at its first position report.
- **`PlaybackReporter` is not thread-safe**: the player's listener and the main thread only.
