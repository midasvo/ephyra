# Test server and emulators

Status: machine check script (`scripts/doctor.sh`), throwaway Docker Jellyfin test server with generated edge-case media and TLS/tunnel helpers (`tools/test-server/`), and seven API 34 AVDs with TV D-pad and track-series scripts (`scripts/`). No app or Gradle module lives in this area. As of 2026-11-25 (`main`).

## Start here

- Machine setup and health check: [get-started.md](../get-started.md) lists what to install per OS in order; [`scripts/doctor.sh`](../../scripts/doctor.sh) checks `build`, `site`, `server`, `devices`, and `github` without modifying anything.
- Reference and commands: [dev-environment.md](../dev-environment.md) is the single reference for the test server (addresses, users, generated media, `https` tunnel, `caddy-tls.sh`, `forward.py`) and the emulators (AVDs, serials, older API matrix, troubleshooting).
- Agent procedures and safety rules: read the [test-server skill](../../.claude/skills/test-server/SKILL.md) before touching the Docker test server or users, and the [test-devices skill](../../.claude/skills/test-devices/SKILL.md) before starting, installing on, or sending input to an emulator.
- Extra track and subtitle test data: [track-test-series.md](../track-test-series.md) and [`scripts/make-track-test-series.sh`](../../scripts/make-track-test-series.sh) generate multi-track and styled-ASS/SRT test series.
- Test fast without Docker or emulators: `bash scripts/doctor.sh build`, `python3 tools/test-server/forward.py --help`, and `python3 .claude/skills/start-work/scripts/check-docs.py`.

## What it does

This area provides a reproducible local environment so contributors and agents can test Ephyra without a personal Jellyfin server or physical Android hardware:

- **Machine readiness (`scripts/doctor.sh`):** inspects Git, JDK 21, Android SDK (`local.properties`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, platform/build-tools/platform-tools), Python 3, Node/npm, Docker + `python3` (in WSL on Windows) + server reachability on `127.0.0.1:<port>`, emulator/acceleration/AVD tools/system images, and `gh` auth status.
- **Throwaway Jellyfin server (`tools/test-server/`):** [`setup.sh`](../../tools/test-server/setup.sh) pulls a pinned Jellyfin image, uses its bundled `ffmpeg` (plus [`pgs.py`](../../tools/test-server/pgs.py) for PGS/VobSub image subtitles) to generate edge-case movies, shows, real-title metadata items, a ~650-movie `Big Movies` library, and `Home Videos` / `Music Videos` libraries, starts the container, and runs [`configure.py`](../../tools/test-server/configure.py) (via shared [`jf.py`](../../tools/test-server/jf.py)) to complete the wizard and create test users (`admin`, `tester`, `nodownload`, `second`, `nopass`) with passwords stored in `creds.env`.
- **Network, TLS, and fault injection (`tools/test-server/`):** `setup.sh https` attaches a `cloudflared` quick tunnel for testing mpv over HTTPS ([#960](https://github.com/midasvo/ephyra/issues/960)); [`caddy-tls.sh`](../../tools/test-server/caddy-tls.sh) puts Caddy with a throwaway CA in front of the server across six ports covering optional/required client certificates and TLS 1.2/1.3 ([#960](https://github.com/midasvo/ephyra/issues/960), [#993](https://github.com/midasvo/ephyra/issues/993)); [`forward.py`](../../tools/test-server/forward.py) acts as a per-emulator TCP/HTTP proxy to simulate server loss, bandwidth caps, or held requests (`--hold`, `--release-file`, `--hold-max`, `--log`); and [`user.py`](../../tools/test-server/user.py) manages per-tester accounts.
- **Emulators and input (`scripts/`):** [`avds.sh`](../../scripts/avds.sh) manages seven API 34 AVDs (`ce_phone`, `ce_phone_tmp`, `ce_tablet_8`, `ce_tablet_10`, `ce_fold`, `ce_tv_1080p`, `ce_tv_4k`) with fixed ports, software rendering by default (for mpv), muted media volume, and debug APK installation. [`tv-dpad.sh`](../../scripts/tv-dpad.sh) injects hardware-like D-pad presses via `uinput` so TV Compose text fields and focus behave like a real remote.

See [dev-environment.md](../dev-environment.md) for the full media table, AVD table, commands, and older-Android notes rather than duplicating them here.

## Where the code is

| Path | What |
|---|---|
| [`scripts/doctor.sh`](../../scripts/doctor.sh) | Read-only environment check (`build`, `site`, `server`, `devices`, `github`) |
| [`tools/test-server/setup.sh`](../../tools/test-server/setup.sh) | Starts/stops/wipes the Docker Jellyfin server, generates media, manages the `https` tunnel |
| [`tools/test-server/configure.py`](../../tools/test-server/configure.py), [`jf.py`](../../tools/test-server/jf.py), [`pgs.py`](../../tools/test-server/pgs.py) | API wizard/library/user setup and report; shared `creds.env`/API client; PGS subtitle generator |
| [`tools/test-server/user.py`](../../tools/test-server/user.py) | `list`, `add`, `rotate`, and `remove` test users (protects built-in setup users) |
| [`tools/test-server/forward.py`](../../tools/test-server/forward.py) | Per-emulator proxy with rate limiting, `--hold` / `--release-file`, `--log`, and clean half-close |
| [`tools/test-server/caddy-tls.sh`](../../tools/test-server/caddy-tls.sh) | Caddy reverse proxy with throwaway CA and client-cert test ports |
| [`scripts/avds.sh`](../../scripts/avds.sh), [`scripts/tablet-avds.sh`](../../scripts/tablet-avds.sh) | Create, start, stop, rotate, screenshot, and install on the 7 AVDs (`tablet-avds.sh` forwards to `avds.sh`) |
| [`scripts/tv-dpad.sh`](../../scripts/tv-dpad.sh) | Registers a `uinput` remote to send held D-pad events to Android TV emulators |
| [`scripts/make-track-test-series.sh`](../../scripts/make-track-test-series.sh) | Generates multi-track and styled-ASS/SRT test series ([track-test-series.md](../track-test-series.md)) |
| [`.claude/skills/test-server/SKILL.md`](../../.claude/skills/test-server/SKILL.md), [`.claude/skills/test-devices/SKILL.md`](../../.claude/skills/test-devices/SKILL.md) | Agent workflows and safety rules for the test server and emulators |
| [`docs/get-started.md`](../get-started.md), [`docs/dev-environment.md`](../dev-environment.md), [`docs/track-test-series.md`](../track-test-series.md) | Tool installation, full test-environment reference, and track-test-series guide |

## Rules

- **No Konsist architecture rules apply** because this area contains shell/Python tooling and Markdown docs rather than Kotlin modules.
- **Keep docs and map in sync:** changes to files listed under `test-environment` in [`docs/areas.yaml`](../areas.yaml) are checked by `check-docs.py` and `check-guides-follow.py`.
- **Operational rules (from [test-server](../../.claude/skills/test-server/SKILL.md) and [test-devices](../../.claude/skills/test-devices/SKILL.md) skills):**
  - Never print, commit, or paste passwords from `creds.env`; use `user.py rotate <name>` if exposed.
  - Never log in to the app as `admin`, and give custom API scripts their own `DeviceId`.
  - Use a separate user (`user.py add <name>`) when testing concurrently so watched/resume state does not collide; never wipe a shared server or replace an existing AVD (`create --force`, `delete`) without asking.
  - Close temporary public tunnels (`setup.sh https stop`) and `caddy-tls.sh stop` when finished, and stop only the emulators you started.
  - Always address emulators by `emulator-<port>` serial; never touch physical devices unless explicitly asked.
  - Keep software rendering (no `--gpu`) when testing mpv playback.

## How to test it

Checks that run anywhere (no Docker or emulators needed):

```bash
bash -n scripts/doctor.sh scripts/avds.sh scripts/tv-dpad.sh scripts/make-track-test-series.sh \
  tools/test-server/setup.sh tools/test-server/caddy-tls.sh
python3 -m py_compile tools/test-server/configure.py tools/test-server/jf.py \
  tools/test-server/pgs.py tools/test-server/user.py tools/test-server/forward.py
bash scripts/doctor.sh --help
bash scripts/doctor.sh build
python3 tools/test-server/forward.py --help
python3 .claude/skills/start-work/scripts/check-docs.py
```

On Windows, use `python` instead of `python3` if `python3` resolves to the Microsoft Store stub (see [get-started.md](../get-started.md)). Note that `tools/test-server/user.py` reads `creds.env` via `jf.py` on import, so `user.py --help` (and `user.py list`) requires an already-initialized `EPHYRA_TEST_SERVER_DIR` (inside WSL on Windows) rather than an empty machine.

**What cannot be tested without Docker, Android SDK images, or real hardware:**
- `tools/test-server/setup.sh`, `caddy-tls.sh`, `user.py` live API calls, and `scripts/doctor.sh server` require Docker + `python3` (inside WSL on Windows) and a running container.
- `scripts/avds.sh`, `scripts/tv-dpad.sh`, and `scripts/doctor.sh devices` require the Android SDK emulator, acceleration (WHPX/KVM), `adb`, and the API 34 system images.
- Hardware media decoders, HDR/Dolby Vision output, display refresh rates, audio passthrough, and real-device performance require a physical device ([dev-environment.md](../dev-environment.md#what-this-does-not-replace)).

## Before a PR

```bash
python3 .claude/skills/start-work/scripts/check-docs.py
python3 .claude/skills/start-work/scripts/check-guides-follow.py
```

- If you edited shell or Python scripts, run `bash -n` and `python3 -m py_compile` (plus `forward.py --help` / `doctor.sh` on the groups available on your machine).
- When adding a new generated media case to `setup.sh`, put it behind its own marker file (like `.generated-extra`), verify it with `ffprobe` and `setup.sh status`, and update the media table in [dev-environment.md](../dev-environment.md) (or test in an isolated server via `EPHYRA_TEST_SERVER_DIR` / `EPHYRA_TEST_SERVER_PORT` / `EPHYRA_TEST_SERVER_NAME`).
- If you changed any file listed in `docs/areas.yaml` for `test-environment`, keep this guide (or `docs/areas.yaml`) updated or explain why in the PR body with `Docs: no change needed — <reason>`.

## Decisions and history

- [#592](https://github.com/midasvo/ephyra/pull/592): initial throwaway Docker Jellyfin test server (`setup.sh`, `configure.py`, `user.py`, `forward.py`), AVD tooling (`avds.sh`, `tv-dpad.sh`), and the `test-server` / `test-devices` skills; expanded with track-choice, chapters, image-subtitle (`pgs.py`), large-library, and video/photo cases ([#580](https://github.com/midasvo/ephyra/issues/580), [#594](https://github.com/midasvo/ephyra/issues/594), [#597](https://github.com/midasvo/ephyra/issues/597), [#671](https://github.com/midasvo/ephyra/issues/671), [#672](https://github.com/midasvo/ephyra/issues/672)).
- [#302](https://github.com/midasvo/ephyra/pull/302), [#951](https://github.com/midasvo/ephyra/pull/951): tablet AVDs folded from `tablet-avds.sh` into `avds.sh`, adding `avds.sh up`, quiet boot flags (`-crash-report-mode never -no-metrics`, volume down via keyevents), and the older-API AVD matrix.
- [#928](https://github.com/midasvo/ephyra/issues/928), [#947](https://github.com/midasvo/ephyra/pull/947): `get-started.md` and `scripts/doctor.sh` added so new machines and agents can check prerequisites in one command.
- [#941](https://github.com/midasvo/ephyra/issues/941), [#991](https://github.com/midasvo/ephyra/pull/991): `forward.py` gained `--hold`, `--release-file`, `--hold-max`, `--log`, clean half-closing (`SHUT_WR`) so mpv does not hit `Connection reset by peer`, and Windows `SO_EXCLUSIVEADDRUSE` so two forwarders cannot bind the same port.
- [#960](https://github.com/midasvo/ephyra/issues/960), [#962](https://github.com/midasvo/ephyra/pull/962), [#993](https://github.com/midasvo/ephyra/issues/993), [#995](https://github.com/midasvo/ephyra/pull/995): `setup.sh https` (Cloudflare quick tunnel) for testing mpv over HTTPS and `caddy-tls.sh` for reverse-proxy TLS / mTLS client-certificate testing.
- [#1018](https://github.com/midasvo/ephyra/pull/1018): `user.py remove` deletes a user, its server data, and its `creds.env` entry while refusing to delete built-in setup users.

## Pitfalls

- **Windows path split (WSL vs Windows):** Docker and the test server scripts (`setup.sh`, `caddy-tls.sh`, `user.py`) run inside WSL where `~/ephyra-test-server` lives, while Gradle, `avds.sh`, `adb`, and `python` run on Windows. In Git Bash, `export MSYS_NO_PATHCONV=1` prevents `/sdcard/...` or `/data/...` rewriting in `adb shell` commands, and `python3` may hit the Microsoft Store alias unless `python` is used or the alias is disabled ([get-started.md](../get-started.md)).
- **WSL idle shutdown and IPv4 vs IPv6 port forwarding:** on Windows, WSL shuts down when idle, taking down containers and any `setup.sh https` tunnel (keep WSL awake with `wsl -d <distro> -- sleep 3600` during tunnel/custom-container tests). Also avoid Docker `--network host` in WSL because containers bind IPv6 only while emulators reach `10.0.2.2` over IPv4; if a Windows process holds `0.0.0.0:<port>`, IPv4 emulator traffic reaches that process instead of WSL ([dev-environment.md](../dev-environment.md), [test-server skill](../../.claude/skills/test-server/SKILL.md)).
- **TV emulator input and network limits:** TV text fields ignore `adb shell input keyevent` D-pad events and scramble full URLs passed to `adb shell input text` — use `scripts/tv-dpad.sh` for D-pad input and send URLs in short chunks. The TV image also has no `adb root` and uses Ethernet (unaffected by airplane mode or `adb emu network speed`), so simulate server loss with `forward.py` instead ([dev-environment.md](../dev-environment.md)).
- **Emulator rendering, boot hangs, and volume:** starting an AVD without `-crash-report-mode never -no-metrics` can hang before `adb devices` sees it, `cmd media_session volume` is a no-op on API 34 images (so `avds.sh` sends 16 volume-down keyevents), and host GPU rendering (`--gpu`) crashes mpv — keep `avds.sh`'s default software rendering for player work ([dev-environment.md](../dev-environment.md)).
- **Jellyfin test-data quirks:** Jellyfin only keeps resume positions for items of 5 minutes or more (and resets below 10%), `ffmpeg` drops Matroska language tags for `und` (so `Und Audio Show` uses `.mp4`) and drops HDR10 colour tags passed as output options rather than filter/codec params, and `Real Movies` / `Real Shows` need internet on the first scan to fetch posters ([dev-environment.md](../dev-environment.md), `setup.sh`).
