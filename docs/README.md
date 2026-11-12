# Docs

What is in this folder, and whether it is still kept up to date.

## Reference

Kept current. Follow these when you work on the matching part of the app.

| Doc | For |
|---|---|
| [identity/VISION.md](identity/VISION.md) | The visual identity: palette, type, shape, motion and glow rules |
| [mpv-checklist.md](mpv-checklist.md) | The checks to run on both player backends for every player PR |
| [tablet/4-design.md](tablet/4-design.md) | How each phone screen behaves at tablet and wider widths |
| [tablet/6-test-matrix.md](tablet/6-test-matrix.md) | The five test sizes, the AVD script and the acceptance checklist |
| [tv-research/adr/](tv-research/adr) | The eight architecture decisions for the TV app |
| [performance.md](performance.md) | The baseline profile, the benchmark and what to record in a performance PR |
| [dev-environment.md](dev-environment.md) | The throwaway Jellyfin test server, its media and users, and the emulators |
| [track-test-series.md](track-test-series.md) | Generated test series for track selection and subtitle styling |
| [screenshots.md](screenshots.md) | How the README, website and Play listing screenshots are made |
| [play-store.md](play-store.md) | Signing, uploading and the listing on Google Play |
| [upstream-sync.md](upstream-sync.md) | Syncing with Findroid, releasing and publishing a beta |
| [open-decisions.md](open-decisions.md) | Product questions from the code audit that nobody has decided yet |

## Research

Written before the work was done and not updated since. Each folder's README says what came of
it. Where one differs from the code, the code is right.

| Folder | Question | Outcome |
|---|---|---|
| [tv-research/](tv-research/README.md) | How to turn `app/tv` into a first-class Android TV client | Built, #121 |
| [tablet/](tablet/README.md) | How to make the phone app a first-class tablet client | Built, in v2.2.0 |
| [mpv-research/](mpv-research/README.md) | How healthy the mpv backend is | Follow-up issues and the mpv checklist, #284 |

## Images

`player/` and `profiles/` hold the screenshots shown in the pull requests for the sleep timer,
the up next card and the profile PIN. No document here uses them. Those pull requests link to
the copy on their own branch, so the images there disappear when the branch is deleted.
