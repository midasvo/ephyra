# Audit 7/10: Android version matrix

Sub-issue #357 of the quality audit (#361). Report only: nothing in the code was changed.

**Build under test:** v2.4.0 debug (`main` at `79d39f81`), `nl.midasvo.findroid.ce.debug`, versionCode
2040000. Phone: `phone-libre-x86_64-debug.apk`. TV: `tv-libre-x86-debug.apk` (32-bit x86 image).
**Server:** the Jellyfin demo server (`https://demo.jellyfin.org/stable`, Jellyfin 12.4.0), user `demo`,
empty password. **Emulators:** six AVDs, one at a time per tester, all with `-gpu swiftshader_indirect`
(software GPU, because mpv crashes host-GPU emulators). Three testers ran the smoke set of
`docs/audit/checklist.md` ("Smoke set for the Android version matrix") as steps 1 to 16 below.

## Summary

- **What ran on what:** Android 10 (API 29) and 11 (API 30) phones, Android 13 (API 33) phone, Android
  16 (API 36) and 17 (API 37) phones, and Android TV 11 (API 30, x86). Every step on every AVD, except
  where a cell says otherwise.
- **The #350 key crash fix holds on Android 10 and 11, on both backends.** Volume (24, 25), play/pause
  (85), play (126), pause (127), headset hook (79), next (87), previous (88) and Back were pressed during
  playback on ExoPlayer and on mpv on API 29 and API 30. No crash, `logcat -b crash` empty after every
  key, every key did what it should. The same keys pass on API 33, 36, 37 and TV 30.
- **No Java crash (`FATAL EXCEPTION`), no `NoSuchMethodError`, no `NoClassDefFoundError`, no
  `ForegroundServiceStartNotAllowedException`, and no `SecurityException` or `StrictMode` violation
  from the app, on any version.** Every `SecurityException` and `StrictMode` line came from Google
  system processes. Three things in the logs did come from the app's process, and none is counted as
  an app bug:
  - API 29: the process died of `OutOfMemoryError` three times, because that system image gives apps a
    16 MB heap (E1).
  - API 37: two native `SIGABRT`s in libplacebo when mpv starts in landscape under SwiftShader (E4).
  - API 37: one ANR on the very first launch (`No response to onStartJob for #OrphanSweepWorker#`, load
    32 right after boot, while a GMS ANR fired at the same moment). It did not happen again.
