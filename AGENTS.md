# AGENTS.md

Instructions for any coding agent working in this repository (Codex, Cursor, Copilot, Gemini CLI, Claude Code, …), and a fair start for people too. It is a map: what every task needs is here, the rest is one link away. Claude Code reads `CLAUDE.md`, which imports this file and adds what only Claude Code has (its skills by name, its notes).

The app is **Ephyra**, a fork of [Findroid](https://github.com/jarnedemeulemeester/findroid). It was called Findroid CE until #172. The repo is `midasvo/ephyra` (renamed from `midasvo/findroid-ce` in #174; GitHub redirects the old URL). The application ID, the Kotlin packages and internal names (`FindroidTheme`, `Theme.Findroid`) keep the old names on purpose. The repository and the models do not: since #774 they are `MediaRepository` (split into roles in #967), `Item`, `Movie`, `ItemSource`, `Library`, … (upstream calls them `JellyfinRepository` and `Findroid*`), and the architecture rule R11 allows no other `Findroid*` type.

## Where to start

- **Find the area before reading code.** `docs/areas.yaml` is the index of the app: per area the keywords, the files to open first, its docs, its rules and a fast test command. The same pages are the development site, https://midasvo.nl/ephyra/dev/. The steps (Claude Code has them as the `start-work` skill, `.claude/skills/start-work/SKILL.md`, which any agent can read as plain Markdown):
  1. Read `docs/areas.yaml` whole and match the task against each entry's keywords, title and summary. Take the area of the behaviour that is wrong first, and name a second one if the task touches it.
  2. If `docs/areas/<id>.md` exists, read its "Start here" block, then only the sections the task needs. Without a guide, open the entry's paths and docs, and say that the area has no guide.
  3. Check the map against the tree: `python3 .claude/skills/start-work/scripts/check-docs.py`. Where a doc and the code differ, the code is right.
  4. For a bug, read the guide's pitfalls, then search the issues: `gh issue list --repo midasvo/ephyra --search "<words>" --state all`. An open issue that matches is often the bug itself.
  5. At the end, say what the guide missed or got wrong, and fix it in the same pull request.
- **New machine:** `docs/get-started.md` (from an empty machine to the app on an emulator with test data) and `scripts/doctor.sh`, which checks the tools and says what is missing.
- **Tests:** `docs/testing.md` (the kinds of test, which one a change needs, what is checked by hand).
- **A feature users get** is an entry in `docs/features.yaml` (which apps have it). The user guide (https://midasvo.nl/ephyra/guide/, `devsite/src/content/docs/guide/`) and the README's radar are drawn from it; the release skill brings it up to date.
- **Modules:** `docs/architecture/modules.md` (generated from the build files: every module, what it is, what it depends on). **Rules R1–R22:** `docs/architecture/rules.md` (generated from the Konsist tests). **Ports and adapters:** `docs/architecture/README.md`, then `5-hexagonal-ddd.md` and the ADRs in `docs/architecture/adr/`.
- The numbered files in `docs/mpv-research/`, `docs/tablet/` and `docs/tv-research/` are research from before the work was done. Where they differ from the code, the code is right.

## Build Commands

```bash
# Debug APK (most common during development)
./gradlew :app:phone:assembleLibreDebug

# Release APK
./gradlew :app:phone:assembleLibreRelease

# TV debug APK
./gradlew :app:tv:assembleLibreDebug

# Compile only (faster feedback, no APK)
./gradlew :app:phone:compileLibreDebugKotlin
./gradlew :app:tv:compileLibreDebugKotlin
./gradlew :modes:film:compileDebugSources

# Full build (all modules)
./gradlew build

# Clean
./gradlew clean
```

```bash
# Formatting check (runs in CI)
./gradlew ktfmtCheck

# Format the tree
./gradlew ktfmtFormat

# Unit tests of every module and flavour (CI runs them too: run them yourself before opening a PR to save a round trip)
./gradlew testDebugUnitTest

# Fast check: the pure Kotlin modules and the architecture rules (seconds, no Android)
./gradlew :domain:test :application:test :architecture:test

# The docs against the tree (a second, no build; CI runs it on every pull request)
python3 .claude/skills/start-work/scripts/check-docs.py
```

On Windows without WSL, `python3` may be the Microsoft Store alias ("Python was not found"): use `python` or `py -3` instead, or turn off the `python3` app execution alias in Windows settings (`docs/get-started.md`, `scripts/doctor.sh`).

CI on a pull request runs `ktfmtCheck` (when `.kt` or `.kts` files or `gradle/libs.versions.toml` change — `ktfmtCheck` covers `.kts` build scripts, comments included) and, unless the change touches only docs-like paths (see `build.yaml`), the CE translation check, a debug build of every app variant and the unit tests (`testDebugUnitTest`, which uploads the test reports when it fails). Pushes to `main` run the same jobs; the Beta workflow does not wait for them. The build needs JDK 21.

`check-docs.py` checks that the paths, links, Kotlin names, rule numbers, Gradle tasks and test names in this file, the map, the guides, the skills and the model exist (its docstring has the details). A rename or a move that a doc names fails the `Docs` check until the doc follows. It does not check what a sentence claims: a green run means nothing named is gone, not that the text is right. A module, a module dependency or a rule changes the generated docs: `EPHYRA_WRITE_GENERATED=1 ./gradlew :architecture:test --rerun --tests '*GeneratedDocsTest*'`, and commit the result.

## Architecture

**Where code goes.** The apps (`app/phone`, `app/tv`) are the composition root and depend on the library modules (`app/tv` not on `player/cast`; the full list is in `modules.md`). ViewModels live in `modes/film`, `setup`, `settings`, `core`, `player/local` and `player/cast`, NOT in `app/phone` or `app/tv`; the screens in the app modules consume their state. New TV screens and components go in `app/tv/src/main/java/dev/jdtech/jellyfin/ce/`, next to upstream's screens instead of rewriting them, and `NavigationRoot.kt` points each route at the CE one (TV ADR 0001; the TV decisions are in `docs/tv-research/adr/`).

**MVI-style MVVM with Unidirectional Data Flow.** Each screen has:
- `FooState`: immutable data class holding all UI state
- `FooAction`: sealed interface of user intents
- `FooViewModel`: `@HiltViewModel`, exposes `StateFlow<FooState>`, handles `onAction()`
- `FooScreen`: stateful Composable that wires the ViewModel to the layout
- `FooScreenLayout`: stateless Composable that takes state + callbacks (used for previews)

Events that fire once (navigation, toasts) use a `Channel<FooEvent>` exposed as `Flow`, observed via `ObserveAsEvents()`.

**Navigation:** Navigation 3 with `@Serializable` route objects. `NavigationRoot.kt` in `app/phone` is the single source of truth for the nav graph.

**Dependency Injection:** Hilt throughout. DI modules are in `core/src/main/java/dev/jdtech/jellyfin/di/`, except the Jellyfin adapter's `JellyfinServerModule` in `server/jellyfin`, the download engine's modules in `downloads/.../di/`, the MDBList adapter's `MdbListModule` in `ratings/mdblist`, the device adapter's `DeviceModule` in `device/.../device/di/` (#965), and `data`'s own `DatabaseModule` (Room, the store ports, `DownloadedFiles`, `ProfileLock`) in `data/src/main/java/dev/jdtech/jellyfin/data/di/` (#899). The repository is bound in `core`'s `MediaRepositoryModule` (#966: it is in `application`, which has no Hilt).

**Ports and adapters** (ADR 0001 and ADR 0002 in `docs/architecture/adr/`; every rule in `docs/architecture/rules.md`):
- `domain` and `application` are pure Kotlin: they import only Kotlin, kotlinx, the JDK and their own code (R19). A port never names a library type; a contract that has to (the player's OkHttp client, `MediaHttpClient`) is a module of its own, `http` (#964). Only the apps depend on `data`, `server/jellyfin`, `ratings/mdblist` (the MDBList adapter of the external ratings, #331) and `device` (what asks Android about the device: the codec probe, connectivity, #965); the Jellyfin SDK is only in `server/jellyfin`. `data` is Room and nothing else: of Android it imports only Room, SQLite and `Context` (R20). A ViewModel takes the roles of the repository it uses (#967: `ItemCatalog`, `ItemLists`, `PlaybackMedia`, …, in `application/.../repository/`; `MediaRepositoryImpl`, also in `application` since #966, implements them all over the server's ports and the store roles, and no class depends on it) or a port, never an adapter class, `JellyfinApi` or `ServerDatabaseDao`. No port declares more than fifteen functions and properties (R21). No file of an adapter names a port another adapter implements (R22): what uses two adapters goes in `application`.
- New server-specific code goes behind a port in `domain/.../domain/ports/` (`MediaServer`: `session`; the catalog by role, `items`, `itemLists`, `itemExtras`, `info`; `userData`, `playback`; `ServerConnector` for setup), with our own types: the adapter maps SDK types, I/O failures are `ServerException`. New Room access goes behind a store port in `application` (state of one user keyed by `AccountKey(serverId, userId)`), implemented in `data`. A new adapter passes the contract suites in `testing`.
- Ids are value classes (`ItemId`, `UserId`, `ServerId`, `MediaSourceId` in `Ids.kt`; `toItemId()`, `asString()`), never `UUID` or a bare `String` outside the adapter (R10). They are `@Serializable` as their plain string, so routes and the Cast custom data hold the typed ids (#918).
- One rule, one place: user-data rules (played, favourite, resume position, last played, the sync's merge) are `PlayState` in `domain/.../userdata` (R14); a finished download is `DownloadState` (`ItemSource.downloadState`; only `DownloadFiles` names the `.download` suffix, R13); tick conversions are `Ticks` (R12).
- The server in use is one `ActiveServer` (type, address, token, user) on `ActiveServerHolder`, switched as a whole (R16); `CurrentMediaServer` picks the adapter by its type. What a server can do is `ServerCapabilities`, never a version check.
- Shared player code imports no engine package (R6). No type is named `Findroid*`, or `Jellyfin*` outside the adapter (R11).
- `./gradlew :architecture:test` (also run by `testDebugUnitTest`) enforces the rules. When one fails, fix the code; never add a baseline line to get past it (the files in `architecture/baseline/` are allowlists with a reason per entry).

**Glossary** ("session" alone is ambiguous: never use it for a new name):
- **server** `Server`/`ServerId` (its `ServerType`); **address** `ServerAddress`, where it answers; **user** `User`/`UserId`.
- **server session**: the app signed in to a server as a user (`ServerSession`, `ActiveServer`); **play session**: the server's job for one stream (`playSessionId`); **Cast session**: a Cast connection.
- **item** `Item`/`ItemId` (`Movie`, `Show`, `Season`, `Episode`, …); **library** `Library`.
- **version** in the UI, **source** in code: one file or transcode of an item (`ItemSource`, `MediaSourceId`); **stream**: an audio, video or subtitle part of a source (`MediaStream`; a track in the player).
- **download**: an item kept on the device, a `LOCAL` source with a `DownloadState` (there is no "offline mode", #281); **resume position** `PlayState.positionTicks`; **played** `played` ("watched" only in UI text).

**AppVariant:** an `AppVariant` enum (`PHONE`, `TV`) is provided by Hilt modules in `app/phone` and `app/tv`, so shared ViewModels (e.g. `SettingsViewModel`) tailor behaviour per variant instead of inspecting device hardware.

## Project facts

**Identity:** the visual identity (palette, type, shape, motion, glow rules) is specified in `docs/identity/VISION.md`. Quote its principles in reviews; don't invent colours outside its token tables.

**App ID:** `nl.midasvo.findroid.ce` (this is a fork — do not revert to the upstream `dev.jdtech.jellyfin` ID, and do not change it to match the Ephyra name: existing installs update over it).

**App name:** `app_name` in `core/src/{main,debug,staging}/res/values/strings.xml` (`Ephyra`, `Ephyra Debug`, `Ephyra Beta`). The Jellyfin client name follows the app label. Where an upstream string names the app (the welcome screen, the local network rationale), CE uses its own `ce_` key instead of editing upstream's translations. The launcher icons and the TV banner are generated by `images/branding/ephyra/make-ephyra.sh`.

**Version:** `EphyraVersion` in `buildSrc/src/main/kotlin/EphyraVersion.kt`: `NAME` (semver, from 2.0.0) with the literals `CODE` and `TV_CODE`, which must match it. Only a release PR changes them. Upstream's `Versions.APP_NAME` / `APP_CODE` are unused. Releases (`vX.Y.Z`) follow `.claude/skills/release/SKILL.md`; merging Findroid's changes follows `.claude/skills/upstream-sync/SKILL.md` and never releases anything.

**Build variants:** build types are `debug`, `release`, `staging`. The default flavor is `libre`: use its variants (e.g. `assembleLibreDebug`, `compileLibreDebugKotlin`) unless the change is about Cast. `libre` must stay free of Google code. Both phone flavors share one application ID and signing key (one installs over the other); the TV app has only `libre`. Channels: Google Play phone `proprietary`, Google Play TV `libre`, Amazon Appstore `libre` (Fire OS has no Play Services), F-Droid `libre`, GitHub releases and betas both (`ephyra-v<version>-libre-<abi>.apk`, `-proprietary-<abi>.apk`, `-libre-tv-<abi>.apk`).

**Cast (#626):** `app/phone` and `player/cast` have a second flavor, `proprietary`, with Google Cast (Play Services). Everything that touches the Cast SDK is in `player/cast/src/proprietary`; `player/cast/src/libre` has stubs that say Cast is not supported, and `player/cast/src/main` (interfaces, models, ViewModels) and the Cast UI in `app/phone/.../presentation/cast` compile in both. A change there must compile in both flavors (`compileLibreDebugKotlin` and `compileProprietaryDebugKotlin`). The Google Play phone bundle is `proprietary` (#641). The Cast surfaces (top bar button, device sheet, mini player, player) are stateless layouts fed by one `CastViewModel`; `CastHost` wraps the navigation display and is the only Cast code `NavigationRoot` knows. Emulators find no receivers: a debug build of the `proprietary` flavor has a stand-in (`adb shell setprop debug.ephyra.cast fake`, see `player/cast/src/debug/.../FakeCast.kt`) to look at every state. Anything about a real receiver still needs a real one.

**Player PRs:** anything under `player/local`, the player screens or the device profile (`JellyfinDeviceProfiles.kt`) runs `docs/mpv-checklist.md` on both backends before merge. It includes the display refresh-rate vote check: ExoPlayer must keep its default frame-rate strategy and mpv must hint the surface on every device, or phones play at their maximum refresh rate (#338). `player/local`'s `PlayerInvariantsTest` fails the build if either is undone.

## Commits and Pull Requests

- **Conventional commits**, on every commit: `type(scope): summary`, such as `fix(player): …` or `feat(profiles): …`. The release steps pick the next version from them: `feat` is a minor release, `type!:` or a `BREAKING CHANGE` footer a major one, anything else a patch. Use `feat` only for something a user gets.
- Branches are `feat/<issue>-<slug>`, `fix/<issue>-<slug>`, `refactor/<issue>-<slug>` or `docs/<slug>`. The PR title follows the same convention and names the issue. Merging a `refactor/` branch publishes no beta (`beta.yml`): use it only for a change no user can notice. For a tests-only or CI-only PR, put `[skip beta]` in the merge commit: `gh pr merge <pr> --merge --body "[skip beta]"`.
- Pull requests are merged with a merge commit, once the checks are green. `main` has no required checks, so `gh pr merge --auto` merges at once: wait for the checks yourself.
- **Review bots:** Sourcery (`sourcery-ai[bot]`, check `Sourcery review`) and CodeRabbit (`coderabbitai[bot]`) review every ready-for-review PR a couple of minutes after it opens, and again after a push (CodeRabbit does not review a draft pull request). Their checks pass whatever they find, so a green check doesn't mean the review is dealt with. Before merging, read each review (Sourcery's "Prompt for AI Agents" block in the review body lists every comment with its file and line) and answer each inline comment in its thread: fix it and say in which commit, or say why it doesn't apply (for example a setting that only exists on phones). Be critical: the bots are often wrong or theoretical, so check each finding against the code before acting on it, and push back when it doesn't hold. A reply that disagrees, with the reason, is a fine answer. Don't add code just to make a comment go away. Don't merge with a bot comment that has no reply. Reply with `gh api repos/midasvo/ephyra/pulls/<pr>/comments/<comment-id>/replies -f body='…'`. A finding that is real but outside the PR becomes an issue, linked in the reply.
- A push brings a new review: after the last push, wait until each active bot's check has finished (it stays pending while the bot reviews) and answer that review too. Right before merging, count the inline comments without a reply once more, as a step of its own and not in the same command as the merge: a comment can arrive late.
- A bot that is out of its review budget (Sourcery's budget is weekly, counted in characters of diff) or inactive for the week shows `skipping` or posts no review. When no bot reviewed a PR, don't merge on the green checks alone. A change of a few lines can be read by whoever merges it, with a comment on the PR saying so. Anything larger, and anything in the player, a Room migration or the setup flow, gets an independent review posted on the PR first: a reviewer that did not write the change compares old and new behaviour and posts its findings as inline comments, which are answered like a bot's.
- Don't edit `CHANGELOG.md` or the version in a feature PR. A release writes both.
- When a change moves or renames code that a doc, the map or a guide names, fix the doc in the same PR (`check-docs.py` says which).
- **The guides follow the code.** A PR that changes a file an area with a guide lists in `docs/areas.yaml` also updates that guide (or its map entry), or says why not in its description on a line of its own: `Docs: no change needed — <reason>`. The `Docs` workflow checks it (`.claude/skills/start-work/scripts/check-guides-follow.py`, which also runs locally against `origin/main` with `--body FILE`, `--body-text TEXT`, or `--pr NUMBER` to supply the PR description); the PR template has the line.

## Translations & Localization

All user-visible strings and plurals that CE adds must be translated into all 36 supported languages. `.claude/skills/translate-strings/SKILL.md` has the rules and the language list (Claude Code: the `translate-strings` skill); follow it for every string change.
- Upstream's translations come from Weblate into `values-<lang>/strings.xml`. Never edit those files. To change the text of an upstream string, add a CE key (`ce_<name>`) and use that instead.
- CE-only translations live in `<module>/src/main/res/values-<lang>/strings_ce.xml`, so upstream syncs don't conflict.
- `python3 .claude/skills/translate-strings/scripts/check-translations.py` verifies keys, placeholders and plural quantities. CI fails a PR with an incomplete CE string.
