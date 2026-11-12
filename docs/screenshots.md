# Screenshots

Every screenshot in the README, on the website and in the Play listing comes from the
**Jellyfin demo server** (`https://demo.jellyfin.org/stable`, user `demo`, empty password): a
public-domain library (Sahara, King Lear, Pioneer One, Caminandes…), so there is no third-party
artwork and no personal server name in the repo. The one exception is the download screens
(§Phone): the demo account can't download (`EnableContentDownloading` is off in its policy), so
those come from the maintainer's own server.

Where they live:

| Use | Folder | Format |
|---|---|---|
| README and website | `images/screenshots/` (copy to `site/images/screenshots/` after a change: GitHub Pages deploys only `site/`) | webp, phone 540 wide, tablet and TV 1280 wide |
| Play listing | `fastlane/metadata/android/en-US/images/{phone,sevenInch,tenInch,tv}Screenshots/` | PNG, native size, `N_en-US.png` |

General rules: dark theme, English UI (`adb shell cmd locale set-app-locales
nl.midasvo.findroid.ce.debug --locales en-US`; note the old value and restore it), status bar in
demo mode, no open drawer on TV (it shows the user and server name). Look at every file before
committing.

The demo account is shared with everyone trying Jellyfin, so Continue Watching holds whatever
the last visitor left: watched items with a resume position, a film at 111 %. Set the state you
want before the Home shots, and again after each player shot, with
`POST /UserItems/{id}/UserData?userId=…` (`Played`, `PlaybackPositionTicks`, `LastPlayedDate`):
Pioneer One E1 played and E2 untouched gives a Next Up row. It resets daily.

Demo-mode status bar (12:00, wifi, full battery), and `exit` when done. Without `-e fully true`
the wifi icon carries the emulator's "no internet" mark:

```bash
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1200
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e fully true
adb shell am broadcast -a com.android.systemui.demo -e command exit
```

Capture: save on the device, then pull. Redirecting `screencap` in PowerShell corrupts the PNG,
and Git Bash rewrites `/sdcard/...` unless `MSYS_NO_PATHCONV=1` is set.

```bash
MSYS_NO_PATHCONV=1 adb -s <serial> shell screencap -p /sdcard/s.png
MSYS_NO_PATHCONV=1 adb -s <serial> pull /sdcard/s.png out.png
```

## Emulators

`scripts/avds.sh` creates and starts the AVDs (`create`, `start`, `stop`, `delete`, `portrait`,
`landscape`, `shot`); see [tablet/6-test-matrix.md](tablet/6-test-matrix.md). Its AVDs are also
the test devices, logged in to the test server and often in use by another session: for
screenshots, make your own with `avdmanager create avd` from the same device profiles (`pixel_6`,
`small_tablet` with `hw.lcd` set to 2048×1280 at 320 dpi, `pixel_tablet`, `tv_1080p`) on a free
port, and delete them afterwards.

| Device profile | Window | Used for |
|---|---|---|
| `pixel_6` | 1080×2400 px, 412×915 dp | phone README and listing |
| `small_tablet`, set to 2048×1280 at 320 dpi | 1024×640 dp | 7-inch listing |
| `pixel_tablet` | 2560×1600 px, 1280×800 dp | tablet README and 10-inch listing |
| `tv_1080p`, `android-tv;x86` image | 1920×1080 | TV README and listing |

Install the debug build of `main` (`./gradlew :app:phone:assembleLibreDebug`, the `x86_64` APK;
`:app:tv:assembleLibreDebug`, the `x86` APK) and sign in: Add server → Users → tap demo → Login.
Delete the AVDs afterwards; they are 3 to 6 GB each.

## Phone

Portrait shots lose the status bar and the navigation bar. On the Pixel 6 emulator (gesture
navigation, 2400 px tall) that is `crop=1080:2190:0:130`; on a phone with three-button navigation
the bar is taller, use `crop=1080:2166:0:100` (OPPO CPH2359).

```bash
# Portrait
ffmpeg -i in.png -vf "crop=1080:2190:0:130,scale=540:-1:flags=lanczos" \
  -c:v libwebp -quality 84 -compression_level 6 images/screenshots/name.webp

# Landscape player
ffmpeg -i in.png -vf "scale=1080:-1:flags=lanczos" \
  -c:v libwebp -quality 84 -compression_level 6 images/screenshots/name.webp
```

Keep each file under about 200 KB. The README shows the portrait shots in a table of three columns,
so add or remove them in threes.

| File | Screen | Source |
|---|---|---|
| `home.webp` | Home with the carousel, Continue Watching and Next Up | demo |
| `movie.webp` | Sahara's page from the top | demo |
| `episode.webp` | Pioneer One S1E2 from the top | demo |
| `library.webp` | The Movies grid (website only) | demo |
| `favorites.webp` | The Favorites tab, with a few items favorited through the API (website only) | demo |
| `profile-lock.webp` | Settings → Profile lock → Pattern, captured while the pattern is being drawn (website only) | demo |
| `downloads.webp` | Downloads with the storage bar, active rings and a finished show | **own server** |
| `season.webp` | A season with downloaded, downloading and not downloaded episodes | **own server** |
| `downloaded-show.webp` | A downloaded show page with episode sizes | **own server** |
| `download-quality.webp` | The download quality dialog over a movie page | **own server** |
| `smart-downloads.webp` | Settings → Downloads with Smart Downloads on (switch it off again) (website only) | **own server** |
| `offline.webp` | Home with Settings → Offline Mode on, after the downloads finished (website only) | **own server** |
| `subtitle-style.webp` | Settings → Player → Subtitle style with a non-default colour | demo |
| `home-rows.webp` | Settings → Interface → Home rows | demo |
| `player-quality.webp` | Landscape: the video quality dialog over the player | demo |
| `up-next.webp` | Landscape: the "Up next" card in the last 15 seconds of Pioneer One S1E1 (website only) | demo |

