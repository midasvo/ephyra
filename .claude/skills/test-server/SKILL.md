---
name: test-server
description: >-
  Set up, start, repair, reset or extend the throwaway Jellyfin test server for Ephyra (Docker,
  generated edge-case media, test users). Use this whenever a task needs a Jellyfin server to test
  against, a test user, a media edge case (tracks, HDR, empty seasons, a broken file, resume
  positions), or when the user says "set up the development environment", "start the test
  server", "I need a server to test with" or "add a test user". Use it before testing against
  anyone's real server or the public demo server.
---

# Test server skill

`docs/dev-environment.md` is the reference: what is on the server, every command, the settings.
Read it first. This file is how to act on it.

## Steps

1. **Is it there already?** `tools/test-server/setup.sh status`. A server that answers is the one
   to use: do not make a second one, and do not wipe one you did not create in this session
   without asking, because other sessions may be testing on it.
2. **Check what the machine has.** `docker version` and `python3 --version`. On Windows both live
   in WSL: run every command of this skill as `wsl -d <distro> -- bash <script>`
   (`wsl -l -q` lists the distros). Docker missing is the user's to install: say so and stop.
3. **Start it.** `tools/test-server/setup.sh`. About two minutes on a first run once the image is
   there, seconds after that. Run it in the background if you have other work.
4. **Read the report it ends with** and compare it with the media table in the doc. "with a
   poster: 0" means the first scan had no internet: the `Real` libraries then have no artwork,
   and a rescan from the server's dashboard fixes it later.
5. **Tell the user** the two addresses (`http://localhost:8096`, and `http://10.0.2.2:8096` from an
   emulator), the user names, and where `creds.env` is. Not the passwords.

## Rules

- **Passwords stay in `creds.env`.** Never print one, never put one in a chat message, a commit, a
  PR or a log. To use one, read the single line you need inside the command that uses it
  (`sed -n 's/^JF_TESTER_PASS=//p' creds.env`), without echoing it. If one does end up in output,
  rotate it: `python3 tools/test-server/user.py rotate <name>`.
- **Your own user when others test too.** Watched state and resume positions are per user. With
  parallel agents or emulators: `python3 tools/test-server/user.py add <name>` for each.
- **Never log in to the app as `admin`,** and give a script of your own its own `DeviceId` in the
  `Authorization` header: a second login with a `DeviceId` ends the first one's session, which
  signs an emulator out in the middle of someone's test.
- **Server state is free to change:** marking items watched, resume positions, favourites. The
  media and the libraries are not: change those through `setup.sh`.
- **This is a test server.** It has no HTTPS and generated passwords. Do not expose its port
  outside the machine, and do not put real media on it.

## Adding an edge case

1. Add the file to `tools/test-server/setup.sh` in a block with its own marker file, the way
   `.generated-extra` does it, so existing servers get it on their next run.
2. Generate with the ffmpeg in the image (`/usr/lib/jellyfin-ffmpeg/ffmpeg`), a test pattern and a
   tone. Keep it short, but 5 minutes or more if a resume position matters.
3. Probe the result with `ffprobe` from the same image: tags that ffmpeg drops silently are the
   usual failure (a language tag in Matroska, colour tags given as output options).
4. Run `setup.sh`, check the item in the report, and add a row to the table in
   `docs/dev-environment.md`.

To try a change to the scripts without touching the running server, run a second one:

```bash
EPHYRA_TEST_SERVER_DIR=~/ephyra-test-server-check EPHYRA_TEST_SERVER_PORT=8097 \
EPHYRA_TEST_SERVER_NAME=ephyra-test-check tools/test-server/setup.sh
```

and remove it afterwards with the same settings and `setup.sh wipe --media`.

## When it goes wrong

| Symptom | Cause |
|---|---|
| `HTTP 503` for a while after start | The server is still starting; the scripts wait for it |
| `HTTP 401` in the middle of a script | Another login used the same `DeviceId` |
| An emulator cannot reach `10.0.2.2:8096` | The container is stopped, or on Windows WSL was shut down: run `setup.sh` |
| The app shows no Download button | The user is `nodownload`, which is what that user is for |
| A resume position does not stick | The item is under 5 minutes, or playback stopped under 10 % |