- **What was found:** 4 wrong-behaviour findings that are the app's (F1 to F4), 1 that needs a real
  device to place (F5), 1 that the emulator causes (F7), 14 unpolished findings, a few TV
  observations (F19) and 3 log-noise findings. F6 (the TV login form and the D-pad) was withdrawn: it
  came from the way the keys were sent, not from the app. F20 (no seek feedback on TV) was withdrawn:
  the TV player run (#356) saw the indicator. The most visible ones:
  - A library opened from the back stack after process death spins forever (F1, API 36). Seen once,
    confirmed in the code, and the code is the same on every version.
  - The notification permission is asked before the Welcome screen and again on every activity
    recreation (F2, API 33, 36, 37).
  - Season pages offer Download on each episode when the server does not allow downloads (F3).
  - A failed playback start leaves an empty, silent player open (F4, API 29, 36, 37).
- **Emulator limits** (E1 to E4) hid four things that need a real device (#359): ExoPlayer on long
  files on API 29, mpv's picture on API 29, 30 and TV 30, ExoPlayer's H.264 decoding on API 37, and mpv
  starting in landscape on API 37.
- **Not tested:** audio output on every AVD (the emulators' audio was not listened to), Quick Connect,
  the volume level itself on most AVDs, downloads and offline play on some versions, mpv's picture on
  the old images, show, season and episode pages and Search on TV. Details in "Not tested".

## Results by API level

Legend as in `checklist.md`: `works`, `unpolished` (what), `broken` (what), `not tested` (why), `n/a`.
"F" and "E" refer to "Findings" and "Emulator limits" below. A cell only says what the tester saw; where
a failure comes from the emulator and not the app, the cell says so.

| # | Smoke step (checklist row) | API 29 (Android 10) | API 30 (Android 11) | API 33 (Android 13) | API 36 (Android 16) | API 37 (Android 17) | TV API 30 (Android TV 11) |
|---|---|---|---|---|---|---|---|
| 1 | Install, first launch, Welcome (SUP-01) | works | works | works; the notification prompt covers Welcome (F2) | works | works; one ANR on the first launch under boot load | works; banner in the launcher (TVS-12) |
| 2 | Add server by address, log in (SUP-06) | unpolished: disclaimer shows `<br/>` (F9) | unpolished: `<br/>` (F9) | unpolished: `<br/>` (F9) | unpolished: `<br/>` (F9) | unpolished: `<br/>` (F9) | unpolished: focus starts on Add user (F21), `<br/>` (F9); the D-pad finding F6 is withdrawn |
| – | Quick Connect (SUP-08) | not tested: not in the brief | not tested: not in the brief | not tested: not in the brief | not tested: not in the brief | not tested: not in the brief | not tested: not in the brief |
| 3 | Notification permission (LIF-12) | n/a (below API 33) | n/a (below API 33) | unpolished: asked before Welcome, again after recreation (F2) | broken: asked again on rotation after a deny (F2) | unpolished: asked before Welcome, again on each cold start (F2) | n/a (below API 33) |
| 4 | Local network permission (LIF-13) | n/a | n/a | n/a | n/a: no rationale screen, correctly | works: rationale and settings card work; F8 fixed (#376): after a deny, Add server says that local network access is off and a LAN address that fails names the permission | n/a |
| 5 | Home: rows, artwork, scroll (HOM-06) | works | works | works | works | works | works |
| 6 | Library, sort, filter, movie, show, season, episode (BRW-07) | works | works | works; empty hero on one movie (F22) | works | works | works; show, season and episode pages not visited |
| 7 | Search | works | works | works | works | works | not tested: not in the TV steps |
| 8 | ExoPlayer: start, picture, pause, controls (PLY-01 start only; resume not checked) | works for a 2.5-min file; long files fail: emulator limit, 16 MB heap (E1); then an empty player (F4) | works | works | works | broken: emulator limit, probably: goldfish H.264 decoder fails (E3); then an empty player (F4) | works |
| 8 | Seek (PLY-04) | works: double tap; scrubbing not tested | works: scrub with trickplay, double tap | works: scrub, double tap | works: scrub, double tap | not tested on ExoPlayer (nothing plays); works on mpv | works: Right seeks +30 s with controls hidden; seek bar focus not tested; the seek-feedback finding F20 is withdrawn |
| 9 | Volume keys (PLY-64) | works | works | works; level not measured | works | works | works; level not measured |
| 9 | Media keys, Back (PLY-65) | works, both backends | works, both backends | works, both backends | works, both backends | works: ExoPlayer in its error state, mpv while playing | works (ExoPlayer) |
| 10 | Picture-in-picture (PLY-100) | works with the PiP button; Home gesture: works on ExoPlayer, no PiP on mpv (F7, emulator); Back after it goes to the launcher (F17) | same as API 29 (F7, F17) | works | works | works (mpv) | n/a |
| 11 | mpv backend (PLY-01, PLY-65 on mpv) | not tested for picture: emulator limit, GLES 2 (E2); sound, state, controls and keys work | not tested for picture: emulator limit, GLES 2 (E2); sound, state, controls and keys work | works | works | works when started in portrait; started in landscape: native abort, emulator limit probably (E4) | not tested for picture: emulator limit, GLES 2 (E2); controls and position work |
| 12 | Every settings screen opens | works | works | works | works | works | works |
| 12 | App language (SET-05) | unpolished: greyed out, no reason given (F14) | unpolished: greyed out, no reason given (F14) | works | works | works | n/a (no row on TV) |
| 12 | Theme, dynamic colours (SET-09) | works; dynamic colours disabled with "Android 12+" | works | works | works | works | n/a (not on TV) |
| 13 | Rotation: Home, item page, player (LIF-01) | works | works | works; carousel peeks behind the rail (F23) | works | works | n/a |
| 14 | Background and process death (LIF-02, LIF-03) | works | works | works | broken: Back from the restored page lands on a library that spins forever (F1) | works; the restored back stack had no library | works; relaunched from the launcher, so Home was expected |
| 15 | Edge-to-edge (LIF-10) | works: no inset problems seen (step 16) | works: no inset problems seen (step 16) | not tested: step for API 36 and 37 | unpolished: two-tone bar with 3-button navigation (F16) | unpolished: two-tone bar with 3-button navigation (F16) | n/a |
| 15 | Predictive back (LIF-11) | n/a | n/a | n/a | unpolished: preview cross-fades both pages (F15); in the player not tested | unpolished: preview cross-fades both pages (F15); in the player: first swipe shows the bars, second exits | n/a |
| 16 | Old-version look (splash, bars, icons, fonts) | unpolished: blank white launch window (F13) | unpolished: blank white launch window (F13) | n/a | n/a | n/a | works; splash and icons not examined closely |
| – | Download (DLD-02) | works: a season, 6 episodes | not tested: done on API 29 | works: one episode | broken: offered on episode rows though the server forbids it (F3); the download completes | broken: offered on episode rows though the server forbids it (F3); the download completes | n/a (no downloads on TV) |
| – | Downloaded item plays offline (OFF-05) | works (ExoPlayer, offline mode) | not tested: no download | not tested: not tried | works (mpv, airplane mode) | broken on mpv: sound, no picture (F5) | n/a |
| – | Logcat (LIF-17) | no app crash in Java; OOM deaths from the 16 MB heap (E1) | clean | clean | clean | one ANR on first launch (boot load); two native aborts (E4) | clean |

## Emulator limits

These failures come from the system image or the software GPU, not from the app. Each needs one check on
a real device before it is closed (#359).

### E1. API 29 image: 16 MB app heap

- **Seen:** ExoPlayer fails on long files (Dracula, 74 min; Pioneer One E1, 31 min) with `ExoPlaybackException:
  Source error` caused by `OutOfMemoryError` in `BoxParser.parseStbl`. Later tries kill the process
  (`Process nl.midasvo.findroid.ce.debug ... has died: fore TOP`, three times). A 2.5-min file and a
  downloaded 40-min episode played.
- **Evidence:** every OOM reports `growth limit 16777216`, and `dumpsys meminfo` shows a 16384 KB heap. The
  API 29 image sets no `dalvik.vm.heapgrowthlimit`, and `dalvik.vm.heapsize=512m` arrives from qemu after
  zygote has started, so ART's 16 MB default applies. `-prop dalvik.vm.heapgrowthlimit=256m` was ignored,
  and restarting zygote over adb hung the emulator. The API 30 image has `heapgrowthlimit=192m` and plays
  the same files.
  ```
  E ExoPlayerImplInternal: Caused by: ...Loader$UnexpectedLoaderException: Unexpected OutOfMemoryError: Failed to allocate a 856704 byte allocation ... growth limit 16777216
  E ExoPlayerImplInternal:       at androidx.media3.extractor.mp4.BoxParser.parseStbl(BoxParser.java:603)
  ```
- **Real-device check:** play a feature-length MP4 (Dracula) with ExoPlayer on an Android 10 device and
  read `getprop dalvik.vm.heapgrowthlimit`. Real Android 10 devices have 128 MB or more.

### E2. mpv has no picture on API 29, 30 and TV 30 under SwiftShader

- **Seen:** black video, while sound (on the phones), the media session (`PLAYING`), controls, seeking
  and keys work. The app shows no error.
- **Evidence:** on these images the guest EGL gives mpv a GLES 2.0 context, and libplacebo (`vo=gpu-next`)
  needs GL 3:
  ```
  mpv: [vo/gpu-next/opengl:v] GL_VERSION='OpenGL ES 2.0 (OpenGL ES 3.0 SwiftShader 4.0.0.1)'
  mpv: [vo/gpu-next/libplacebo:fatal] OpenGL version too old (2 < 3)
  mpv: [cplayer:fatal] Error opening/initializing the VO window.
  ```
  API 33, 36 and 37 get `OpenGL ES 3.0 (OpenGL ES 3.0 SwiftShader 4.0.0.1)` and render.
- **Real-device check:** mpv on the Xiaomi box (Android TV) and on any Android 10 or 11 phone: picture
  shows, and the mpv log has a GLES 3 `GL_VERSION`. Separately, a device that really has only GLES 2
  would get the same silent black screen; whether the app should detect that and fall back to ExoPlayer
  is a product question, not part of this audit.

### E3. The goldfish H.264 decoder fails in ExoPlayer on API 37

- **Seen:** every H.264 item fails on ExoPlayer on API 37 (a toast, then F4's empty player). The same
  `c2.goldfish.h264.decoder` plays on API 36.
- **Evidence:** a binder failure and an SELinux denial of the `mediacodec` domain on the app's
  `/memfd:MessageQueue`, both in the system image:
  ```
  E libbinder.IPCThreadState: Binder transaction failure. id: 489242, cmd: BR_FAILED_REPLY (29201), error: -1 (Operation not permitted)
  W MediaCodec_loop: avc: denied { read write } for path=/memfd:MessageQueue (deleted) scontext=u:r:mediacodec:s0 tcontext=u:object_r:untrusted_app:s0
  E MediaCodec: queueCSDInputBuffer failed w/ error 14
  E ExoPlayerImplInternal: Caused by: androidx.media3.exoplayer.video.MediaCodecVideoDecoderException: Decoder failed: c2.goldfish.h264.decoder
  ```
  The app has no fallback for a failing video decoder (only `AudioDecoderFallback`).
- **Real-device check:** ExoPlayer playback of an H.264 file on a real Android 17 device, or on the next
  API 37 system image.

### E4. mpv aborts in libplacebo when playback starts in landscape on API 37

- **Seen:** with the device already in landscape, Play on mpv kills the process within a second of the
  first frame, 2 of 2 on API 37, 0 of 1 on API 36. Starting in portrait (the player then rotates itself)
  works.
- **Evidence:** a native assertion in libplacebo's OpenGL backend, on SwiftShader's GL translator:
  ```
  F libc: Fatal signal 6 (SIGABRT), code -1 (SI_QUEUE) in tid 9834 (Thread-17), pid 9504 (ndroid.ce.debug)
  F DEBUG: Abort message: '../src/opengl/gpu_pass.c:265: pl_pass gl_pass_create(pl_gpu, const struct pl_pass_params *): assertion "obj.size <= buf_size" failed'
  F DEBUG: #03 pc 0000000000597463 .../base.apk!libmpv.so (pl_pass_create+1251)
  F DEBUG: #07 pc 000000000054fe5a .../base.apk!libmpv.so (pl_render_image+522)
  ```
- **Real-device check:** on the OPPO phone (and on an Android 17 device if one is available), rotate to
  landscape on an item page, switch to mpv and press Play.

## Findings

Ranked: crash or data loss, then wrong behaviour, then unpolished, then log noise. Where the cause is
given with a file and line, it was checked against the source at `79d39f81`. "Not confirmed in code"
means the tester's explanation could not be confirmed by reading the code.

### Crash or data loss

None that is the app's. The process deaths in the logs are E1 and E4.

### Wrong behaviour

#### F1. fix(library): a library shows a spinner forever after the app was killed in the background

- Issue: #371
- Seen on: API 36 (2 of 2), phone. API 37: not tried with this back stack. The code is the same on every
  version.
- Steps: My media > Movies > King Lear, Home, `adb shell am kill nl.midasvo.findroid.ce.debug`, reopen
  the app (the item page comes back), press Back.
- Seen: the Movies top bar and a spinner that never ends; no request goes to the server (waited over
  30 s). Expected: the grid loads.
- Cause: `app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/LibraryScreen.kt:81-89`.
  `initialLoad` is `rememberSaveable`, so after process death it comes back as `false`, while the new
  `LibraryViewModel` has an empty state and `loadItems()` is skipped. The state's `items` is still the
  default `emptyFlow()`, so the paging items stay in their first `refresh` state and the spinner at
  `:182-184` stays. The TV library has the same pattern (`app/tv/src/main/java/dev/jdtech/jellyfin/ce/presentation/film/LibraryScreen.kt:113-120`),
  not tested on TV.

#### F2. fix(permissions): the notification permission is asked on every launch and rotation, and before the Welcome screen

- Issue: #372
- Seen on: API 33, 36 and 37, phone. n/a below API 33 and on TV.
- Steps: fresh install, launch: the system dialog appears on top of Welcome. Deny. Then rotate (API 36),
  change the app language (API 33), or close and relaunch (API 33, 37).
- Seen: the dialog comes back each time until it has been denied twice (the system's own limit). After a
  deny the app never says notifications are off; downloads still run, with progress only inside the
  app. Allow works: a "Downloading 1 item" notification shows (API 36). Expected: ask once, with
  context (for example at the first download), and not again on a configuration change.
- Cause: `app/phone/src/main/java/dev/jdtech/jellyfin/MainActivity.kt:39-43`. `onCreate` launches the
  `POST_NOTIFICATIONS` request whenever it is not granted, without checking `savedInstanceState` or
  whether the user already answered, and `onCreate` runs on every recreation.

#### F3. fix(downloads): episode rows on the season page offer Download when the server does not allow the user to download

- Issue: #373
- Seen on: API 36 and 37, phone.
- Steps: log in as `demo`, Shows > Pioneer One > Season 1.
- Seen: every episode row has a Download button and the download completes (181 MB
  `S01E01 - Earthfall.mkv`), while the season's own Download button is hidden and the episode page has
  none, because those follow `canDownload`. Expected: one rule everywhere.
- Cause: `app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/SeasonScreen.kt:372-376` passes
  `onDownloadClick` for every episode without checking `episode.canDownload`, and `EpisodeCard`
  shows the button whenever the callback is not null (`components/EpisodeCard.kt:91`). The wide layout
  has the same code (`SeasonWideLayout.kt:358-361`).
- Note: on API 29 and 33 the season and show Download buttons were offered too, so the demo account was
  allowed to download during those runs. The server's setting seems to have differed between runs.

#### F4. fix(player): the player stays open, empty and silent when playback fails to start

- Issue: #374
- Seen on: API 29 (ExoPlayer source error from E1), API 36 (server 503 during the demo reset), API 37
  (decoder failure from E3); phone, ExoPlayer.
- Steps: start playback while it cannot start: the server answers an error, or the stream or decoder
  fails.
- Seen: a toast (seen on API 37 and 36; the API 29 tester saw no message, see "Contradictions"), then a
  black player at 00:00 / 00:00 with a Play button that fails again or does nothing. On API 36 there is no
  title, the controls are disabled, and the player logs "Updating playback progress" every 5 s until
  Back. Expected: close the player with the error, or show the error on screen with a way back.
  ```
  E PlayerViewModel$initializePlayer: org.jellyfin.sdk.api.client.exception.InvalidStatusException: Invalid HTTP status in response: 503
  E PlayerViewModel$initializePlayer: No start item, stopping player initialization
  D PlayerViewModel: Updating playback progress
  ```
- Cause: `player/local/.../presentation/PlayerViewModel.kt:572-584` (no start item) and `:1330-1339`
  (`onPlayerError`) send `PlayerEvents.PlayerError`. The phone's `PlayerActivity.kt:631-640` only shows
  a toast for it and leaves the activity open, and the progress loop at `:675-680` keeps running. The TV
  player shows the error on screen (`TvPlayerScreen.kt:168`, `:577`).

### Wrong behaviour that needs a real device

#### F5. fix(player): mpv plays a downloaded episode offline with sound but no picture

- Issue: not filed, needs a real-device or D-pad check first
- Seen on: API 37, phone, mpv: 2 of 2 offline, 0 of 1 online. API 36 offline: 0 of 1.
- Steps: mpv backend, download Pioneer One S1E1, airplane mode on, Downloads > Pioneer One > Earthfall.
- Seen: black screen, sound, media session `PLAYING` with the position advancing. Expected: picture.
  ```
  V mpv: [vo/gpu-next/android:fatal] Missing surface pointer
  V mpv: [vo/gpu-next:error] Failed initializing any suitable GPU context!
  V mpv: [cplayer:fatal] Error opening/initializing the selected video_out (--vo) device.
  ```
- Cause: the tester suspects mpv opens the file before the player's surface is attached and does not
  retry the VO. Not confirmed in code: `MPVPlayer.kt:222` sets `vo` at init before any surface exists,
  and `surfaceCreated` (`:1933-1942`) attaches the surface and sets `vo` again, which should start the
  VO; why it did not here is not known. Timing-dependent; seen only on the slower API 37 emulator.

#### F6. fix(tv): the login form cannot be completed with the D-pad once the on-screen keyboard is hidden

**Withdrawn.** Keys sent with `adb shell input keyevent` come from a virtual input device, and Compose
text fields move focus on D-pad keys only from a real D-pad device. The Android TV 14 run (#355)
repeated the steps with a real D-pad device (registered with `adb shell uinput`): with the keyboard
hidden, Down moves from Username to Password, Login and Quick Connect, and OK on a field reopens the
keyboard. The form works. The entry below is kept as it was first reported.

- Issue: none, withdrawn
- Seen on: Android TV 11 (API 30) only so far. A check on Android TV 14 is pending.
- Steps: add a server, Users > demo, Sign in. The keyboard is up with focus in Username. Press Back: the
  keyboard hides. Press Down.
- Seen: focus stays in Username (four presses); Login looks dimmed; a second Back leaves the form. The
  only working path: the keyboard's action key moves to Password, the action key again logs in.
  Expected: Down moves to Password, Login and Quick Connect.
- Cause: not confirmed in code, and possibly an artefact of the test. The fields are plain Material 3
  `OutlinedTextField`s with no D-pad handling of their own
  (`app/tv/src/main/java/dev/jdtech/jellyfin/presentation/setup/login/LoginScreen.kt:128-194`). Compose's
  text field moves focus on D-pad keys only for events from a device that supports `SOURCE_DPAD` and is
  not virtual, unless the source is HDMI-CEC (read from the bytecode of
  `TextFieldFocusModifier_androidKt$interceptDPadAndMoveFocus$1` in compose foundation 1.12.1). The
  tester sent keys with `adb shell input keyevent`, which come from the virtual device, so a real
  remote may behave differently. The Android TV 14 check should use a remote or the emulator's own
  D-pad, not `adb input`.

#### F7. Home does not enter picture-in-picture on mpv (emulator, from E2)

- Issue: not filed, needs a real-device or D-pad check first
- Seen on: API 29 and 30, phone, mpv, with Settings > Player > "Picture-in-picture home gesture" on.
  On API 37 the same steps on mpv open PiP.
- Steps: mpv backend, home gesture on, play a movie, press Home.
- Seen: no PiP window, playback pauses. ExoPlayer opens PiP with the same steps.
- Cause: confirmed in code, and caused by E2. With no VO, mpv's `width` and `height` properties are
  unavailable, so `MPVPlayer.getVideoSize()` returns `VideoSize.UNKNOWN` (`MPVPlayer.kt:1756-1760`),
  `pipParams` throws `IllegalArgumentException` (`PlayerActivity.kt:1100-1107`), and `pictureInPicture`
  swallows it (`:1153-1155`). With a picture it should work; the app part is that a failed PiP is silent.
  Real-device check: Home on mpv on an Android 10 or 11 device.

### Unpolished

#### F8. fix(setup): after local network access is denied on Android 17, discovery and LAN addresses fail without saying why

- Issue: #376
- Seen on: API 37, phone. n/a below API 37.
- Steps: fresh install, Welcome > Continue > Grant > Don't allow (twice) > Not now > Add server. Enter a
  LAN address (`10.0.2.2:5568`) and Connect.
- Seen: discovery fails with no change on screen; Connect says only "Server not found", the same text as
  with the permission granted. The app calls the permission "Local network access", while the system
  dialog and App info call it "Nearby devices", and the denied text says "allow it in the system
  settings" without naming it. The rationale screen, the Settings > Permissions card (Denied, then
  Granted after allowing in App info) and an internet server all work. Expected: with the permission
  denied and a private address, say that local network access is off and offer the settings.
  ```
  E ojsd.LocalServerDiscove: Unable to send discovery message to /255.255.255.255
  E ojsd.LocalServerDiscove: java.io.IOException: sendto failed: EPERM (Operation not permitted)
  ```
- Cause: `setup/.../presentation/addserver/AddServerViewModel.kt:32-46` collects discovery without
  checking the permission (the SDK logs the `EPERM` and the flow ends), and
  `setup/.../data/SetupRepositoryImpl.kt:126-130` throws `add_server_error_not_found` for any address
  without a reachable server.

#### F9. fix(setup): the server's login disclaimer shows literal HTML such as `<br/>`

- Issue: #375
- Seen on: every version, phone and TV. Not tied to the Android version.
- Steps: add the demo server, pick `demo`.
- Seen: "...for testing and evaluation.<br/>" and "...above.<br/>Please contact...". Expected: line
  breaks, as Jellyfin's web client renders the disclaimer.
- Cause: the disclaimer is the server's branding `loginDisclaimer`, which is HTML
  (`setup/.../data/SetupRepositoryImpl.kt:232-234`), shown as plain text:
  `app/phone/.../presentation/setup/login/LoginScreen.kt:244` and
  `app/tv/.../presentation/setup/login/LoginScreen.kt:271`.

#### F10. fix(home): the Home error dialog shows a raw stack trace

- Issue: #377
- Seen on: API 30, phone (the demo server answered 503 while restarting). Not tied to the Android version.
- Steps: the server answers 503 on a Home refresh; tap the red error icon on Home.
- Seen: "Invalid HTTP status in response: 503" and the full `InvalidStatusException` stack trace, with
  Share and Close. Expected: a plain message, with the trace behind Share.
- Cause: `app/phone/.../presentation/components/ErrorDialog.kt:60-64` shows
  `exception.stackTraceToString()` in the dialog body. The same dialog is used by Library and Media
  (`MediaScreen`) and `ErrorState`.

#### F11. fix(settings): the player backend shows the raw values `exoplayer` and `mpv`

- Issue: #399
- Seen on: API 33 and TV 30 (the other testers did not mention it).
- Steps: Settings > Player > Player backend, and its dialog.
- Cause: `SettingsViewModel.kt:501-502` uses `R.array.player_backends` for both labels and values, and
  that array holds the stored values (`settings/src/main/res/values/string_arrays.xml:13-16`).

#### F12. fix(i18n): "Continar viendo" typo in upstream Spanish

- Issue: #403
- Seen on: API 33, phone, app language Español (US). Not checked on TV.
- Seen: the Home row reads "Continar viendo"; it should be "Continuar viendo".
- Cause: `modes/film/src/main/res/values-b+es+419/strings.xml:3` and `values-es-rMX/strings.xml:3`.
  These are upstream Weblate files, which CE does not edit: fix it upstream, or use a `ce_` key.

#### F13. fix(app): the launch window is blank white on Android 10 and 11

- Issue: not filed yet, to be merged with the findings of #353 to #356
- Seen on: API 29 and 30, phone. Not compared on API 31+ by that tester.
- Steps: force-stop, launch from the launcher, screenshot within 0.3 s.
- Seen: an empty white window until Home draws (about a second on the emulator). Expected: the app icon
  or brand mark, as Android 12+'s system splash shows.
- Cause: the app does not use the SplashScreen compat library, and `Theme.Findroid`
  (`core/src/main/res/values/themes.xml:9-11`) sets no splash attributes, so below API 31 the starting
  window is the theme's background. `MainActivity.kt:52` draws nothing until `MainViewModel` has
  loaded.

#### F14. fix(settings): App language is greyed out on Android 10 to 12 without a reason

- Issue: not filed yet, to be merged with the findings of #353 to #356
- Seen on: API 29 and 30, phone. By the code also API 31 and 32.
- Steps: Settings > Language.
- Seen: "App language" is disabled and does nothing. Expected: a reason, as Dynamic colours gives
  ("only available on Android 12+"), or an in-app picker below API 33 (AppCompat's per-app locales
  work there).
- Cause: `SettingsViewModel.kt:118-124` enables `PreferenceAppLanguage` only for `SDK_INT >= TIRAMISU`
  and gives it no description. Disabled on purpose (checklist SET-05); the missing reason is the finding.

#### F15. fix(navigation): the predictive back preview cross-fades two full-size pages

- Issue: not filed yet, to be merged with the findings of #353 to #356
- Seen on: API 36 and 37, phone, gesture navigation.
- Steps: open a movie page, swipe slowly from the left edge and hold.
- Seen: both pages at full size and half opacity on top of each other, text overlapping. Commit, cancel
  and Back to the launcher behave. Expected: a preview that shows which page Back goes to (the current
  page shrinks or slides, the previous one behind it).
- Cause: `app/phone/.../NavigationRoot.kt:333` and `:368` set `predictivePopTransitionSpec` to the same
  fade-and-drift transition as every other navigation (`GLOBAL_TRANSITION_SPEC`, from
  `core/.../ephyra/EphyraMotion.kt:43-48`), and a predictive back gesture plays it while the finger is
  down.

#### F16. fix(ui): the bottom navigation bar is two-tone with 3-button navigation

- Issue: not filed yet, to be merged with the findings of #353 to #356
- Seen on: API 36 and 37, phone, 3-button navigation. Gesture navigation is one colour.
- Steps: `cmd overlay enable com.android.internal.systemui.navbar.threebutton`, open Home.
- Seen: the app's bar colour (223,239,245) runs down to the system bar, then the system draws its
  near-white contrast scrim (252,253,254) behind the buttons. Expected: one surface to the screen edge.
- Cause: not confirmed in code beyond this: `MainActivity.kt:45` calls `enableEdgeToEdge()` with its
  default navigation bar style, and nothing in the app sets `isNavigationBarContrastEnforced`. The
  tester suggests turning it off where the app draws its own bar.

#### F17. fix(player): Back after returning from a Home-gesture PiP leaves the app

- Issue: not filed yet, to be merged with the findings of #353 to #356
- Seen on: API 29 and 30, phone, ExoPlayer. Not reported on 33, 36 or 37.
- Steps: home gesture on; play from an item page; Home (PiP opens); tap the PiP window and its full-screen
  button; Back.
- Seen: the launcher. Expected: the item page the player was started from. With the player's PiP button
  instead of Home, Back returns to the app.
- Cause: not confirmed in code. `PlayerActivity` is `singleTask` (`app/phone/src/main/AndroidManifest.xml:33`),
  and after Home the app's main task is behind the launcher; that the PiP task is finished alone is a
  likely explanation, not a confirmed one.

#### F18. fix(settings): "Max days in Next Up" reads "0 days" by default

- Issue: #400
- Seen on: API 33, phone.
- Steps: Settings > Interface.
- Seen: "0 days"; it is unclear that 0 means no limit.
- Cause: the summary that explains it exists ("Only show series watched within this many days. 0 means
  no limit.", `settings/src/main/res/values/strings.xml:195`), but the settings row shows only the value
  and suffix (`app/phone/.../settings/components/SettingsNumberInputCard.kt:41`); the description is
  shown only inside the dialog (`SettingsNumberInputDialog.kt:108`).

#### F19. Observations on TV (not defects)

- Back from the player goes straight to the item page.
- The Player settings radio dialog keeps focus in the right pane until Left is pressed.
- The "Direct play Dolby Vision" description is cut with an ellipsis.

#### F20. fix(tv): no seek feedback when the controls are hidden

**Withdrawn.** The TV player run (#356, Android TV 14, real D-pad device) saw the indicator: one press
of Right with the controls hidden showed "⏩ 1:52 / 31:36" at the top with a trickplay thumbnail above it
for about a second, in a screenshot taken straight after the key and in a screen recording. The
screenshot on TV 30 came after the indicator had gone. The entry below is kept as it was first reported.

- Issue: none, withdrawn
- Seen on: TV 30, ExoPlayer.
- Steps: controls hidden, press Right.
- Seen: the position jumps about 30 s, but the screenshot right after shows plain video. One screenshot
  per press.
- Cause: not confirmed in code; the code contradicts it. `TvPlayerKeyHandler.kt:141` and `:148` call
  `showSeekIndicator`, and `TvPlayerScreen.kt:434-450` draws the indicator (with a trickplay thumbnail)
  while the controls are hidden, for `SEEK_INDICATOR_MS` = 1 s (`:261-265`, `:1284`). An `adb`
  screenshot probably came after it had gone. Check by eye on the box (#359) before filing.

#### F21. fix(tv): the Users screen focuses "Add user" instead of the user

- Issue: not filed yet, to be merged with the findings of #353 to #356
- Seen on: TV 30.
- Steps: add the demo server; the Users screen opens.
- Seen: focus on "Add user"; the demo user needs an Up press first.
- Cause: `app/tv/.../presentation/setup/users/UsersScreen.kt:101-110` picks the first focus only from
  saved users (`state.users`). Right after adding a server there are none, only the server's public
  users (`state.publicUsers`), so it falls back to "Add user".

#### F22. fix(film): an item without a backdrop shows an empty hero

- Issue: #433
- Seen on: API 33, phone. Not seen on TV (its page shows the art differently).
- Steps: Movies > King Lear.
- Seen: the hero area (about 600 px) is empty, with the back and home buttons floating on it. Other items
  show a backdrop. Expected: a fallback (the poster, or a shorter header).
- Cause: not confirmed in code. That King Lear has no backdrop on the server was not checked.

#### F23. Home carousel peeks behind the navigation rail in landscape

- Issue: not filed yet, to be merged with the findings of #353 to #356
- Seen on: API 33, phone. Minor, noted in the tester's rotation step.

### Log noise

#### F24. chore(logging): cancelled trickplay loads are logged as errors with stack traces

- Issue: not filed yet, to be merged with the findings of #353 to #356
- Seen on: API 30, 33, 36 and 37 (25 to 47 per run while scrubbing).
- Seen: `E PreviewScrubListener$onScrubMove: Trickplay tile load failed` and
  `E TrickplayLoader...: Failed to load trickplay sheet 0`, each with a `JobCancellationException`.
- Cause: `catch (e: Exception)` also catches the coroutine's `CancellationException` and logs it at
  error level: `app/phone/.../utils/PreviewScrubListener.kt:68-69`,
  `player/local/.../domain/TrickplayLoader.kt:113-114`, and the same in `TrickplayRegionLoader.kt:121-122`.
  The TV's `TvTrickplayThumbnail.kt:60-61` rethrows the cancellation first, so it does not have this.

#### F25. chore(logging): download workers log "No authenticated user" before login

- Issue: not filed yet, to be merged with the findings of #353 to #356
- Seen on: API 33 and 37, before the first login (four times on API 37, about once a minute, then it
  stops).
- Seen: `E DownloadQueue: Failed to query active downloads for restore` and `E OrphanSweepWorker`, both
  with `IllegalStateException: No authenticated user. userId is null`.
- Cause: thrown at `data/.../repository/JellyfinRepositoryImpl.kt:78`, logged at error level at
  `core/.../downloader/DownloadQueue.kt:234` and `core/.../work/OrphanSweepWorker.kt:26-27`; the worker
  also returns `Result.retry()`, which is why it repeats.

#### F26. chore(logging): a cancelled progress update is logged as an error

- Issue: not filed yet, to be merged with the findings of #353 to #356
- Seen on: API 29 (`PlayerViewModel$updatePlaybackProgress: JobCancellationException`, three times).
- Cause: `PlayerViewModel.kt:826-827` catches `Exception` and logs it with `Timber.e`, cancellation
  included.

## Contradictions between the testers

- **Downloads allowed or not (F3).** On API 29 the demo account downloaded a whole season, and on API 33
  the app offered Download on shows, seasons and episodes. On API 36 and 37 the season and episode-page
  buttons were hidden (`canDownload` false) and only the episode rows offered it. The demo server's
  setting probably changed between runs (it reset at noon during the API 36 run).
- **Message after a failed start (F4).** The API 29 tester saw no message; the API 36 and 37 tester saw a
  toast. The code shows a toast in both cases (`PlayerActivity.kt:631-640`); it is likely the API 29
  tester missed it.
- **mpv's GL context on API 33.** The API 33 tester wrote that mpv "logged GLES 2.0 ... but rendered
  fine". The log shows mpv trying a "GLES 2.x +" context and getting `OpenGL ES 3.0`, so API 33 is not an
  exception to E2.
- **Severity of F2.** API 33 and 37 were recorded as unpolished, API 36 as wrong behaviour (the rotation
  case). The table keeps each tester's word.

## Not tested

- **Audio output** on every AVD: nobody listened. Sound was inferred from the media session, AudioTrack
  state and the mpv log.
- **Volume level** on API 33 and TV 30 (keys sent, level not read back).
- **Quick Connect (SUP-08)** on every AVD: not in the brief's steps.
- **Resume from Continue Watching (PLY-01 as written):** playback was started from item pages; the resume
  position was not checked on purpose.
- **ExoPlayer on long files on API 29** (E1) and **ExoPlayer at all on API 37** (E3): emulator limits.
- **mpv's picture on API 29, 30 and TV 30** (E2): emulator limit. Audio, controls and keys were tested.
- **The PiP button on mpv** on API 29 and 30 (only Home was tried on mpv).
- **Scrubbing on API 29** (double tap only).
- **Download on API 30** (done on API 29), **offline playback on API 30 and 33**.
- **Predictive back in the player on API 36** (tested on API 37 only).
- **Edge-to-edge (LIF-10) on API 33:** the step was for API 36 and 37 only.
- **The library process-death path (F1) on API 29, 30, 33 and 37:** the restored back stack there had no
  library behind the item page.
- **TV:** Search, show, season and episode pages, the seek bar focus, the Interface screen's content
  (opened, not read), the splash screen, and whether a double Center on the Movies grid opens a duplicate
  item page (seen once, not reproduced).
- **App language from the system picker's "System default" entry** on API 33 (reset with `cmd locale`
  instead).
- **The suspected `DownloadPumpService` restart crash** from #351 (a sticky restart in the background on
  Android 12+): nobody killed the process during a download. No
  `ForegroundServiceStartNotAllowedException` appeared in any log.
- **"Hide watched"** on API 33 (button present, not toggled).

## AVD creation commands

API 34 images (`google_apis/x86_64` and `android-tv/x86`) are in the SDK. The others were downloaded and
unpacked outside the SDK, because the sandbox cannot write to it:

| AVD | Port | Image |
|---|---|---|
| `ce_api29` | 5560 | `https://dl.google.com/android/repository/sys-img/google_apis/x86_64-29_r13.zip` |
| `ce_api30` | 5562 | `https://dl.google.com/android/repository/sys-img/google_apis/x86_64-30_r16.zip` |
| `ce_api33` | 5564 | `https://dl.google.com/android/repository/sys-img/google_apis/x86_64-33_r17.zip` |
| `ce_api36` | 5566 | `https://dl.google.com/android/repository/sys-img/google_apis/x86_64-36_r07.zip` |
| `ce_api37` | 5568 | `https://dl.google.com/android/repository/sys-img/google_apis/x86_64-37.0_r08.zip` |
| `ce_tv_api30` | 5570 | `https://dl.google.com/android/repository/sys-img/android-tv/x86-30_r04.zip` |

The images were unpacked to `~/.android/sysimg/android-<api>/<tag>/<abi>/` (for example
`~/.android/sysimg/android-29/google_apis/x86_64/`, `~/.android/sysimg/android-37.0/google_apis/x86_64/`,
`~/.android/sysimg/android-30/android-tv/x86/`).

Each AVD is a copy of a `config.ini` that `avdmanager` made from an installed API 34 image, with the
device `pixel_6` for the phones and `tv_1080p` for TV, and two changes: `image.sysdir.1` set to the
absolute path of the unpacked image, and `hw.keyboard = yes`. For example:

```sh
# Template (once per form factor)
avdmanager create avd -n ce_template_phone -k "system-images;android-34;google_apis;x86_64" -d pixel_6
avdmanager create avd -n ce_template_tv -k "system-images;android-34;android-tv;x86" -d tv_1080p

# One AVD: copy the template and point it at the unpacked image
mkdir -p ~/.android/avd/ce_api29.avd
cp ~/.android/avd/ce_template_phone.avd/config.ini ~/.android/avd/ce_api29.avd/
#   in config.ini: image.sysdir.1=C:/Users/<user>/.android/sysimg/android-29/google_apis/x86_64/
#                  target=android-29, and add: hw.keyboard = yes
#   ~/.android/avd/ce_api29.ini: path=<absolute path of ce_api29.avd>, path.rel=avd/ce_api29.avd, target=android-29
```

Start, one at a time per tester:

```sh
emulator -avd <name> -port <port> -no-snapshot -no-boot-anim -crash-report-mode never -no-metrics -gpu swiftshader_indirect
```

Wait for `adb -s emulator-<port> shell getprop sys.boot_completed` to print 1 (API 36 and 37 can take two
minutes to appear in adb). Stop with `adb -s emulator-<port> emu kill`. The issue asks for the AVDs to
be deleted afterwards; at the time of writing they are still in `~/.android/avd/`.
