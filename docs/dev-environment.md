# Development environment: a test server and test devices

Everything you need to try a change without a Jellyfin server or an Android device of your own:

- a **throwaway Jellyfin server** in Docker, with generated media for the edge cases the app has
  to handle, and test users;
- **emulators** for the phone, the tablet sizes, a foldable and Android TV.

Both are scripts in this repository. With Claude Code, ask for it ("set up the development
environment", "start the test server", "give me a TV emulator"): the `test-server` and
`test-devices` skills in `.claude/skills/` follow this document.

## What you need

[get-started.md](get-started.md) lists what to install per system and the order of the steps;
`scripts/doctor.sh` checks a machine against it. The server needs about 1.5 GB of disk, each
system image over 1 GB, each AVD a few GB once used.

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
tools/test-server/setup.sh https         # a public https address for the server, printed
tools/test-server/setup.sh https stop    # close it again
```

### Over https

The server itself speaks http. `setup.sh https` puts a Cloudflare quick tunnel in front of it (a
second container, `cloudflared`, no account needed) and prints an address such as
`https://some-random-words.trycloudflare.com`. Add that address in the app like any server. Use
it when a change touches how a player loads a stream: mpv reads https through its own TLS
library, not through the app's HTTP client, so a test over http says nothing about it (#960).

- The address is public while the tunnel runs, and the user `nopass` has no password. Close it
  with `setup.sh https stop` when the test is done. It is a new address every time.
- On Windows the tunnel dies when WSL shuts down for being idle. Keep WSL awake for as long as
  the test takes, for example with `wsl -d Ubuntu -- sleep 3600` in a second terminal.
- The tunnel belongs to the server it was started for: `setup.sh stop` and `wipe` close it, and
  after the server has restarted for another reason (a reboot, WSL waking up) run `setup.sh https`
  again. `setup.sh status` shows whether one is up.
- The tunnel is not a reverse proxy like Caddy or nginx on a home server: a bug that needs one
  of those is not shown by it. For that there is the next section.

### Behind a reverse proxy with TLS

`tools/test-server/caddy-tls.sh` (in WSL on Windows, like `setup.sh`) puts Caddy in front of the
running server, with a certificate from a throwaway CA, on six ports from 8443: client certificate
asked for but optional (`client_auth` mode `verify_if_given`, the case of #960), no client
certificate (the control), the same two with TLS 1.2 or TLS 1.3 only, and one that requires a
certificate (#993). `caddy-tls.sh status` shows what each port speaks, `caddy-tls.sh stop`
removes it. The CA and a client certificate signed by it are in `caddy-tls/` in the server's
folder.

The address from an emulator is `https://jellyfin.10.0.2.2.nip.io:8443` (a public name that
resolves to `10.0.2.2`, so the app sends a host name as with a real server). mpv does not check
the server's certificate (`tls-verify=no`), but the app's own HTTP client does: make the CA a user
CA on the emulator first. On a phone image with `adb root`:

```bash
h=$(openssl x509 -subject_hash_old -noout -in ca.crt)
adb push ca.crt /data/local/tmp/$h.0
adb shell "mkdir -p /data/misc/user/0/cacerts-added && cp /data/local/tmp/$h.0 /data/misc/user/0/cacerts-added/ && chown system:system /data/misc/user/0/cacerts-added/$h.0 && chmod 644 /data/misc/user/0/cacerts-added/$h.0"
```

No reboot is needed for the app; the TV image has no root, so there it goes through Settings.

`EPHYRA_TEST_SERVER_DIR`, `EPHYRA_TEST_SERVER_PORT`, `EPHYRA_TEST_SERVER_NAME` and
`EPHYRA_TEST_SERVER_IMAGE` change the folder, the port, the container name and the image. With a
different folder, port and name you can run a second server next to the first. Pick a port
nothing else holds: on Windows a process listening on `0.0.0.0:<port>` wins over WSL for IPv4,
so an emulator (`10.0.2.2` is IPv4) reaches that process while `curl localhost` on the host
reaches the new server. `netstat -ano | grep :<port>` on Windows, `ss -ltn` on Linux.

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
python3 tools/test-server/user.py remove alice           # deletes user, its server data, and creds.env entry (refuses setup users)
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
| Image Subs Movie | Two image subtitle tracks, a white bar 4 of every 5 seconds: PGS (English) and VobSub (Dutch). At about 1.9 Mbps, so a quality cap of 1.5 Mbps transcodes it: which image subtitles the server delivers as a file during a transcode |
| Hevc Movie, Hdr Ten Movie | HEVC 10-bit; HEVC 10-bit with PQ and BT.2020 (HDR10 signalling) |
| Versions Movie | Two versions of one movie (360p, 720p) |
| Broken Movie | A file that is not a video: playback has to fail cleanly |
| Test Show | Season 1 (5 episodes), Season 2 (2), an empty Season 3, Specials |
| Empty Show, Empty Season Show, One Episode Show | A show without seasons, with one empty season, with one episode |
| Long Season Show | 30 episodes in one season |
| Und Audio Show | An audio track whose language is `und` |
| Tracks Show | 3 episodes with two audio tracks (AAC English, the default, and AAC Dutch tagged `dut`), at about 2 Mbps so a quality cap of 1.5 Mbps transcodes them: the per-series audio choice on a transcode |
| Chapters Show | 2 episodes of 6 minutes with four named chapters (Opening, Part One, Part Two, Credits): chapters in the player, also on the episode a show's Play starts |

**Real titles** (libraries `Real Movies` and `Real Shows`): the same test pattern under real
names (14 movies, 5 shows such as Breaking Bad and Bluey), so the server fetches posters,
overviews, cast and episode data. These need internet on the first scan, and what the metadata
providers return can change, so do not assert on their exact content.

**A large library** (`Big Movies`): about 650 movies, hard links of one 5-second clip, with
names spread over the alphabet (10 to 40 per letter, none under X, one "The ..." per letter) and
a few under digits, symbols and accents. No artwork. For the phone's A–Z fast scroller and
anything else that needs a grid that scrolls a long way.

**Other library types** (`Home Videos` of the type Home Videos & Photos, and `Music Videos`):
Home Videos has a 6-minute clip at the top (resume), a plain folder with two clips, a folder with
a clip and a photo (the server makes that one a `PhotoAlbum`), and a loose photo. Music Videos
has three clips, one of them in an artist folder; the server lists all three flat, as
`MusicVideo` items.

To add a case, add it to `setup.sh` under a marker file of its own (as `.generated-extra` does)
and to the table above. A file dropped into `media/` by hand works until the next `wipe --media`,
and nobody else gets it.

Not on the server: Dolby Vision, collections, live TV, music and a base-URL path. For https
there is the tunnel, and Caddy for a reverse proxy with TLS (see above).
`docs/track-test-series.md` has a second generator, for track selection and subtitle styling.

## The emulators

```bash
scripts/avds.sh images          # which system images are needed, and whether they are installed
scripts/avds.sh create          # every AVD that does not exist yet
scripts/avds.sh start ce_phone  # cold boot, returns when booted, media volume at 0
scripts/avds.sh up ce_phone     # create if missing, start if not running, install the debug build
scripts/avds.sh list
```

`start` (and so `up`) passes `-crash-report-mode never -no-metrics`, without which a new emulator
can hang on a dialog nobody sees, and turns the media volume down to 0 after boot: the test media
is a test tone. `EMULATOR_FLAGS` adds flags of your own, such as `-no-window`.

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

### Older Android versions

`avds.sh` makes API 34 AVDs only. Tests across Android versions have used these as well, made by
hand with `avdmanager create avd -n <name> -k "<image>" -d pixel_6` (quoted: the image name has semicolons) (`-d tv_1080p` for TV) after
installing the image with `sdkmanager`:

| API | Image | Why it is worth a run |
|---|---|---|
| 28 | `system-images;android-28;android-tv;x86` | The TV app's oldest version. Only GLES 2.0: mpv's default `gpu-next` shows a black picture, `pref_player_mpv_vo=gpu` shows video. No `uinput`, so `scripts/tv-dpad.sh` fails there |
| 29 | `system-images;android-29;google_apis;x86_64` | The phone app's oldest version |
| 30, 33 | `system-images;android-<NN>;google_apis;x86_64` | Versions in between |
| 36 | `system-images;android-36;google_apis;x86_64` | Takes over a minute to show up in `adb devices` |
| 37 | `system-images;android-37.0;google_apis;x86_64` | ExoPlayer cannot decode H.264 there (test playback on mpv); the app asks for the local network permission at first start (`adb shell pm grant <package> android.permission.ACCESS_LOCAL_NETWORK`) |

Give each a port of its own (`-port` on the emulator command line) that the table above does not
use. What the table says about each version comes from the test runs of November 2026; the image
names were checked against `sdkmanager --list`.

`sdkmanager` now prints that it is deprecated in favour of `android sdk` (the `android` binary in
the same `cmdline-tools/latest/bin` folder). It still installs packages.

### The app on an emulator

```bash
./gradlew :app:phone:assembleLibreDebug   # or :app:tv:
scripts/avds.sh install ce_phone          # picks phone or tv and the ABI
```

`scripts/avds.sh up ce_phone` does the create, the start and the install in one go.

The debug build is its own app (`nl.midasvo.findroid.ce.debug`). Then, in the app: add the server
`http://10.0.2.2:8096` and log in as a test user. `install` keeps the app's data, so you log in
once per AVD. Installing an older build over a newer one can drop the database, and the login
with it.

### Things that cost us time

- **Git Bash on Windows** rewrites `/sdcard/...` in `adb shell` commands: `export MSYS_NO_PATHCONV=1`.
- **TV text fields ignore `adb shell input keyevent` D-pad keys**: they come from a virtual device.
  `scripts/tv-dpad.sh` sends them from a real one, and holds each key like a finger does:
  `scripts/tv-dpad.sh ce_tv_1080p down down ok`.
- **A new emulator that never shows up in `adb devices`** is waiting on the crash-report dialog.
  `avds.sh start` passes `-crash-report-mode never -no-metrics`; pass them yourself when you start
  an emulator another way.
- **`cmd media_session volume --stream 3 --set 0`** reports success and leaves the volume as it
  was on the API 34 images. Volume-down key presses work: `avds.sh start` sends sixteen.
- **A restarted emulator can come up on another port** when the old one is still shutting down.
  Run `adb devices` again instead of assuming the serial.
- **Server gone, internet still there**: on a phone or tablet AVD, `adb root`, then
  `adb shell iptables -I OUTPUT -d 10.0.2.2 -p tcp --dport 8096 -j REJECT --reject-with tcp-reset`;
  the same rule with `-D` removes it. Airplane mode: `adb shell cmd connectivity airplane-mode enable`.
- **On the TV image none of that works**: it has no root, its network is Ethernet, which airplane
  mode leaves up, and `adb emu network speed` reports a speed without limiting anything. Take the
  server away from one emulator with a forwarder instead: `python3 tools/test-server/forward.py`
  (next to the server, so in WSL on Windows) listens on 8097 and passes everything to 8096. Log
  that emulator in to `http://10.0.2.2:8097`; killing the script is "connection lost", starting it
  again is "back", and nobody else notices. A connection to the dead port times out instead of
  being refused, so a player takes a minute or more to give up. A third argument limits the speed
  (`forward.py 8097 8096 600` for 600 kbit/s): without it a player has a whole test file buffered
  within a second and plays on without the server. The limit slows long answers only: to hold
  one request instead, so the server answers late, add `--hold METHOD PATH-PREFIX` (repeatable),
  for example `forward.py 8984 8096 --hold POST /Sessions/Playing/Stopped`. A held request is
  passed on when Enter is pressed in the script's terminal or when the file named by
  `--release-file` appears (the oldest held request per release; a release while nothing is held is dropped;
  `--hold-max SECONDS` releases by itself), and `--log` prints every request with the start of
  its body. The script half-closes a connection when one side ends, so
  mpv does not see a reset, and it refuses a port another process already listens on.
- **Scripted taps**: dump the screen (`uiautomator dump`) and check it before every tap. Keys sent
  blind land on whatever screen is open.
- **A script that logs in to the server** needs a `DeviceId` of its own in its `Authorization`
  header. A second login with the same `DeviceId` ends the first one's session.
- **Android 17 images**: ExoPlayer cannot decode H.264 on the emulator; test playback there on mpv.

## What this does not replace

A real device, for anything about decoders, HDR and Dolby Vision output, refresh rates, audio
passthrough and performance. The emulator plays everything in software.
