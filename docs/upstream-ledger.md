# Upstream ledger

Ephyra is a fork of [Findroid](https://github.com/jarnedemeulemeester/findroid) and stays
**0 commits behind** it. This page says what that means and what Ephyra did with each of
Findroid's commits.

## What "0 behind" means

Every Findroid commit is in Ephyra's history. It does not mean Ephyra has Findroid's code.

- **Translations** come in as they are. The strings Ephyra shares with Findroid are translated
  on Findroid's Weblate, and a sync merges those files.
- **Code** does not come in by merging. Ephyra has changed most of Findroid's Kotlin files and
  has moved the code into a different module structure
  ([docs/architecture](architecture/README.md)), so a Findroid change no longer fits as text.
  Each one is read and gets a verdict below. One that is worth having is built again in Ephyra's
  structure, in a pull request of its own.

## Verdicts

| Verdict | Meaning |
|---|---|
| taken | Ephyra has the change as Findroid made it |
| re-implement | Worth having, but it cannot come in as it is; the linked issue will build it in Ephyra's form |
| covered | Ephyra already has the same thing in its own form |
| re-implemented | Built in Ephyra's form; the linked pull request did it |
| skipped | Not taken, with the reason |

Translation commits are not listed: they are always taken.

## Commits

Newest sync first. The ledger starts with the sync of 2026-11-22; what earlier syncs brought is
in the [changelog](../CHANGELOG.md) under **From Findroid**.

### Sync to `cc3423a07` (2026-11-22)

| Commit | Date | Kind | What it does | Verdict | Where |
|---|---|---|---|---|---|
| [`cc3423a07`](https://github.com/jarnedemeulemeester/findroid/commit/cc3423a07) | 2026-11-22 | refactoring | Moves the search bar's state (`SearchBarState`) up to `NavigationRoot` | skipped | Material 3 1.5 does not deprecate the search bar API Ephyra uses, and Ephyra's search bar has fixes of its own (#387, #430, #439). Rebuilding it on `SearchBarState` gains nothing a user sees |
| [`a5e5d1725`](https://github.com/jarnedemeulemeester/findroid/commit/a5e5d1725) | 2026-11-22 | dependency | Compose 1.13.0, Material3 1.5.0 and compile SDK 37.1, with the bottom sheets, the password field and the search bar moved to the new APIs | re-implemented | #857 (issue #843): the versions and compile SDK 37.1, and the three places that no longer compiled. The bottom sheet, password field and search bar APIs upstream moved away from are not deprecated and stay |

### Sync to `c7a8b0b20` (2026-11-22, #831)

| Commit | Date | Kind | What it does | Verdict | Where |
|---|---|---|---|---|---|
| [`ba318d178`](https://github.com/jarnedemeulemeester/findroid/commit/ba318d178) | 2026-11-21 | refactoring | Reformats 115 Kotlin files with trailing commas (a newer ktfmt) | skipped | Ephyra formats with ktfmt 0.62. Moving to Findroid's formatter reformats the whole tree and is its own change |
| [`c83c0d964`](https://github.com/jarnedemeulemeester/findroid/commit/c83c0d964) | 2026-11-21 | dependency | Ruby 4.0.10 | taken | |
| [`2258aa9f0`](https://github.com/jarnedemeulemeester/findroid/commit/2258aa9f0) | 2026-11-21 | dependency | ktfmt Gradle plugin 0.28.0 | skipped | 0.28.0 cannot run the 0.62 formatter. Goes with the reformat above |
| [`17ae155b1`](https://github.com/jarnedemeulemeester/findroid/commit/17ae155b1) | 2026-11-21 | dependency | libmpv 1.1.0 | covered | Ephyra builds its own `io.github.midasvo:libmpv`, at 1.4.0 |
| [`bf4236a3c`](https://github.com/jarnedemeulemeester/findroid/commit/bf4236a3c) | 2026-11-20 | dependency | Android Gradle Plugin 9.5.2, lifecycle 2.11.1, navigation3 1.2.2, Gradle 9.9.2 | taken | |
| [`951eb51af`](https://github.com/jarnedemeulemeester/findroid/commit/951eb51af) | 2026-11-16 | dependency | Gradle 9.9.1 | taken | Superseded by 9.9.2 |
| [`3a06420d5`](https://github.com/jarnedemeulemeester/findroid/commit/3a06420d5) | 2026-11-15 | dependency | kotlinx-serialization 1.13.0, KSP 2.3.14 | covered | Ephyra's own Renovate had both already |
| [`c4567ad43`](https://github.com/jarnedemeulemeester/findroid/commit/c4567ad43) | 2026-11-15 | dependency | navigation3 1.2.1 | taken | Superseded by 1.2.2 |
| [`384c6c026`](https://github.com/jarnedemeulemeester/findroid/commit/384c6c026) | 2026-11-15 | dependency | Android Gradle Plugin 9.5.1 | taken | Superseded by 9.5.2 |