How the less obvious ones are made:

- `up-next.webp`: ExoPlayer shows green at the end of the file on the emulator, so switch to mpv
  (see Android TV below), set S1E1's position to 40 seconds before its end through the API,
  resume it and take a screenshot every few seconds.
- `profile-lock.webp`: `input motionevent DOWN`, a `MOVE` per dot, `screencap`, then `UP`, in one
  `adb shell` call, so the pattern is still lit.

For the own-server shots: the same phone AVD and crop as the rest. Get it to the Add server
screen and let the maintainer sign in; never type their password. Download two episodes first
and wait for them, then start a large movie and three more episodes: that gives the season shot
(downloaded, downloading, not downloaded) and the Downloads shot (two rings, one waiting, a
finished show; the limit is two at a time). Use Bluey for the show. The server name and the
user's initial show on Home, and that is fine; the server address is not: it is in the sheet
behind the server name on Home, so never capture that. Deleting the AVD removes the downloads.

## Tablet

A `pixel_tablet` AVD in landscape. For the portrait shot `adb emu rotate` turns it when
`user_rotation` does not. The launcher's
taskbar sits at the bottom of every screen except the player: crop it (it starts at y=1480 in
landscape, 2440 in portrait).

```bash
# Landscape, taskbar cropped
ffmpeg -i in.png -vf "crop=2560:1480:0:0,scale=1280:-1:flags=lanczos" \
  -c:v libwebp -quality 80 -compression_level 6 images/screenshots/tablet-name.webp
# Player (no taskbar)
ffmpeg -i in.png -vf "scale=1280:-1:flags=lanczos" -c:v libwebp -quality 80 -compression_level 6 out.webp
```

The foldable has two displays: pass `screencap -d <id>` (from `dumpsys SurfaceFlinger
--display-id`) and tap the lower half to bring up the controls in tabletop posture.

| File | Screen |
|---|---|
| `tablet-home.webp` | Home: rail, three carousel pages, Continue Watching |
| `tablet-movie.webp` | Sahara's hero page with the poster beside the text |
| `tablet-season.webp` | Pioneer One Season 1 as list-detail, E2 selected |
| `tablet-player.webp` | The player with the controls and the episode panel open |
| `tablet-library.webp` | The Movies grid |
| `tablet-home-portrait.webp` | Home in portrait (not in the README today) |
| `fold-tabletop.webp` | A `pixel_fold` AVD, half-opened in portrait, the player with its controls (website only) |

## Android TV

A `tv_1080p` AVD. `adb shell input tap` doesn't reach the TV app: drive it with
`scripts/tv-dpad.sh <serial> up|down|left|right|ok|back`, a few keys at a time with a screenshot
before the next, because focus restore can put you somewhere unexpected. `input text` works in
the server address field once the on-screen keyboard is closed (Back).

ExoPlayer shows a solid green picture on this emulator. Play with mpv for the two player shots:
with the app stopped, add `<string name="pref_player_backend">mpv</string>` to
`shared_prefs/nl.midasvo.findroid.ce.debug_preferences.xml` through `run-as`. The trickplay
thumbnail is gone a second after the last key, so take screenshots while a run of `right` keys is
still going and keep the best one.

The screensaver starts through Android's idle timeout, because `cmd dreams` needs root and the
TV image has none. It changes slide every 20 seconds:

```bash
adb shell settings put secure screensaver_components   nl.midasvo.findroid.ce.debug/dev.jdtech.jellyfin.ce.presentation.screensaver.ScreensaverService
adb shell settings put secure screensaver_activate_on_sleep 1
adb shell settings put system screen_off_timeout 15000
```

```bash
ffmpeg -i in.png -vf "scale=1280:-1:flags=lanczos" \
  -c:v libwebp -quality 80 -compression_level 6 images/screenshots/tv-name.webp
```

| File | Screen | Issue |
|---|---|---|
| `tv-home.webp` | Home with the hero, focus on the first card | #132 |
| `tv-library.webp` | Movies grid, focus on a card so the A–Z bar and counter show | #135 |
| `tv-season.webp` | Pioneer One Season 1 with an episode's details | #130 |
| `tv-movie.webp` | Sahara's page, Play focused | |
| `tv-player.webp` | Player overlay, shown with Down | #131 |
| `tv-trickplay.webp` | Right a few times with the controls hidden | #134 |
| `tv-screensaver.webp` | The screensaver on a slide with a title logo (website only) | #140 |

## Play listing

Native PNGs, same subjects per form factor (Home, a movie page, the Movies library, Pioneer One
Season 1, the player with controls). Google Play rejects a side longer than twice the other, so the
phone shots are cropped to 1080×2160 (`crop=1080:2160:0:0`; the landscape player to
`crop=2160:1080:120:0`). The tablet sets crop the taskbar (`2560×1480` on 10-inch, `2048×1160` on
7-inch); the player shots are full frames. Details per set in [play-store.md](play-store.md).
