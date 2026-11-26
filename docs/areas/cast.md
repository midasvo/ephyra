# Cast

Status: Google Cast in the `proprietary` flavour of the phone app (#626); `libre` and `app/tv` have no Cast SDK. As of 2026-11-25 (`main`).

## Start here

- Cast lives in `player/cast` (which has `libre` and `proprietary` flavours, plus `debug` for the emulator stand-in) and the phone UI in `app/phone/.../presentation/cast/`. `app/tv` does not depend on `player/cast`.
- Everything that touches the Cast SDK (`com.google.android.gms:play-services-cast-framework`, `androidx.mediarouter`) is in `player/cast/src/proprietary/`. `player/cast/src/libre/` provides stubs (`CastSessionManagerImpl`, `CastPlayerControllerImpl`) that report `CastAvailability.NOT_IN_BUILD`.
- `CastViewModel` (`player/cast/src/main/.../presentation/CastViewModel.kt`) feeds every Cast surface: the top bar button (`CastButton`), the device sheet (`CastDeviceSheet`), the mini player (`CastMiniPlayer`), and the expanded/side-panel player (`CastPlayer`). `CastHost` (`app/phone/.../presentation/cast/CastHost.kt`) wraps the navigation display and is the only Cast entry `NavigationRoot` knows.
- `server/jellyfin/.../server/jellyfin/cast/` builds the Chromecast device profiles (`Chromecast`, `ChromecastH265`) and resolves receiver stream URLs (`ReceiverSource`, `ReceiverUrls`).
- Test fast: `./gradlew :player:cast:testLibreDebugUnitTest :player:cast:testProprietaryDebugUnitTest` and `./gradlew :app:phone:compileLibreDebugKotlin :app:phone:compileProprietaryDebugKotlin`.

## What it does

In `proprietary` builds, `CastSessionManagerImpl` wraps the Play Services `CastContext` and `MediaRouter` to discover Cast receivers, track connection state (`CastConnectionState`), and connect or disconnect. When `CastAvailability` is checked, `CastSessionManager.availability` returns `CastAvailability.DISABLED` when the Cast setting (`castEnabled`) is off, `CastAvailability.NO_PLAY_SERVICES` when `CastContext` is unavailable, and `CastAvailability.AVAILABLE` otherwise (it does not depend on the current server; if the Cast button is hidden, check the setting first).

`CastPlayerControllerImpl` (`src/proprietary/`) loads items onto the receiver's `RemoteMediaClient` with `CastCustomData` (serialized `mediaSourceId`, `playSessionId`, `transcoded`, `defaultAudioStreamIndex`, and track lists — no stream URL or token, since the receiver already has the stream URL), handles play/pause/seek/queue navigation and track switches (`CastTracks`, `CastUrls`), and decides playback reports through `CastPlaybackReporter`. `CastPositions.ranOut` (`src/main/`) detects the brief position-0 report when an item runs out at the end, shared by `CastPlaybackReporter` and `CastSegmentSkipper` (#851, #994).

`CastSegmentSkipper` (`src/main/`) applies `SegmentTracker` from `player/local` to receiver position updates: auto-skipping segments configured to skip (seeking to `endTicks`, capped at `durationMs - END_MARGIN_MS` when `durationMs > 0L`; no seek occurs when the capped target is not ahead of the playback position) and showing the skip button for "ask" segments, while guarding against stale positions after a load or seek, foreign item reports, and the end-of-item position-0 report (#994).

In `libre` builds, `CastSessionManagerImpl` and `CastPlayerControllerImpl` are no-op stubs with `CastAvailability.NOT_IN_BUILD`, and `CastHost` renders its `content` directly without adding any Cast UI.

## Where the code is

| Path | What |
|---|---|
| [`player/cast/build.gradle.kts`](../../player/cast/build.gradle.kts) | `libre` and `proprietary` flavours; `proprietaryImplementation` for Cast Framework and MediaRouter |
| [`player/cast/src/main/.../CastSessionManager.kt`](../../player/cast/src/main/java/dev/jdtech/jellyfin/player/cast/CastSessionManager.kt), [`CastPlayerController.kt`](../../player/cast/src/main/java/dev/jdtech/jellyfin/player/cast/CastPlayerController.kt) | Shared interfaces for session discovery/connection and remote playback control |
| [`player/cast/src/main/.../presentation/CastViewModel.kt`](../../player/cast/src/main/java/dev/jdtech/jellyfin/player/cast/presentation/CastViewModel.kt) | Single `@HiltViewModel` per activity driving all Cast surfaces (`CastUiState`, `CastEvent`) |
| [`player/cast/src/main/.../presentation/CastSegmentSkipper.kt`](../../player/cast/src/main/java/dev/jdtech/jellyfin/player/cast/presentation/CastSegmentSkipper.kt), [`CastPositions.kt`](../../player/cast/src/main/java/dev/jdtech/jellyfin/player/cast/CastPositions.kt) | Segment auto-skip/button logic over receiver reports (#994); `CastPositions.ranOut` |
| [`player/cast/src/main/.../di/CastModule.kt`](../../player/cast/src/main/java/dev/jdtech/jellyfin/player/cast/di/CastModule.kt) | Provides `CastSessionManager` and `CastPlayerController`, using `CastStandIn` when active |
| [`player/cast/src/proprietary/.../CastPlayerControllerImpl.kt`](../../player/cast/src/proprietary/java/dev/jdtech/jellyfin/player/cast/CastPlayerControllerImpl.kt), [`CastSessionManagerImpl.kt`](../../player/cast/src/proprietary/java/dev/jdtech/jellyfin/player/cast/CastSessionManagerImpl.kt) | Real Cast SDK implementation (`RemoteMediaClient`, `CastContext`, `MediaRouter`) |
| [`player/cast/src/proprietary/.../CastCustomData.kt`](../../player/cast/src/proprietary/java/dev/jdtech/jellyfin/player/cast/CastCustomData.kt), [`CastPlaybackReporter.kt`](../../player/cast/src/proprietary/java/dev/jdtech/jellyfin/player/cast/CastPlaybackReporter.kt), [`CastOptionsProvider.kt`](../../player/cast/src/proprietary/java/dev/jdtech/jellyfin/player/cast/CastOptionsProvider.kt) | Queue item custom data (#767), receiver playback report decisions, notification/receiver options |
| [`player/cast/src/proprietary/.../CastTracks.kt`](../../player/cast/src/proprietary/java/dev/jdtech/jellyfin/player/cast/CastTracks.kt), [`CastUrls.kt`](../../player/cast/src/proprietary/java/dev/jdtech/jellyfin/player/cast/CastUrls.kt), [`CastPlaylistOrder.kt`](../../player/cast/src/proprietary/java/dev/jdtech/jellyfin/player/cast/CastPlaylistOrder.kt), [`LegacyPlaybackInfo.kt`](../../player/cast/src/proprietary/java/dev/jdtech/jellyfin/player/cast/LegacyPlaybackInfo.kt) | Track mapping, `castContentType` / `audioStreamIndexOf`, queue ordering, playback info parsing |
| [`player/cast/src/libre/.../`](../../player/cast/src/libre/java/dev/jdtech/jellyfin/player/cast/) | Stub `CastSessionManagerImpl` (`NOT_IN_BUILD`) and `CastPlayerControllerImpl` |
| [`player/cast/src/debug/.../FakeCast.kt`](../../player/cast/src/debug/java/dev/jdtech/jellyfin/player/cast/debug/FakeCast.kt) | Debug stand-in (`FakeCastModule`, `FakeCastSessionManager`, `FakeCastPlayerController`) gated by `debug.ephyra.cast` |
| [`app/phone/.../presentation/cast/CastHost.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/cast/CastHost.kt) | Wraps navigation content; hosts `CastMiniPlayer`, `CastDeviceSheet`, `CastPlayer`, `CastButton`, `CastDialogs`, `CastPlayback` |
| [`server/jellyfin/.../server/jellyfin/cast/`](../../server/jellyfin/src/main/java/dev/jdtech/jellyfin/server/jellyfin/cast/) | `Chromecast`, `ChromecastH265`, `ReceiverSource`, `ReceiverUrls` |

## Rules

- **R1, R2**: no Jellyfin SDK types or `JellyfinApi` outside `server/jellyfin`.
- **R10**: ids (`ItemId`, `MediaSourceId`) are value classes, serialized as strings in `CastCustomData`.
- **Flavour separation**: `libre` must stay free of Google Play Services and Cast SDK imports (`CastOptionsProvider` and `ReconnectionService` live only in `player/cast/src/proprietary/AndroidManifest.xml`). Every change in `player/cast/src/main/` or `app/phone/.../presentation/cast/` must compile in both `libre` and `proprietary` flavours (`compileLibreDebugKotlin` and `compileProprietaryDebugKotlin`).

## How to test it

```bash
./gradlew :player:cast:testLibreDebugUnitTest :player:cast:testProprietaryDebugUnitTest
./gradlew :app:phone:compileLibreDebugKotlin :app:phone:compileProprietaryDebugKotlin
```

Unit tests in `player/cast`:
- `src/test/`: `CastSegmentSkipperTest`, `CastViewModelSegmentTest` (runs in both `libre` and `proprietary` flavours using Robolectric and fakes).
- `src/testProprietary/`: `CastCustomDataTest`, `CastItemIdTest`, `CastPlaybackReporterTest`, `CastPlaylistOrderTest`, `CastReceiverItemsTest`, `CastTracksTest`, `CastUrlsTest`, `LegacyPlaybackInfoTest`.

On an emulator (`proprietary` debug build only): emulators find no real Cast receivers. Set the system property to enable the debug stand-in (`FakeCast.kt`):

```bash
adb shell setprop debug.ephyra.cast fake   # restart the app afterwards
```

Supported scenarios via `debug.ephyra.cast`: `fake` (normal connected devices), `fake:none` (no devices found), `fake:connectfail`, `fake:fail`, `fake:error`, `fake:drop`, `fake:lost`, `fake:end`, `fake:unavailable` (no Play Services).

**What cannot be tested without a real Cast receiver:**
- Actual device discovery, Cast session connection/reconnection, and standby behaviour (`onStandbyStateChanged`, #851).
- Real receiver media loading, HLS/MP4 playback, subtitle/audio track switching on the receiver, and receiver-emitted stale/foreign/end-of-item position reports (the stand-in moves its position immediately on seek and sends nothing to the server).

## Before a PR

```bash
./gradlew ktfmtCheck
./gradlew :player:cast:testLibreDebugUnitTest :player:cast:testProprietaryDebugUnitTest
./gradlew :app:phone:compileLibreDebugKotlin :app:phone:compileProprietaryDebugKotlin
```

## Decisions and history

- [#626](https://github.com/midasvo/ephyra/issues/626): `proprietary` flavour with Google Cast; `libre` stays free of Google code; single `CastViewModel` and `CastHost`.
- [#767](https://github.com/midasvo/ephyra/issues/767): `CastCustomData` keeps stream metadata on the receiver without URLs or tokens so a session can be picked up after process death.
- [#851](https://github.com/midasvo/ephyra/issues/851), [#994](https://github.com/midasvo/ephyra/pull/994): `CastSegmentSkipper` and `CastPositions.ranOut` for segment skipping during Cast; TV standby handling in `CastPlayerControllerImpl` remains open in #851.

## Pitfalls

- **Compiling only `libre` misses `proprietary` breakages.** `app/phone` and `player/cast` default to `libre`; always run `compileProprietaryDebugKotlin` and `testProprietaryDebugUnitTest` when touching shared Cast code.
- **Stand-in hides receiver timing quirks.** `FakeCastPlayerController` updates position immediately on seek and does not report to the server, so late position reports, end-of-item position-0 blips, and playback reporting must be covered by unit tests (`CastSegmentSkipperTest`, `CastViewModelSegmentTest`, `CastPlaybackReporterTest`) and verified on a real receiver when available.
- **Direct play container assumption (`CastUrls.kt`).** `castContentType(isTranscoded)` returns `"video/mp4"` for direct play and `"application/x-mpegurl"` for transcodes rather than trusting ffprobe's container list.
