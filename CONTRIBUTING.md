# Contributing to Ephyra

Bug reports and pull requests are welcome. For a larger change, open an issue first so we can
agree on the direction before you build it.

## Building

You need JDK 21 and the Android SDK. There is one flavour, `libre`.

```bash
./gradlew :app:phone:assembleLibreDebug   # phone and tablet
./gradlew :app:tv:assembleLibreDebug      # Android TV
```

The debug build installs next to the release as **Ephyra Debug**, with its own data.

## Something to test on

No Jellyfin server or Android device at hand? [docs/dev-environment.md](docs/dev-environment.md)
sets up a throwaway server in Docker, with generated media for the awkward cases and test users,
and emulators for phone, tablet and Android TV:

```bash
tools/test-server/setup.sh       # Jellyfin at http://localhost:8096
scripts/avds.sh create ce_phone  # then: start ce_phone, install ce_phone
```

## Before you open a pull request

```bash
./gradlew ktfmtFormat          # CI fails on unformatted code
./gradlew testDebugUnitTest    # CI does not run the tests, so run them yourself
```

- **Commit messages** follow [Conventional Commits](https://www.conventionalcommits.org/):
  `fix(player): …`, `feat(downloads): …`. The next version number is picked from them, so use
  `feat` only for something a user gets.
- **Strings.** Never edit `values-<lang>/strings.xml`: those are Findroid's and come from its
  Weblate. A string Ephyra adds goes in the module's `values/strings.xml`, with a translation in
  every `values-<lang>/strings_ce.xml` (36 languages). CI checks this with
  `.claude/skills/translate-strings/scripts/check-translations.py`. Machine-assisted
  translations are fine; a fix to one is a welcome pull request by itself.
- **Player changes** go through [docs/mpv-checklist.md](docs/mpv-checklist.md), on ExoPlayer and
  on mpv.
- **Anything visual** follows [docs/identity/VISION.md](docs/identity/VISION.md).
- Leave `CHANGELOG.md` and the version alone; both are written at release time.

## Finding your way

- [CLAUDE.md](CLAUDE.md) describes the modules and the architecture. It is written for Claude
  Code, which does much of the work here, and reads just as well for people.
- [docs/](docs/README.md) lists the design documents and which of them are still current.
- Ephyra is a fork of [Findroid](https://github.com/jarnedemeulemeester/findroid), and Findroid's
  changes are merged in regularly. Code Ephyra adds is kept in its own files where it can be, so
  those merges stay small. A fix in code that Findroid still owns is best sent to Findroid too.
