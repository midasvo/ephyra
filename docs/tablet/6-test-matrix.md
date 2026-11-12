# Phase 6: test matrix

How to check a tablet change on the five sizes of [design rule 9](4-design.md). Run it for any PR that touches a screen in the [§12 screen matrix](4-design.md#12-screen-matrix), and once for the whole list before a release.

## The five sizes

| Size | AVD | Profile | Window | Port |
|---|---|---|---|---|
| Phone | `ce_phone_tmp` | `pixel_6` | 412×915 dp (and 915×412 rotated) | 5580 |
| 8" tablet | `ce_tablet_8` | `small_tablet`, resized to 1024×640 px at 160 dpi | 1024×640 dp | 5582 |
| 10.5" tablet | `ce_tablet_10` | `pixel_tablet` | 1280×800 dp, both orientations (natural: landscape) | 5584 |
| Foldable | `ce_fold` | `pixel_fold` (else `pixel_9_pro_fold`, `pixel_10_pro_fold`) | 841×701 dp unfolded (2208×1840 px at 420 dpi, natural: landscape) | 5586 |
| 13" Chromebook | none | the `resizable` profile, or any AVD with the emulator window resized | 1600×900 dp | |

All use `system-images;android-34;google_apis;x86_64` and `hw.keyboard = yes`. The ports are distinct from the TV AVDs and from `ce_tablet_tmp` (5562), so they can run side by side.

## AVD script

`scripts/tablet-avds.sh` (bash; Git Bash on Windows, Linux, macOS). It needs `ANDROID_HOME`.

```bash
export ANDROID_HOME=C:/Users/midas/AppData/Local/Android/Sdk   # this machine

scripts/tablet-avds.sh create [name|all]    # default: all four
scripts/tablet-avds.sh start ce_tablet_10   # cold boot, returns when booted
scripts/tablet-avds.sh portrait ce_tablet_10
scripts/tablet-avds.sh landscape ce_tablet_10
scripts/tablet-avds.sh shot ce_tablet_10 out.png
scripts/tablet-avds.sh stop ce_tablet_10
scripts/tablet-avds.sh delete [name|all]
```

- `portrait` and `landscape` turn off auto-rotate and set `user_rotation`. The script knows that the natural orientation of the tablets and the unfolded fold is landscape (so portrait is `user_rotation 1` there), and of the phone is portrait.
- `shot` is `screencap` plus `adb pull` with `MSYS_NO_PATHCONV=1`; redirecting `screencap` in PowerShell corrupts the PNG.
- Install the `x86_64` debug APK (`./gradlew :app:phone:assembleLibreDebug`) and log in to the demo server (`https://demo.jellyfin.org/stable`, user `demo`, empty password).
- Split-screen: `am start --windowingMode 6` and `am task resize <id> 0 0 1200 1600`. Fold and unfold: the emulator's Extended controls, Virtual sensors, Device posture, or `adb emu posture <id>` (1 closed, 2 half-opened, 3 opened). The unfolded fold's hinge is vertical in its natural landscape, so tabletop is half-opened in portrait: `adb shell wm user-rotation lock 1` (a posture change resets a plain `settings put system user_rotation`).
- Delete the AVDs when done; they are 3 to 6 GB each.

## Screens to check

At every size, in light and dark theme, check the screens below in the state that stresses them. The §12 matrix says what each should look like.

| Screen | What to look at |
|---|---|
| Home | Rail or bar, number of pages, card sizes, Continue Watching row |
| Media, Library, Favorites, Search | Cell size, search bar cap (720 dp), counts of columns |
| Downloads | List at medium, two columns at expanded |
| Movie, Show, Episode | Hero with poster, backdrop ratio, status-bar scrim, rows at full width |
| Season | Header and list below 840 dp, list-detail 40/60 at 840 dp and up; open an episode and rotate |
| Person | Row layout |
| Settings, setup | Caps at 640 and 480 dp |
| Player | Orientation, controls, gestures, keyboard, side sheet |

## Acceptance criteria

From [§13](4-design.md#13-acceptance-criteria). Tick them per size.

- [ ] No page in a portrait window shows a backdrop cropped tighter than 2.2:1. In a landscape window the 45 % height cap of `4-design.md` §6 decides instead, so that the poster, title and Play stay on the first screen: the crop there is up to 3.6:1 at the five sizes (decided in #432; #622 is the design that would show the picture at 16:9). No running text column is wider than 720 dp.
- [ ] Movie, Show and Episode at expanded use the hero layout with the poster; Season at expanded is list-detail; rotating a tablet keeps the selected episode and the scroll position.
- [ ] The player opens in the device's current orientation on medium and expanded windows; volume and brightness swipes at expanded width only work within 240 dp of an edge.
- [ ] Space, Left/Right and Esc work in the player; Tab reaches every control on Home, Media and an item page.
- [ ] Split-screen at 600 dp and PiP work without visual breakage; playback survives a rotate, a fold and a resize.
- [ ] Tabletop posture puts the video above the fold.
- [ ] The phone layout at 412×915 is unchanged from `main` before this work, except the status-bar scrim.
- [ ] `docs/play-store.md` and `fastlane/.../tenInchScreenshots/` have Ephyra's own tablet screenshots.

## Store screenshots

The 10-inch screenshots in `fastlane/metadata/android/en-US/images/tenInchScreenshots/` (`1_en-US.png` to `5_en-US.png`) are taken on `ce_tablet_10`, landscape, from the demo server, which only has public-domain media (see [play-store.md](../play-store.md)).

| File | Screen |
|---|---|
| `1_en-US.png` | Home with Continue Watching |
| `2_en-US.png` | Movie page (Sahara) |
| `3_en-US.png` | Movies library |
| `4_en-US.png` | Season in list-detail (Pioneer One, Season 1) |
| `5_en-US.png` | Player with controls (Pioneer One S1:E1) |

1. Turn on demo mode for a clean status bar, and leave it afterwards:

   ```bash
   adb shell settings put global sysui_demo_allowed 1
   adb shell am broadcast -a com.android.systemui.demo -e command enter
   adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1200
   adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
   adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e fully true
   adb shell am broadcast -a com.android.systemui.demo -e command network -e mobile hide
   adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
   # ...
   adb shell am broadcast -a com.android.systemui.demo -e command exit
   ```

2. Set the app language to English (`cmd locale set-app-locales ... --locales en-US`) and restore it afterwards. The demo server keeps a Continue Watching row by itself.
3. Capture with `scripts/tablet-avds.sh shot`. The Pixel Tablet's taskbar shows third-party app icons at the bottom, so every shot except the player is cropped to 2560×1480:

   ```bash
   ffmpeg -i in.png -vf "crop=2560:1480:0:0" 1_en-US.png
   ```

4. Player: the controls only show on a tap, and seeking by dragging the bar does not work with `input swipe`, so tap the forward button (about 30 s each) to a bright scene, wait a few seconds so the frame is sharp, tap once to show the controls, then capture. Leave with Back; the demo server resets daily, so resume positions don't matter.
