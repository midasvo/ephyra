# Subtitles and tracks

Status: works on both engines, phone and TV, as of 2026-11-23 (`main` at `b24b19d54`). Open: a subtitle file from the device ([#853](https://github.com/midasvo/ephyra/issues/853)), libass in Media3 ([#668](https://github.com/midasvo/ephyra/issues/668)).

## Start here

- The player is `PlayerViewModel` in `player/local`; both engines are a media3 `Player`, so shared code reads media3 `Tracks`, never an engine class (rule R6).
- Which tracks start: `domain/SeriesTrackPreference.kt` and `domain/TrackPicks.kt`. Subtitle files: `domain/SubtitleFiles.kt`, then `engine/exo/` or `engine/mpv/MpvSubtitleFiles.kt`.
- Styles: `AppPreferences` → `core/.../SubtitleStyle.kt` (ExoPlayer) or `engine/mpv/MpvSubtitleOptions.kt` (mpv).
- What the server sends: the device profile in `server/jellyfin/.../JellyfinDeviceProfiles.kt`.
- Ports: `SeriesTrackPreferences` (`getSeriesTrackPreferences`, `saveSeriesTrackPreference`), `PlaybackPort` (the `audioStreamIndex` of a `PlaybackRequest`, for a transcode), `EnginePlayer` (`setSubtitleDelayMs`).
- Test fast: `./gradlew :player:local:testDebugUnitTest --tests '*Subtitle*' --tests '*Track*'`. On a device: Tracks Movie, Image Subs Movie, Tracks Show on the test server.
- Before the PR: `docs/mpv-checklist.md` on both backends, and `docs/track-test-series.md` for the series choice.
- Seen before: transcodes drop embedded tracks (#597, #613), a delay after a seek (#650), the ASS override on mpv (#833).

## What it does

- Lists the audio and subtitle streams of the playing item in the track dialogs, with readable names that are the same on ExoPlayer and mpv.
- Picks the start tracks: the saved choice of the series, else the preferred audio and subtitle languages (Settings → Language), else the file's defaults.
- Remembers a track picked by hand: per series and user in Room, or for a movie while the player is open.
- Loads subtitles that do not come with the stream as files of their own: external files, the tracks the server extracts during a transcode, image subtitles (PGS, VobSub, DVB).
- Switches audio during a transcode by asking the server for a new stream.
- Shifts subtitles in time (subtitle delay and sync sheet, ±180 s, for the playing item only).
- Styles text subtitles (colour, edge, background, font, size) and optionally overrides the styles of ASS/SSA tracks.

Both engines are a media3 `Player` to the rest of the app: `MPVPlayer` extends `BasePlayer` and builds media3 `Tracks`. So the shared code reads `Tracks` and `Format`, not `player/core`'s `Track`, which is the Cast model.

## Where the code is

| Path | What |
|---|---|
| [PlayerViewModel.kt](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/presentation/PlayerViewModel.kt) | `trackChoices`, `switchToTrack`, `rememberTrackChoice`, `onTracksChanged`, `setSubtitleDelay`, the sync cues |
| [domain/SeriesTrackPreference.kt](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/domain/SeriesTrackPreference.kt) | `normalizeLanguage`, `matchSeriesTrack`: finding a stored choice again in the next file |
| [domain/TrackPicks.kt](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/domain/TrackPicks.kt) | `TrackChoices`, `TrackPick`, `SessionTrackPicks` (movies), `trackPreferenceKey` |
| [domain/SubtitleFiles.kt](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/domain/SubtitleFiles.kt) | Which subtitles of a source load as files, in dialog order |
| [domain/StreamSwitches.kt](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/domain/StreamSwitches.kt) | Quality and transcode audio switches; the latest one wins |
| [domain/PlaylistManager.kt](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/domain/PlaylistManager.kt) | The audio index a transcode is asked for: saved series choice, preferred language |
| [domain/Extensions.kt](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/domain/Extensions.kt) | `trackName`, `serverTrackName`: the names in the dialogs |
| [domain/AutoSyncSubtitleCueParser.kt](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/domain/AutoSyncSubtitleCueParser.kt) | Reads SRT, WebVTT, ASS/SSA, TTML cues for the sync sheet |
| [engine/EngineDefaults.kt](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/engine/EngineDefaults.kt) | The track selector with the preferred languages; mpv takes its parameters |
| [engine/exo/](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/engine/exo/) | `SubtitleFileMediaSource` (files load beside the stream), `ImageSubtitleFiles` (whole `.sup`/`.mks` files), `SubtitleDelayMediaSource`/`SubtitleDelayMediaPeriod`, `SubtitleOffsetRenderer`, `SubtitleReplaySampleStream` (delay) |
| [engine/mpv/](../../player/local/src/main/java/dev/jdtech/jellyfin/player/local/engine/mpv/) | `MpvSubtitleFiles` (files fetched when wanted), `MpvSubtitleOptions` (style options), track list in `MPVPlayer.kt` |
| [player/core/.../models/](../../player/core/src/main/java/dev/jdtech/jellyfin/player/core/domain/models/) | `ExternalSubtitle`, `SubtitleSyncCue`, `ServerAudioTrack`, `Track` (Cast) |
| [JellyfinDeviceProfiles.kt](../../server/jellyfin/src/main/java/dev/jdtech/jellyfin/server/jellyfin/JellyfinDeviceProfiles.kt) | Subtitle profiles per engine: embed, external, burn-in; mpv's profile under a cap |
| [JellyfinPlayback.kt](../../server/jellyfin/src/main/java/dev/jdtech/jellyfin/server/jellyfin/JellyfinPlayback.kt) | Sends `audioStreamIndex` to the server |
| [SeriesTrackPreferences.kt](../../application/src/main/kotlin/dev/jdtech/jellyfin/repository/SeriesTrackPreferences.kt) | `getSeriesTrackPreferences`, `saveSeriesTrackPreference`; model in [domain](../../domain/src/main/kotlin/dev/jdtech/jellyfin/models/SeriesTrackPreference.kt), Room in [data](../../data/src/main/java/dev/jdtech/jellyfin/models/SeriesTrackPreferenceDto.kt) |
| [AppPreferences.kt](../../settings/src/main/java/dev/jdtech/jellyfin/settings/domain/AppPreferences.kt) | `preferredAudioLanguage`, `preferredSubtitleLanguage`, `subtitle*`, `subtitleOverrideEmbeddedStyles` |
| [SubtitleStyle.kt](../../core/src/main/java/dev/jdtech/jellyfin/utils/SubtitleStyle.kt) | Settings to ExoPlayer's `CaptionStyleCompat` |
| [settings/.../subtitle/](../../settings/src/main/java/dev/jdtech/jellyfin/settings/presentation/subtitle/) | `SubtitleStyleViewModel` and its State/Action/Event |
| Phone [presentation/player/](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/player/) | `TrackSelectionDialogFragment`, `SubtitleSyncDialog(Fragment)`; [SubtitleStyleScreen.kt](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/settings/SubtitleStyleScreen.kt) |
| TV [ce/presentation/player/](../../app/tv/src/main/java/dev/jdtech/jellyfin/ce/presentation/player/) | `TvSelectionDialog`, `TvSubtitleDialog`, the CC and audio keys in `TvPlayerKeyHandler`. TV has no style screen: the settings list shows the subtitle style group |

## Rules

- **R6** (engine boundary): `engine/exo` and `engine/mpv` do not use each other, and no other production file uses either; only `engine.di` binds both. An engine feature goes behind `EnginePlayer` (as `setSubtitleDelayMs` does).
- **R1, R2, R3**: no Jellyfin SDK outside `server/jellyfin`. Subtitle delivery, stream types and codecs in the player are our own enums (`dev.jdtech.jellyfin.models.SubtitleDeliveryMethod`, `MediaStreamType`). Device profile changes go in the adapter.
- **R4**: the player reads and writes the series choice through `SeriesTrackPreferences`, never `ServerDatabaseDao`.
- **R10**: ids are value classes (`ItemId`, `UserId`, `MediaSourceId`, `MediaStreamId` in `domain/.../models/Ids.kt`); no `java.util.UUID` and no `String` id in a signature outside the adapter. A random name that is not an id (mpv's subtitle folder, the media session) is not a reason for `java.util.UUID` either: `kotlin.uuid.Uuid.random()` gives the same text ([#930](https://github.com/midasvo/ephyra/pull/930)).
- **R12**: tick conversions only through `Ticks`.
- Player PRs: run [docs/mpv-checklist.md](../mpv-checklist.md) on both backends. `PlayerInvariantsTest` pins that mpv adds external subtitles off the main thread (#617).
- A change to the series choice table is a Room migration: it gets an independent review (CLAUDE.md).
- New strings: all 36 languages through the `translate-strings` skill. Subtitle and track strings are `ce_` keys in `player/local` and `core` (`ce_subtitle_delay`, `ce_track_sdh`, …).

## How to test it

Unit tests, no device (`player/local`, `data` and `server/jellyfin` have no flavor; `core` has `libre`):

```bash
./gradlew :player:local:testDebugUnitTest --tests '*Subtitle*' --tests '*Track*' --tests '*StreamSwitches*' --tests '*AudioChoice*' --tests '*SwitchFailureText*' --tests '*PlayerInvariantsTest'
./gradlew :application:test --tests '*MediaRepositoryImplTest'
./gradlew :data:testDebugUnitTest --tests '*RoomStoredReadsTest'
./gradlew :server:jellyfin:testDebugUnitTest --tests '*JellyfinDeviceProfilesTest' --tests '*PlaybackSourceMappingTest'
./gradlew :core:testLibreDebugUnitTest --tests '*SubtitleStyleTest'
```

They cover: series matching (`MediaRepositoryImplTest`, `RoomStoredReadsTest`, `SessionTrackPicksTest`, `PlaylistManagerAudioChoiceTest`), names (`TrackNamesTest`), files (`SubtitleFilesTest`, `ImageSubtitleFilesTest` with `src/test/resources/subtitles/`, `SubtitleFileMediaPeriodTest`, `MpvSubtitleFilesTest`), delay (`SubtitleDelayMediaPeriodTest`, `SubtitleReplaySampleStreamTest`), sync cues (`AutoSyncSubtitleCueParserTest`), mpv style (`MpvSubtitleOptionsTest`).

On the test server ([docs/dev-environment.md](../dev-environment.md), `tools/test-server/setup.sh`, the `test-server` skill):

- **Tracks Movie**: two audio tracks (English stereo, Dutch 5.1), two embedded subtitles (one forced), an external `.de.srt`.
- **Image Subs Movie**: PGS and VobSub. Set a quality cap of 1.5 Mbps to make it transcode: that tests image subtitles delivered as files.
- **Tracks Show**: two audio tracks (`dut`-tagged Dutch); with the 1.5 Mbps cap, the series audio choice on a transcode.
- **Und Audio Show**: an audio track tagged `und`.

For the per-series choice and the ASS override, generate the series in [docs/track-test-series.md](../track-test-series.md) (Track Test, Other Show and Subtitle Style Test; `scripts/make-track-test-series.sh`, needs `ffmpeg`) and add them to a TV shows library by hand: `setup.sh` does not. That doc has the step lists. The audit rows PLY-33 to PLY-47 in [docs/audit/checklist.md](../audit/checklist.md) list what to look at.

Emulators: the `test-devices` skill. `scripts/avds.sh start <name>` uses software rendering, which mpv needs. Switch the backend under Settings → Player. Mute the emulator first.

The mpv checklist asks for the same item on both backends: resume with the audio and subtitle buttons enabled, a seek, the media session, the refresh rate vote, backing out. Write down what differs.

## Before a PR

```bash
./gradlew ktfmtCheck
./gradlew :architecture:test
./gradlew :application:test :player:local:testDebugUnitTest :data:testDebugUnitTest :server:jellyfin:testDebugUnitTest
./gradlew :app:phone:compileLibreDebugKotlin :app:tv:compileLibreDebugKotlin
python3 .claude/skills/translate-strings/scripts/check-translations.py   # when strings changed
```

Add `:app:phone:compileProprietaryDebugKotlin` when Cast's `Track` or `TrackFormatter` changes. Then the mpv checklist on both backends.

## Decisions and history

- [#19](https://github.com/midasvo/ephyra/issues/19): PGS, VobSub and DVB delivered without a transcode.
- [#24](https://github.com/midasvo/ephyra/issues/24): subtitle style settings.
- [#115](https://github.com/midasvo/ephyra/issues/115), [#120](https://github.com/midasvo/ephyra/issues/120): audio and subtitle choice per series; audio is never remembered as off.
- [#296](https://github.com/midasvo/ephyra/issues/296): the saved choice is applied with the first track selection.
- [#300](https://github.com/midasvo/ephyra/issues/300), [#308](https://github.com/midasvo/ephyra/issues/308): "Override embedded styles", and the style reaching mpv.
- [#451](https://github.com/midasvo/ephyra/issues/451): readable track names, the same on both backends.
- [#580](https://github.com/midasvo/ephyra/issues/580), [#834](https://github.com/midasvo/ephyra/issues/834): the series choice and the preferred language sent to the server for a transcode.
- [#596](https://github.com/midasvo/ephyra/issues/596), [#726](https://github.com/midasvo/ephyra/issues/726): audio switch during a transcode; picks kept for a movie; the latest switch wins.
- [#597](https://github.com/midasvo/ephyra/issues/597), [#613](https://github.com/midasvo/ephyra/issues/613): embedded text and image subtitles offered during a transcode.
- [#612](https://github.com/midasvo/ephyra/issues/612): mpv's capped profile never lets the server burn in a subtitle.
- [#617](https://github.com/midasvo/ephyra/issues/617), [#711](https://github.com/midasvo/ephyra/issues/711): subtitle files load off the main thread and only when wanted.
- [#619](https://github.com/midasvo/ephyra/issues/619), [#650](https://github.com/midasvo/ephyra/issues/650): subtitle delay and sync sheet; cues after a seek with a delay.
- [#833](https://github.com/midasvo/ephyra/issues/833): toggling the ASS override during mpv playback.
- [#750](https://github.com/midasvo/ephyra/issues/750): the series choice through the repository, not the DAO. [ADR 0001](../architecture/adr/0001-ports-and-adapters.md), [ADR 0002](../architecture/adr/0002-hexagonal-ddd.md).
- [TV ADR 0004](../tv-research/adr/0004-tv-player-ui.md): TV track dialogs call the shared `switchToTrack()`.

## Pitfalls

- **A transcode drops the tracks inside the file.** The server extracts them and lists each with its own URL; they must be loaded as files (`subtitleFiles`, #597, #613). For direct play the server can list the same URLs, so they would show twice: only transcodes take them.
- **The first subtitle request of a transcode can take minutes**, until the server has extracted every track. ExoPlayer stalled in BUFFERING and mpv lost the tracks (#711). Never let a subtitle file hold up the video.
- **media3 draws nothing from a whole image subtitle file**: it expects one picture per sample. `ImageSubtitleFileParser` splits `.sup` and Matroska files (#613).
- **Language codes differ**: `en` (ExoPlayer), `eng` (mpv), `dut` (Matroska), `nld` (server). Compare through `normalizeLanguage` (#580).
- **mpv draws subtitles itself**: ExoPlayer's `SubtitleView` style never reaches it; it gets `MpvSubtitleOptions` (#308). mpv applies a style change while it plays, ExoPlayer on the next playback.
- **Changing `sub-ass-override` while mpv plays loses a cue** unless the track is selected again (`MpvSubtitleOptions.kt`, #833).
- **A positive delay** left cues missing after a forward seek (#650) and kept the item on its last frame for the delay (#619). The delay belongs to the playing item only; the next item starts at 0.
- **On a downloaded (LOCAL) source every subtitle row is a sidecar file**, whatever `isExternal` says (`SubtitleFiles.kt`).
- **A track with neither language nor title cannot be found again**: it is not remembered (`rememberTrackChoice`).
- **mpv lists subtitle files after the stream's tracks**, so the item's track count changes and the choice is applied again (`trackPreferenceKey`).
- **Emulators** cannot run mpv with host GPU rendering (`docs/dev-environment.md`); on Android 17 images ExoPlayer cannot decode H.264.
