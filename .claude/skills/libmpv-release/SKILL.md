---
name: libmpv-release
description: >-
  Release a new libmpv from Ephyra's own fork (midasvo/libmpv-android, published as
  `io.github.midasvo:libmpv` on Maven Central) and bump Ephyra to it. Use this whenever the user
  mentions releasing, publishing or tagging libmpv, a new mpv, FFmpeg, libplacebo or dav1d build,
  bumping libmpv in Ephyra, or a fix that is "already on libmpv-android main but not released".
  Releasing Ephyra itself is the `release` skill; merging Findroid is `upstream-sync`.
---

# libmpv Release Playbook

Goal: get a libmpv build with the mpv and FFmpeg we want onto Maven Central, then into Ephyra
through a normal PR that has been tested on devices. A libmpv update is our decision, not
upstream's: we no longer wait for `dev.jdtech.mpv` releases (#654).

## Background

- **Fork:** `midasvo/libmpv-android`, local checkout `C:\Users\midas\Projects\libmpv-android`.
  **Upstream:** `jarnedemeulemeester/libmpv-android`. The fork's `RELEASING.md` is the source of
  truth for the fork side; this skill only summarises it.
- **Artifact:** `io.github.midasvo:libmpv`. The Kotlin package is still `dev.jdtech.mpv`, so
  imports never change.
- **Versions:** our own semver, from `1.1.0`. Minor for dependency bumps, patch for build or wrapper
  fixes, major for a breaking wrapper API change. Each GitHub release lists the component versions.
- **Upstream changes** reach the fork through `sync-upstream.yaml` (every Monday) as a PR named
  "Merge upstream main (…)". Merge it with a merge commit. Renovate also runs on the fork and
  opens its own bump PRs; they build all four ABIs, so treat an mpv, FFmpeg or libplacebo bump
  as a release candidate.
- **In Ephyra:** `libmpv` in `gradle/libs.versions.toml`. Renovate opens the bump but never
  auto-merges it.

## Procedure

### 1. Decide what goes in
```bash
gh pr list --repo midasvo/libmpv-android            # a pending upstream sync?
gh api repos/midasvo/libmpv-android/contents/buildscripts/include/depinfo.sh --jq .content | base64 -d | grep '^v_'
gh release list --repo midasvo/libmpv-android --limit 3
```
Merge the upstream-sync PR if it's green. If we need a version before upstream has it, bump it in
`depinfo.sh` in a PR on the fork. Upstream's identical bump will merge cleanly later.

### 2. Read what changed for the player
Read the mpv, FFmpeg and libplacebo release notes between the old and the new versions. Look for
renamed or removed options, properties and commands that `MPVPlayer.kt` uses, new defaults, and log
prefix changes. `MpvStreamBreak.isStreamError` and `MpvErrorRecovery.isConnectionError` match log
prefixes. `PlayerInvariantsTest` pins the options that matter (`network-timeout`, `curl-enabled`).

### 3. Tag the fork
```bash
cd C:/Users/midas/Projects/libmpv-android && git switch main && git pull
git tag -a v1.2.0 -m "libmpv 1.2.0" && git push origin v1.2.0
gh run watch --repo midasvo/libmpv-android $(gh run list --repo midasvo/libmpv-android --workflow publish.yaml --limit 1 --json databaseId --jq '.[0].databaseId')
```
The build takes about 25 minutes. Then check that the deployment is on Maven Central (it takes up
to about 30 minutes):
```bash
curl -sfI https://repo1.maven.org/maven2/io/github/midasvo/libmpv/1.2.0/libmpv-1.2.0.aar | head -1
```
Fill in the draft GitHub release ("What changed for players") and publish it.

### 4. Bump Ephyra
Make a branch, set `libmpv = "1.2.0"`, and apply what step 2 found. Then run:
```bash
./gradlew ktfmtCheck :player:local:testDebugUnitTest testDebugUnitTest :app:phone:assembleLibreDebug :app:tv:assembleDebug
```
Add a line under `### Maintenance` in `## [Unreleased]` in `CHANGELOG.md` ("libmpv 1.2.0: mpv …,
FFmpeg …"), or under `### Fixed` if it fixes something users see.

### 5. Test on devices
Run the whole of `docs/mpv-checklist.md` on a phone and on TV, not just the "Every PR" part: on an
emulator, on a real phone, and on the Xiaomi box (Dolby Vision, 32-bit `armeabi-v7a`). Cut the
network during playback once. The user does the device runs: say exactly what still needs doing.

### 6. Report
Report the fork release URL, the Ephyra PR, what was tested where, and what is still open.

## Hard rules

- Never publish a version twice or try to overwrite one. Maven Central is immutable: fix forward
  with the next patch version.
- Never rename the `dev.jdtech.mpv` package or change the fork more than `RELEASING.md` lists.
  Every extra line is a merge conflict with upstream.
- Never let a libmpv bump auto-merge in Ephyra, and never merge one without the device checklist.
- Keep `tls-verify=no` until the build ships a CA bundle, and `curl-enabled=no` until the stall
  handling has been tested against curl (#654).
- The signing key and Portal token live only in the fork's `release` environment and in the
  maintainer's offline backup. Never print, copy or regenerate them in a session.
