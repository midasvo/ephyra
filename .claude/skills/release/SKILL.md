---
name: release
description: >-
  Cut and publish an Ephyra release (vX.Y.Z, semver from conventional commits): pick the next
  version, write the changelog section, bump EphyraVersion.kt, tag and publish. Use this whenever
  the user says "release", "do a release", "cut/tag/publish a version", "ship it to stable",
  or asks only for part of it (just the changelog, just the version number, just the tag), so
  the conventions stay the same. Syncing with Findroid is a separate skill (upstream-sync).
---

# Release Playbook

Goal: turn what is on `main` into a stable release `vX.Y.Z`. A release never merges anything
from Findroid; that happens beforehand, in its own PR (see the `upstream-sync` skill).

## Background

- **Repo:** `origin` = `midasvo/ephyra`. The app is **Ephyra** (formerly Findroid CE).
- **Version:** `buildSrc/src/main/kotlin/EphyraVersion.kt` holds three literals that change
  together: `NAME`, `CODE` (`major * 1_000_000 + minor * 10_000 + patch * 100`) and `TV_CODE`
  (`CODE + 1`). The build fails when they disagree, and minor and patch must stay below 100.
  `CODE` is a literal because F-Droid's update check reads it with a regex. Upstream's
  `Versions.APP_NAME` / `APP_CODE` are not used; ignore them.
- **Tags:** releases are `vX.Y.Z` and trigger `.github/workflows/release.yml`, which builds the
  signed APKs (phone `libre` and `proprietary`, TV `libre`) and attaches them to the release. Betas are `v<next patch>-beta.<run>` and come
  from `beta.yml` on every push to `main`, except the merge of a release PR. Tags below `v2`
  (`v0.x`, `v1.x`) are Findroid's and `v1.2.0-ce.N` are the old Findroid CE releases; never reuse
  or move them.
- **Semver:** a breaking change (`type!:` or a `BREAKING CHANGE` footer) is major, a `feat` is
  minor, anything else is patch. For users "breaking" means something they have to act on: a
  setting reset, a dropped Android version, a lost download. `scripts/next-version.sh` applies
  these rules; the user has the last word.

## Procedure

### 1. Start from the real main

```bash
git fetch origin
git switch --detach origin/main
gh run list --repo midasvo/ephyra --branch main --limit 3   # main must be green
```

Work from `origin/main`, not the local `main` branch: in a worktree `git switch main` fails,
because `main` is checked out in the main checkout.

### 2. Pick the version

```bash
next=$(.claude/skills/release/scripts/next-version.sh)   # prints the reason on stderr
last=$(git tag -l 'v*' --sort=-v:refname | grep -E '^v([2-9]|[1-9][0-9]+)\.[0-9]+\.[0-9]+$' | head -1)
git log --no-merges --format='- %s' "${last:-v1.2.0-ce.6}"..HEAD
IFS=. read -r major minor patch <<< "$next"
code=$((major * 1000000 + minor * 10000 + patch * 100))
```

Show the user the proposed version, the reason and the commits, and ask them to confirm. The
script can't see what a commit means for users: a `fix` that resets a setting is still
breaking, and a `feat` that only touches CI is a patch.

### 3. Release PR: version, changelog and listings

On a branch `release/v$next` (`git switch -c "release/v$next"`):

- Set `NAME = "$next"`, `CODE = $code` and `TV_CODE = $((code + 1))` in `EphyraVersion.kt`.
- In `CHANGELOG.md`, turn `## [Unreleased]` into `## [v$next] — <YYYY-MM-DD>`, or add that
  section at the top if there is no Unreleased one. Keep what was already written there and
  add the rest from the commits:
  - Open with a short paragraph in plain words: what this release means for someone using the
    app. Then **Added / Changed / Fixed**, each line ending with its issue number, e.g. `(#142)`.
  - Changes merged from Findroid go under **From Findroid** (find them with
    `git log --merges --grep='upstream' "$last"..HEAD` and the sync PRs).
  - Dependency bumps and translation updates go under **Maintenance**, collapsed to a line each.
  - Leave out what users never see (CI, skills, docs), unless it changes how they install or
    update.
- Add `[v$next]: https://github.com/midasvo/ephyra/releases/tag/v$next` at the top of the
  link list at the bottom, or the heading renders as plain bracketed text.
- Write the store changelog to `fastlane/metadata/android/en-US/changelogs/$code.txt` and the
  same text to `$((code + 1)).txt` (the TV build). F-Droid shows it as "what's new" and reads it
  from the tagged commit, so it can't be added after the tag. Plain text, at most 500
  characters: one sentence on what the release brings, then a few `- ` lines for the changes
  users will notice.
- Bring the feature lists up to date with what this release adds: "What Ephyra adds to
  Findroid", "Tablet" and "Android TV" in `README.md`, and the same sections in
  `site/index.html`. Only list what a user would pick the app for; fixes stay in the changelog.

```bash
git add buildSrc/src/main/kotlin/EphyraVersion.kt CHANGELOG.md fastlane/metadata/android/en-US/changelogs README.md site
git commit -m "chore(release): v$next"
git push -u origin HEAD
gh pr create --repo midasvo/ephyra --title "chore(release): v$next" --body "<the changelog section>"
```

Merge it once `gh pr checks` is fully green, with `--merge` (the repo uses merge commits; `--auto`
merges right away because `main` has no required checks).

### 4. Tag and publish

Tag the merge commit on `main`, then create the release with the changelog section as its
notes **right away**: `release.yml` appends the APK guide to those notes and attaches the APKs
when its build finishes, a few minutes later.

`--generate-notes` adds GitHub's "What's Changed" list of PRs and new contributors below the
changelog. Always pass `--notes-start-tag "$last"` with it (`$last` from step 2, the previous
release): on its own GitHub compares with Findroid's `v1.0.2`, inherited with the fork, and lists
every PR since the fork.

```bash
git fetch origin
git log -1 --format=%s origin/main   # must be the merge of the release PR
git tag -a "v$next" -m "Ephyra v$next" origin/main
git push origin "v$next"
gh release create "v$next" --repo midasvo/ephyra --verify-tag \
  --title "Ephyra v$next" --notes-file <file with the CHANGELOG.md section> \
  --generate-notes --notes-start-tag "$last"
gh run watch --repo midasvo/ephyra $(gh run list --repo midasvo/ephyra --workflow release.yml --limit 1 --json databaseId --jq '.[0].databaseId')
```

Check that the release has twelve APKs, four ABIs each of `ephyra-v$next-libre-<abi>.apk`,
`ephyra-v$next-proprietary-<abi>.apk` and `ephyra-v$next-libre-tv-<abi>.apk`. If the workflow failed for a flaky reason, rerun it with
`gh run rerun <run id> --failed`. If the code has to change, fix it on `main` and release the
next patch version; the tag stays where it is.

### 5. F-Droid

Once the APKs are on the release, point the F-Droid inclusion merge request at it with the
`fdroid-update` skill (`python3 .claude/skills/fdroid-update/scripts/update-mr.py`). Its
pipeline takes a while; don't wait for it, report its link.

### 6. Report

Tell the user the version, a link to the release, the APK count, the state of the F-Droid merge
request, and anything that needs a follow-up (the Play upload in `docs/play-store.md`, a known
issue).

## Hard rules

- Never reuse or move a tag that has been pushed. A broken release gets a new patch version.
- Never tag from a branch other than `main`, and never tag a commit whose CI isn't green.
- The version only changes in a release PR, never in a feature PR.
- Never change the application ID `nl.midasvo.findroid.ce`; every release must update over the
  previous one, so the versionCode only goes up.
