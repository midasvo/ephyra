---
name: test-devices
description: >-
  Create, start and drive the Android emulators Ephyra is tested on (phone, tablets, foldable,
  Android TV), install a build on one and log it in to the test server. Use this whenever a change
  has to be seen or tested in the running app, when the user asks for an emulator, a TV or tablet
  to test on, or says "set up the development environment". Also use it before sending any key or
  tap to an emulator: it has the pitfalls that produced false bug reports before.
---

# Test devices skill

`docs/dev-environment.md` is the reference: the AVDs, their serials, the commands. Read it first.
The server the app logs in to comes from the `test-server` skill.

## Steps

1. **Look first.** `scripts/avds.sh list` and `adb devices`. An emulator that is running may be
   in use by another session: ask the user before you install on it, send keys to it or stop it.
   Never touch a physical device (a serial that is not `emulator-…`) unless the user named it for
   this task.
2. **Images.** `scripts/avds.sh images`. Installing a missing image means accepting Google's
   licence, so that is the user's step: give them the command and wait.
3. **Create and start.** `scripts/avds.sh create <name>`, then `scripts/avds.sh start <name>`
   in the background (it returns when Android has booted). Pass
   `EMULATOR_FLAGS="-crash-report-mode never -no-metrics"`, or a new emulator can hang on a dialog
   nobody sees.
4. **Build and install.** `./gradlew :app:phone:assembleLibreDebug` (or `:app:tv:`), then
   `scripts/avds.sh install <name>`. Never uninstall to "start clean" on an AVD that is logged in.
5. **Log in.** Server `http://10.0.2.2:8096`, a test user from the `test-server` skill. Typing a
   generated test password into the app on an emulator is fine; typing the user's own password
   for a real server is not: they log in there themselves.
6. **Stop what you started** when the task is done: `scripts/avds.sh stop <name>`. Leave the rest.

## Sending input

- **Always address one device:** `adb -s emulator-<port> …`. Without `-s`, a second device
  attached later breaks the command, or worse, receives it.
- **Look, then act.** `adb shell uiautomator dump` (or a screenshot) before every tap or key,
  and again after. A key sent to the wrong screen starts playback, switches a setting or types
  into the wrong field.
- **TV: use `scripts/tv-dpad.sh`,** not `input keyevent`, for anything near a text field, and
  whenever a focus bug is the thing under test. A focus problem seen only with `input keyevent`
  is not a finding.
- **Git Bash:** `export MSYS_NO_PATHCONV=1`, or `/sdcard/…` and `/data/…` arguments get rewritten.
- **Screenshots:** `scripts/avds.sh shot <name> <file.png>`, then look at the image. A screenshot
  you did not look at is not evidence.

## Rules

- **mpv needs software rendering,** which `start` uses by default. Do not add `--gpu` for player
  work.
- **A real device decides** anything about decoders, HDR or Dolby Vision output, refresh rate and
  performance. Say "not verified on a device" in the PR when the emulator is all you had.
- **Player changes** still go through `docs/mpv-checklist.md` on both backends.
- **Do not replace an AVD** (`create --force`, `delete`) that you did not create in this session
  without asking: it holds a login, downloads and sometimes hours of someone's test state.

## Useful states

| To get | Do |
|---|---|
| The server unreachable, internet up | `adb root`, then the `iptables` rule in the doc (phone and tablet images only) |
| The same on a TV image | `tools/test-server/forward.py` and the app logged in to `http://10.0.2.2:8097`; kill the script to drop the server (see the doc) |
| No network at all | `adb shell cmd connectivity airplane-mode enable` (phone and tablet images only: the TV image is on Ethernet, and `adb emu network speed` does nothing there either) |
| Portrait or landscape | `scripts/avds.sh portrait|landscape <name>` |
| The app's database or preferences | `adb exec-out run-as nl.midasvo.findroid.ce.debug cat <path>` (debug build only) |
| A crash, after the fact | `adb logcat -b crash -d` |
