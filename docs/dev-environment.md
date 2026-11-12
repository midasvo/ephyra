# Development environment: a test server and test devices

Everything you need to try a change without a Jellyfin server or an Android device of your own:

- a **throwaway Jellyfin server** in Docker, with generated media for the edge cases the app has
  to handle, and test users;
- **emulators** for the phone, the tablet sizes, a foldable and Android TV.

Both are scripts in this repository. With Claude Code, ask for it ("set up the development
environment", "start the test server", "give me a TV emulator"): the `test-server` and
`test-devices` skills in `.claude/skills/` follow this document.

## What you need

| For | Needs |
|---|---|
| Building | JDK 21 and the Android SDK (`ANDROID_HOME` set) |
| The test server | Docker and `python3`. On Windows: inside WSL, with Docker in WSL or Docker Desktop's WSL integration |
| The emulators | The SDK's `emulator`, `platform-tools` and `cmdline-tools`, hardware acceleration (KVM, HVF or WHPX), and two system images |

The server needs about 1.5 GB of disk, each system image over 1 GB, each AVD a few GB once used.

## The test server

```bash
tools/test-server/setup.sh
```

On Windows, from Git Bash or PowerShell:

```bash
wsl -d Ubuntu -- bash tools/test-server/setup.sh
```

The first run takes about two minutes plus the image download: it pulls the Jellyfin image,
generates the media with the ffmpeg inside that image, starts the container, runs the setup
wizard through the API, adds the libraries and users, and waits for the library scan. Run it
again any time: it skips what exists and starts the container if it was stopped. It ends with a
report of what the server holds.

| | |
|---|---|
| Address on the host | `http://localhost:8096` |
| Address from an emulator | `http://10.0.2.2:8096` |
| Server name | Ephyra Test |
| Data | `~/ephyra-test-server` (inside WSL on Windows): `config`, `cache`, `media`, `creds.env` |
| Jellyfin version | fixed in `setup.sh`, so everyone tests against the same server |

Other commands:

```bash
tools/test-server/setup.sh status        # is it up, and the report
tools/test-server/setup.sh stop
tools/test-server/setup.sh wipe          # new server, same media and passwords
tools/test-server/setup.sh wipe --media  # also regenerate the media
```

`EPHYRA_TEST_SERVER_DIR`, `EPHYRA_TEST_SERVER_PORT`, `EPHYRA_TEST_SERVER_NAME` and
`EPHYRA_TEST_SERVER_IMAGE` change the folder, the port, the container name and the image. With a
different folder, port and name you can run a second server next to the first.

### Users

The passwords are generated on the first run and live in `creds.env` (mode 600), next to the
data. Nothing prints them.

| User | For |
|---|---|
| `admin` | The API scripts. Do not log in to the app with it |
| `tester` | The normal case: may download |
| `nodownload` | A user the server does not let download |
| `second` | A second profile on the same server |
| `nopass` | A user with an empty password |

```bash
python3 tools/test-server/user.py list
python3 tools/test-server/user.py add alice              # --no-download, --no-password
python3 tools/test-server/user.py rotate alice
python3 tools/test-server/user.py remove alice
```

Watched state and resume positions belong to a user. When two people or agents test at the same
time, each takes a user of their own.

### Media

All of it is a test pattern with a tone, so it is small and free to share.

**Edge cases** (libraries `Movies` and `Shows`, no internet metadata, trickplay on):

| Item | Tests |
|---|---|
| Plain Movie (5 min), Short Movie (20 s, `.mp4`) | The ordinary case, and an item that ends at once |
| Long Movie (12 min), Resume Show (4 episodes of 12 min) | Resume positions: Jellyfin keeps one only for items of 5 minutes or more, and a stop under 10 % resets it |
| A Movie With A Really Long Title… | Wrapping in rows and on pages |
| Tracks Movie | Two audio tracks (AAC stereo English, AC-3 5.1 Dutch), two embedded subtitles (one forced), one external `.de.srt` |
| Hevc Movie, Hdr Ten Movie | HEVC 10-bit; HEVC 10-bit with PQ and BT.2020 (HDR10 signalling) |
| Versions Movie | Two versions of one movie (360p, 720p) |
| Broken Movie | A file that is not a video: playback has to fail cleanly |
| Test Show | Season 1 (5 episodes), Season 2 (2), an empty Season 3, Specials |
| Empty Show, Empty Season Show, One Episode Show | A show without seasons, with one empty season, with one episode |
| Long Season Show | 30 episodes in one season |
| Und Audio Show | An audio track whose language is `und` |
| Tracks Show | 3 episodes with two audio tracks (AAC English, the default, and AAC Dutch tagged `dut`), at about 2 Mbps so a quality cap of 1.5 Mbps transcodes them: the per-series audio choice on a transcode |

**Real titles** (libraries `Real Movies` and `Real Shows`): the same test pattern under real
names (14 movies, 5 shows such as Breaking Bad and Bluey), so the server fetches posters,
overviews, cast and episode data. These need internet on the first scan, and what the metadata
providers return can change, so do not assert on their exact content.

To add a case, add it to `setup.sh` under a marker file of its own (as `.generated-extra` does)
and to the table above. A file dropped into `media/` by hand works until the next `wipe --media`,
and nobody else gets it.

Not on the server: Dolby Vision, collections, live TV, music, a base-URL path, HTTPS.
`docs/track-test-series.md` has a second generator, for track selection and subtitle styling.

## The emulators

```bash
scripts/avds.sh images          # which system images are needed, and whether they are installed
scripts/avds.sh create          # every AVD that does not exist yet
scripts/avds.sh start ce_phone  # cold boot, returns when booted
scripts/avds.sh list
```

| AVD | Serial | What |
|---|---|---|
| `ce_phone` | `emulator-5554` | Pixel 6, Android 14 |
| `ce_phone_tmp` | `emulator-5580` | A second phone, for throwaway logins |
| `ce_tablet_8` | `emulator-5582` | 8 inch tablet, 1024x640 dp |
| `ce_tablet_10` | `emulator-5584` | Pixel Tablet, 1280x800 dp |
| `ce_fold` | `emulator-5586` | Foldable |
| `ce_tv_1080p` | `emulator-5572` | Android TV 14, 1080p, 32-bit x86 |
| `ce_tv_4k` | `emulator-5574` | Android TV 14, 4K (same dp as 1080p: only artwork and memory differ) |

The system images are `system-images;android-34;google_apis;x86_64` and
`system-images;android-34;android-tv;x86`. Install them yourself with `sdkmanager`: it asks you
to accept Google's licence, which the script does not do for you. On Windows `sdkmanager.bat`
splits a package name on `;`: put the name in a file and pass `--package_file=<file>`.

`create` never replaces an AVD that exists (`--force` does, and loses its app data). `start` uses
software rendering, because mpv crashes emulators that render on the host GPU; `start <name>
--gpu` is faster for work that never opens mpv. Three emulators next to a Gradle build is fine on
a strong machine; more makes everything slow.

### The app on an emulator

```bash
./gradlew :app:phone:assembleLibreDebug   # or :app:tv:
scripts/avds.sh install ce_phone          # picks phone or tv and the ABI
```

The debug build is its own app (`nl.midasvo.findroid.ce.debug`). Then, in the app: add the server
`http://10.0.2.2:8096` and log in as a test user. `install` keeps the app's data, so you log in
once per AVD. Installing an older build over a newer one can drop the database, and the login
with it.

### Things that cost us time

- **Git Bash on Windows** rewrites `/sdcard/...` in `adb shell` commands: `export MSYS_NO_PATHCONV=1`.
- **TV text fields ignore `adb shell input keyevent` D-pad keys**: they come from a virtual device.
  `scripts/tv-dpad.sh` sends them from a real one, and holds each key like a finger does:
  `scripts/tv-dpad.sh ce_tv_1080p down down ok`.
- **A new emulator that never shows up in `adb devices`** is waiting on the crash-report dialog:
  start it with `EMULATOR_FLAGS="-crash-report-mode never -no-metrics"`.
- **Server gone, internet still there**: on a phone or tablet AVD, `adb root`, then
  `adb shell iptables -I OUTPUT -d 10.0.2.2 -p tcp --dport 8096 -j REJECT --reject-with tcp-reset`;
  the same rule with `-D` removes it. Airplane mode: `adb shell cmd connectivity airplane-mode enable`.
  The TV image has no root.
- **Scripted taps**: dump the screen (`uiautomator dump`) and check it before every tap. Keys sent
  blind land on whatever screen is open.
- **A script that logs in to the server** needs a `DeviceId` of its own in its `Authorization`
  header. A second login with the same `DeviceId` ends the first one's session.
- **Android 17 images**: ExoPlayer cannot decode H.264 on the emulator; test playback there on mpv.

## What this does not replace

A real device, for anything about decoders, HDR and Dolby Vision output, refresh rates, audio
passthrough and performance. The emulator plays everything in software.
