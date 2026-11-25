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
Read it first; `docs/get-started.md` has what to install. This file is how to act on it.

## Steps

1. **Is it there already?** `tools/test-server/setup.sh status`. A server that answers is the one
   to use: do not make a second one, and do not wipe one you did not create in this session
   without asking, because other sessions may be testing on it.
2. **Check what the machine has.** `scripts/doctor.sh server` (from Git Bash on Windows). It
   checks Docker and `python3` where Docker runs and whether a server answers. On Windows every
   command of this skill runs in WSL: `wsl -d <distro> -- bash tools/test-server/setup.sh …` for
   the shell script, `wsl -d <distro> -- python3 tools/test-server/user.py …` for the Python
   ones (`wsl -l -q` lists the distros). Docker missing is the user's to install: say so and stop.
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
  parallel agents or emulators: `python3 tools/test-server/user.py add <name>` for each, and
  `python3 tools/test-server/user.py remove <name>` when done (deletes the user, its server data,
  and its `creds.env` entry; refuses built-in setup users).
- **Never log in to the app as `admin`,** and give a script of your own its own `DeviceId` in the
  `Authorization` header: a second login with a `DeviceId` ends the first one's session, which
  signs an emulator out in the middle of someone's test.
- **Server state is free to change:** marking items watched, resume positions, favourites. The
  media and the libraries are not: change those through `setup.sh`.
- **This is a test server.** It has generated passwords and one user without a password. Do not
  expose its port outside the machine, and do not put real media on it.
- **https only for as long as a test needs it.** `tools/test-server/setup.sh https` prints a
  public https address (a Cloudflare quick tunnel); `setup.sh https stop` closes it. Use it when
  a change touches how a player loads a stream, and for the release smoke test. Close it in the
  same session, and say so in your report. On Windows keep WSL awake while it runs
  (`wsl -d <distro> -- sleep 3600` in the background), or the tunnel dies mid-test.
- **On Windows, WSL shuts down when nothing runs in it,** and that stops every container: the
  server comes back with its restart policy, a container of your own (a proxy, a second server)
  stays stopped. For any test with a container of your own, keep a `wsl -d <distro> -- sleep 3600`
  of your own running in the background and stop it afterwards; never kill another session's.
- **Docker's host network in WSL is not reachable from Windows for IPv4:** a container with
  `--network host` that listens on all interfaces gets an IPv6 socket, which WSL forwards only to
  `[::1]`, so an emulator (`10.0.2.2`, IPv4) is refused. Publish ports instead (`-p
  127.0.0.1:<port>:<port>`) and reach the server from the container as
  `host.docker.internal` (`--add-host host.docker.internal:host-gateway`).
- **A reverse proxy with TLS:** `tools/test-server/caddy-tls.sh` puts Caddy with a throwaway CA
  in front of the server, with sites that ask for a client certificate, require one, or do not
  (#960, #993); `tools/test-server/caddy-tls.sh stop` removes it. The emulator needs the CA as a user CA
  (`docs/dev-environment.md`, "Behind a reverse proxy with TLS"). Stop it in the same session.

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
| The https address answers `530` | The tunnel is gone (on Windows: WSL went idle). Run `setup.sh https` again; the address is new |
| The app shows no Download button | The user is `nodownload`, which is what that user is for |
| A resume position does not stick | The item is under 5 minutes, or playback stopped under 10 % |
