# Quality audit, November 2026

One pass over the whole app (epic #361): what works, what is unpolished and what is broken, on
phone, tablet and Android TV. The audit reported and did not fix. This file is the summary, the
ranked list of what was found, and the order to fix it in.

**Build:** v2.4.0 (`main` at `79d39f81`), debug build, plus the release APKs of 2.0.0 to 2.4.0 for
the upgrade path.

| File | What |
|---|---|
| [checklist.md](checklist.md) | 423 feature rows with a result per form factor and player backend (#352 to #356) |
| [static-checks.md](static-checks.md) | Lint, unit tests, `SDK_INT` checks, manifests (#351) |
| [android-versions.md](android-versions.md) | Smoke run on Android 10, 11, 13, 16, 17 and Android TV 11 (#357) |
| [upgrade-path.md](upgrade-path.md) | Updates from 2.0.0 to 2.4.0 and what a downgrade loses (#358) |

## What was tested

- Phone (Pixel 6), tablets (8 inch, 10.5 inch, Pixel Fold) and Android TV (1080p) on Android 14
  emulators: every checklist row that the demo server can show.
- The player on ExoPlayer and mpv on all three form factors.
- A smoke run on Android 10, 11, 13, 16 and 17 and Android TV 11.
- Updates from 2.0.0, 2.1.0, 2.2.0 and 2.3.0 to 2.4.0, and downgrades.
- Lint on every module, the unit tests, and a review of every `SDK_INT` check and manifest.

## What was not tested

- **Anything that needs the own server:** profiles with a second user, PIN and pattern lock between
  users, HEVC, AV1, HDR10 and Dolby Vision, subtitles and several audio tracks, the Track Test
  series, collections, extras, multi-version items. The demo server has none of these.
- **Real devices (#359):** the OPPO phone and the Xiaomi box. Audio output, refresh-rate switching,
  Bluetooth buttons and mpv video on Android 10, 11 and TV 11 can only be checked there.
- The TV 4K AVD, the launcher's Watch Next row beyond the first check, a real network cut on TV
  (the emulator is on Ethernet), updating a TV install, mouse hover and scroll.
- About a third of the Player rows per column are `not tested` for these reasons. The checklist
  gives the reason per cell.

Two findings were test artefacts and are withdrawn: the TV login form (keys sent with
`adb shell input keyevent` are ignored by Compose text fields; use a `uinput` D-pad) and the missing
seek indicator on TV.

## What is healthy

- **Android versions.** No Java crash, `NoSuchMethodError`, `NoClassDefFoundError` or app
  `SecurityException` from Android 10 to 17. Lint has no `NewApi` finding left. The #350 key crash
  fix holds on Android 10 and 11 on both backends.
- **Upgrades.** Every update from 2.0.0 to 2.4.0 keeps the login, settings, downloads, offline
  positions and favourites.
- **Unit tests.** 513 tests, all pass.
- **Core flows.** Login, browsing, search, item pages, playback, seeking, PiP, downloads at original
  quality, offline playback and process death (except #371) work on every form factor.
- **Tablet and foldable.** Rotation, fold and unfold, split-screen and tabletop posture keep
  playback and state. #339 is fixed. The gesture edge zones are exact.
- **Edge-to-edge and predictive back** on Android 16 and 17: nothing clipped or unreachable.

## How to work through the list

One issue at a time, in the order below. For each issue:

1. **Verify.** Reproduce it on v2.4.0 or current `main` with the steps in the issue, and read the
   code path. Some issues state a suspected cause only, and testers disagreed on about 15 rows. If
   it does not reproduce, say so on the issue and close it or ask, instead of fixing something that
   may not be broken.
2. **Fix.** The smallest change that removes the cause. One issue per pull request, unless two
   issues share one cause (they are joined with `+` below).
3. **Test.** At the level the batch names:
   - **A, logic:** a unit test that fails before the fix and passes after it, plus
     `./gradlew testDebugUnitTest`.
   - **B, one device:** A where the logic can be unit tested, plus the issue's steps on one
     emulator before and after the fix, with a screenshot or log as proof.
   - **C, player:** B on both backends, plus `docs/mpv-checklist.md` (CLAUDE.md requires it for
     anything under `player/local` or the player screens), on each form factor the issue names.
   - **D, layout:** B at the sizes of `docs/tablet/6-test-matrix.md` that the change touches, both
     orientations, plus the phone size to show nothing moved there.
   Add the own server or a real device where the issue says the emulator could not show it.
4. **Record.** Set the checklist cell to `works` in the same pull request.

mpv on an emulator needs `-gpu swiftshader_indirect`; everything else can use `-gpu host`. TV keys
go through a `uinput` D-pad.

## Ranked list

### 1. Playback blockers and crashes (release as 2.4.1 when done)

Test level C for the player issues, B for #381.

| Issue | What | Note |
|---|---|---|
| #444 | Every transcode fails with 404 on a server whose address has a path | In every release since v1.0.2-ce.30. Level A plus one playback on the demo server (`/stable`) with a quality cap. Check external subtitle URLs, built the same way |
| #453 | mpv: picking another episode in the episode sheet or the TV queue row crashes the app | Cause confirmed. The non-crashing picks leave the title, queue and server reports on the old episode: same fix |
| #381 | Removing the current user from Settings, Users crashes the app | Reproduced on phone and tablet |
| #454 + #426 | An unfinished download plays as a black screen; a cancelled download comes back after a restart | Together they leave an episode that will not play |

### 2. Player correctness

Test level C.

| Issue | What |
|---|---|
| #445 | mpv: a lost connection ends the video and reports it finished |
| #460 | mpv: a stalled stream freezes with no buffering or error and never recovers |
| #446 | mpv: a stream that fails to open shows no error |
| #374 | The phone player stays open, empty and silent when playback fails to start |
| #458 | The TV player never reports progress during playback |
| #461 | Leaving the player while an item is loading reports no stop |
| #459 | TV: OK that brings up the controls also pauses; the "OK pauses" setting does nothing |
| #462 | TV: after Home and back, the player sits paused with nothing on screen |
| #367 | The still-watching countdown restarts after the app returns |
| #447 | Home opens PiP although the gesture is off, once the PiP button was used |
| #448 + #449 | Space can close the player; keyboard focus on the controls is invisible |

### 3. State, data and identity

Test level B; #380 and #386 are level A.

| Issue | What |
|---|---|
| #371 | A library shows a spinner forever after process death (phone, tablet, TV) |
| #425 | Adding a saved server again signs the app out until a restart |
| #427 | My media is blank after a network drop and stays blank |
| #428 | Offline Home shows remembered online rows that cannot be opened |
| #431 | A change in Home rows only shows after a pull to refresh |
| #387 | Rotating or switching theme closes the search results |
| #388 | The login screen comes back with an old password filled in |
| #379 | Downloaded files stay on disk after a database reset |
| #380 | Room migration tests for the released schemas |
| #386 | The server is told the app version is 1.2.0 |
| #385 | Export device profile always fails on Android 11 and later |

### 4. Setup, permissions, downloads and TV behaviour

Test level B.

| Issue | What |
|---|---|
| #372 | The notification permission is asked on every launch and rotation |
| #373 | Download is offered when the server does not allow it |
| #383 | A download that fails on the server shows "Waiting for network" |
| #376 | Local network access denied on Android 17: discovery fails without saying why |
| #377 | The Home error dialog shows a raw stack trace |
| #382 | Quick Connect keeps showing its code after it is cancelled |
| #384 | The licence texts cannot be opened on the phone |
| #389 | Tapping a cast member's name does nothing |
| #404 | TV: adding a server fails with spaces around the address |
| #405 | TV: Home stays blank and item pages spin forever when loading fails |
| #406 | TV: the "Display Extra Info" setting does nothing |
| #407 | TV: the person page opens scrolled past the name and biography |

### 5. Tablet layout and keyboard

Test level D. #432 was a design decision: the hero height formula in `docs/tablet/4-design.md` §6
cannot meet a 2.2:1 limit in landscape, so acceptance criterion 1 now holds for portrait and the
hero height decides in landscape (#622 for a 16:9 backdrop).

| Issue | What |
|---|---|
| #429 | Episode buttons and text cut off in the Season list-detail below about 1200 dp and in a 600 dp split |
| #430 | Tab on My media opens the search and never reaches the library cards |
| #455 | The taskbar covers the seek bar after split-screen or an unfold |
| #456 | The player's buttons show through the top of the episode sheet |
| #432 | Landscape backdrop cropped to a thin band (decided: accepted, see #622) |
| #434, #435, #436, #438, #439 | Width caps, Season rotation scroll, person page, carousel pages, wide search |

### 6. Unpolished

Test level B, or D where it is a layout. Small ones can share a pull request per screen.

- **TV:** #408, #409, #410, #411, #412, #413, #415, #416, #417, #418, #419, #420, #421, #422, #423,
  #424, #463, #464.
- **Phone and tablet:** #375, #390, #391, #392, #393, #394, #395, #396, #397, #398, #433, #437,
  #440, #441, #442, #443.
- **Player:** #450, #451, #452.
- **Text and translations (use the `translate-strings` skill):** #399, #400, #401, #402, #403, #414.

## Found but not filed

- mpv on the Android 17 emulator: an abort in libplacebo when playback starts in landscape, and a
  downloaded episode playing offline with sound but no picture. Both need a real device.
- `DownloadPumpService` calling `startForeground` when Android restarts it in the background on
  Android 12 and later (suspected in #351, never exercised).
- The still-watching settings rows stay enabled on the tablet while the prompt is off.
- The Users screen says "No users found" when the list fails to load, with no Retry.
- Release notes: the Favorites Home row moved to the Favorites tab in 2.1.0; a downgrade below
  2.3.0 signs the user out and forgets the downloads list.
- Polish seen once in the version matrix and not filed: the blank white launch window on Android 10
  and 11, the predictive back preview overlaying both pages, the two-tone bar with 3-button
  navigation, Back after a Home-gesture PiP leaving the app, App language greyed out below
  Android 13 without a reason.
- Hygiene from the static checks: five dead `SDK_INT` checks, two unused permissions, the stale
  comment in `NetworkConnectivity.kt`, an exported TV receiver without a permission.

## CI recommendation

- **Add the unit tests now.** `./gradlew testDebugUnitTest` takes 33 seconds and passes.
- **Add lint after a baseline.** Lint fails today on 65 existing errors, none of them `NewApi`.
  Add the media3 `UnstableApi` ignore to `core` and `app/tv`, downgrade `MissingQuantity`, and
  baseline the rest; then `NewApi` can be a required check. Lint takes about 3.5 minutes.
- **Add a migration test (#380)** before the next schema change. The database falls back to a
  destructive migration, so a broken migration would pass CI and wipe logins.
- **No emulator smoke test in CI for now.** The Android 10 image needs workarounds (16 MB heap) and
  the run would cost more than lint, which catches the #350 class of bug without a device.

## Still to do in the epic

- #359: the real devices, and the own-server rows of the checklist.
- The fixes, in the order above.
