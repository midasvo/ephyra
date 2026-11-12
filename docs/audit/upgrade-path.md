# Audit: upgrade path

Sub-issue #358 of the quality audit (#361). Report only: nothing in the code was changed. The
checklist rows are UPG-01 to UPG-16 in `checklist.md`.

**Run on:** AVD `ce_phone_tmp` (Pixel 6, Android 14), the release APKs from GitHub (x86_64), the
Jellyfin demo server (`https://demo.jellyfin.org/stable`, user `demo`), ExoPlayer only. The demo account
is shared with other testers, so favourites and resume positions on the server could change under the
run; the offline checks used local positions of downloaded episodes, which the server cannot change.

## Summary

- **Every update to 2.4.0 kept everything that was checked:** the login, every setting the user had
  changed, the downloads (listed and playing offline), the offline resume position of a downloaded
  episode and the favourite. This holds for the straight jump from 2.0.0 and for each step
  2.0.0 → 2.1.0 → 2.2.0 → 2.3.0 → 2.4.0, and Findroid CE 1.2.0-ce.6 installs over it with the login
  kept. No crash, no Room or migration error in the logs.
- **Two things change for a user who changed nothing:** dynamic colours go from on to off at 2.2.0 (the
  default changed), and the Favorites Home row disappears at 2.1.0 (the row was replaced by the
  Favorites tab). Both are on purpose; only the first is in the release notes.
- **A downgrade across a Room schema version loses the servers, the login and the list of downloads.**
  The downloaded files stay on disk and are never cleaned up (#379). Settings survive. Going from 2.4.0
  back to 2.3.0 (same schema) keeps everything. Android refuses a downgrade unless the user uninstalls
  first or forces it with `adb install -r -d`.
- **There are no migration tests** (#380). If an automatic migration were ever wrong, the destructive
  fallback would wipe the database on update with nothing in CI to catch it.

## APKs used

All from the GitHub releases. Package `nl.midasvo.findroid.ce`, the same V2 signer for all six
(`CN=Unknown, OU=Unknown, O=Unknown, L=Nijmegen, ST=Gelderland, C=NL`), certificate SHA-256
`e2d940cbed02a4719a2dc0e54735c4565a7b7b4549b75a820bd723546fd498f3`.

| Release | Asset | versionCode | Room schema |
|---|---|---|---|
| v1.2.0-ce.6 | `findroid-ce-v1.2.0-libre-x86_64.apk` | 34 | 14 |
| v2.0.0 | `ephyra-v2.0.0-libre-x86_64.apk` | 2000000 | 14 |
| v2.1.0 (no 2.1.1 release) | `ephyra-v2.1.0-libre-x86_64.apk` | 2010000 | 14 |
| v2.2.0 (last 2.2.x) | `ephyra-v2.2.0-libre-x86_64.apk` | 2020000 | 16 |
| v2.3.0 | `ephyra-v2.3.0-libre-x86_64.apk` | 2030000 | 18 |
| v2.4.0 | `ephyra-v2.4.0-libre-x86_64.apk` | 2040000 | 18 |

## What survived, by path

Set up on the older version: login; non-default settings (theme Dark, Max days in Next Up 45, Display
Extra Info on, cast and crew off, Home rows reordered with Latest Shows off and Favorites on, Direct
play Dolby Vision off, volume and brightness gestures off, seek back 12000 ms, auto skip on for intros,
still watching 5 episodes and 120 min, subtitles yellow with drop shadow at 130 %, downloads over mobile
data on, 3 concurrent downloads); Pioneer One as a favourite; two downloaded episodes (S1E3, S1E4, in
Original quality); S1E3 played offline to a known position. The update was `adb install -r`, then the
app was started offline first (downloads and positions), then online (login and settings).

`works` = seen kept after the update; `broken` = seen lost; `not tested` = not checked on that path.

| From → to | Login | Interface | Home rows | Player | Media segments | Still watching | Subtitle style | Download settings | Downloads listed | Downloads play offline | Offline resume position | Favourites | No crash |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2.0.0 → 2.4.0 | works | works (1) | works (2) | works | works | works | works | works | works | works | works | works | works |
| 2.0.0 → 2.1.0 | works | works | works (2) | works | works (3) | works | works | works | works | works | works | not tested (4) | works |
| 2.1.0 → 2.2.0 | works | works (1) | works | works | works | works | works | works | works | works | works | not tested (4) | works (5) |
| 2.2.0 → 2.3.0 | works | works | works | works | works | works | works | works | works | works | works | not tested (4) | works |
| 2.3.0 → 2.4.0 | works | works | works | works | works | works | works | works | works | works | works | works | works |
| 1.2.0-ce.6 → 2.4.0 | works | works (theme only) | not tested | not tested | not tested | not tested | not tested | not tested | not tested | not tested | not tested | not tested | works |
| 2.4.0 → 2.3.0 (downgrade) | works | works | not tested | not tested | not tested | not tested | not tested | not tested | works | not tested | works | not tested | works |
| 2.3.0 → 2.2.0 (downgrade) | broken | works | not tested | not tested | not tested | not tested | not tested | not tested | broken | broken (not listed) | broken | not tested | works |
| 2.4.0 → 2.1.0 (downgrade) | broken | not tested | not tested | not tested | not tested | not tested | not tested | not tested | not tested | not tested | not tested | not tested | works |

1. Dynamic colours go from on to off on the step into 2.2.0 for a user who never changed the setting.
   Every value the user set is kept.
2. The order and Latest Shows off are kept. The Favorites row switched on in 2.0.0 is gone from 2.1.0 on.
3. 2.1.0 still shows the old "Auto skip" switch and "Segment type to auto skip = Intro", kept. The
   per-type actions appear in 2.2.0 as Intros = Skip automatically, Outros = Ask to skip.
4. Checked only at the start (2.0.0) and the end (2.4.0) of the step-by-step run; both showed it.
5. One ANR on 2.2.0 after leaving the player (main thread waiting on the RenderThread, 0 % CPU). It did
   not come back after a cold boot of the emulator, on the same install, and was not seen on any other
   step. Treated as a host-GPU emulator stall.

Positions in the step-by-step run: S1E3 was at 6:10 offline on 2.0.0 (the server still had about 3:33
from the first run, so a lost local row would have shown "28 mins left"). After each step the page said
"25 mins left" and playback resumed at 6:17, 6:20, 6:26 and 6:31 (each check played a few seconds).

## What changes for a user who changed nothing

- **Dynamic colours off at 2.2.0.** `pref_dynamic_colors` defaulted to `true` in 2.0.0 and to `false`
  since 2.2.0. A user who never touched it gets the Ephyra palette instead of the wallpaper colours. The
  2.2.0 notes say so ("Material You dynamic colours are now an opt-in instead of the default").
- **The Favorites Home row is gone at 2.1.0.** The row was removed in favour of the Favorites tab (#188),
  which is on by default. The row disappears from Home and from the Home rows list without notice; the
  2.1.0 notes announce the tab, not the row's removal. Nothing is lost (the favourites are on the server
  and in the tab), but a user who had the row on may look for it.
- **Load artwork at display size on at 2.2.0.** `pref_image_display_size` went from `false` to `true`
  (#254). Not visible as a setting change to most users.

## Room

Checked against the code at `main` (`a6db7acd`) and at each release tag.

- The database is `ServerDatabase` (`data/src/main/java/dev/jdtech/jellyfin/database/ServerDatabase.kt`),
  version 18. Schema per release: 14 in 1.2.0-ce.6, 2.0.0 and 2.1.0; 16 in 2.2.0; 18 in 2.3.0 and 2.4.0.
- Schemas are exported (`room3 { schemaDirectory("$projectDir/schemas") }` in `data/build.gradle.kts`),
  and every version from 2 to 18 is checked in under `data/schemas/dev.jdtech.jellyfin.database.ServerDatabase/`.
- The steps since 2.0.0 are all `AutoMigration`s without a spec, and each only adds columns (from the
  exported JSON):

  | From → to | Added |
  |---|---|
  | 14 → 15 | `users.primaryImageTag TEXT NULL` |
  | 15 → 16 | `mediastreams.profile TEXT NULL` |
  | 16 → 17 | `users.pinOnStart INTEGER NOT NULL DEFAULT 1`, `users.pinSalt TEXT NULL`, `users.pinHash TEXT NULL` |
  | 17 → 18 | `users.lockType TEXT NOT NULL DEFAULT 'pin'` |

  Older steps: automatic 2 → 3 to 5 → 6 (4 → 5 drops `trickPlayManifests`, 5 → 6 drops `intros`), the
  manual `MIGRATION_6_7` (drops and recreates `segments`), automatic 7 → 8 to 13 → 14.
- `core/src/main/java/dev/jdtech/jellyfin/di/DatabaseModule.kt` builds the database with
  `.addMigrations(MIGRATION_6_7).fallbackToDestructiveMigration(dropAllTables = true)`, in every release
  since 1.2.0-ce.6. When Room finds no migration path, it drops every table without logging anything:
  servers and their addresses, users (access tokens, profile PINs and patterns), the metadata of every
  download (movies, shows, seasons, episodes, sources, media streams, trickplay, segments), user data
  (offline positions and played and favourite changes not yet synced), pending downloads and the
  per-series track choices. There is no downward migration, so this is what happens on a downgrade
  across a schema version, and it is what would happen on an update if a migration failed validation.
- No test migrates an old schema: there is no `MigrationTestHelper` and no `androidTest` source set. The
  tests in `data/src/test` that touch the database use a fake `ServerDatabaseDao`.

## Preferences

- Settings are not in Room. `AppPreferences` (`settings/src/main/java/dev/jdtech/jellyfin/settings/domain/AppPreferences.kt`)
  wraps the default `SharedPreferences`, one string key per setting. This is why settings survive a
  downgrade that resets the database.
- From 2.0.0 to now no key was renamed and none changed type. Keys added since 2.0.0: the session copy
  (`pref_session_server_id`, `_base_url`, `_access_token`, `_user_id`, empty on the first start after the
  update and filled from Room), the detail page switches (`pref_detail_*`), `pref_show_favorites_tab`,
  `pref_ask_user_on_start`, `pref_player_still_watching_enabled` (default off),
  `pref_subtitle_override_embedded_styles`, `pref_home_cache` and
  `pref_player_media_segments_actions_migrated`.
- Defaults changed since 2.0.0: `pref_dynamic_colors` true → false, `pref_image_display_size`
  false → true, and the per-type media segment actions (`pref_player_media_segments_*_action`) from
  unset to Ask (intro, outro) or Ignore (recap, preview, commercial). The last ones are filled once from
  the old switches, guarded by `pref_player_media_segments_actions_migrated` (UPG-09 passed).
- Other one-time migrations, each guarded by its own flag: `mpv_migrated` and
  `pref_player_mpv_ao_aaudio_migrated` (UPG-10, not tested).

## Downgrade

`adb install -r` of an older version is refused (`INSTALL_FAILED_VERSION_DOWNGRADE`), so a user can only
go back by uninstalling (which loses everything) or by forcing it with `adb install -r -d`. Forced:

| Downgrade | Room schema | Result |
|---|---|---|
| 2.4.0 → 2.3.0 | 18 → 18 | Everything kept: login, Home online, two downloads (381 MB), S1E3 "25 mins left", theme, Max days. |
| 2.3.0 → 2.2.0 | 18 → 16 | Opens on Welcome, then "No servers found": servers, user and login gone. After logging in again, Downloads says "No downloads yet" while the two files (363 MiB) stay in `Android/data/nl.midasvo.findroid.ce/files/downloads/`. Settings kept. Nothing in the log. |
| then 2.2.0 → 2.4.0 | 16 → 18 | The new login is kept; the downloads are still not listed and the files still on disk. |
| 2.4.0 → 2.1.0 | 18 → 14 | Welcome again: login lost. |

Proposed text for the release notes:

> Going back to an older version is not supported. Android refuses it unless you uninstall first. If you
> force it (`adb install -d`) to a version older than 2.3.0, Ephyra forgets your servers, sign-ins and the
> list of downloads. The files stay on the device and take up space until you clear the app's storage.
> Settings are kept. Going from 2.4.0 back to 2.3.0 keeps everything.

## Not tested

- **TV**, on every path, including UPG-13 (the phone APK replaced by the TV APK on a box). The run was
  phone only.
- **A beta install** next to stable (UPG-14).
- **The mpv-specific row** UPG-10 (`aaudio` moved to `audiotrack`): the mpv backend crashes host-GPU
  emulators, so it was not used.
- **Favourites on the intermediate steps** (2.1.0, 2.2.0, 2.3.0): only checked at 2.0.0 and 2.4.0.
- **The older Display-switch path of UPG-11:** 2.0.0 already has the Home rows screen.
- **On 1.2.0-ce.6 → 2.4.0** only the login and the theme were checked.
- **The pattern lock** after the upgrade (UPG-08 was run with a PIN).

## Findings

| Finding | Issue |
|---|---|
| Downloaded files stay on disk after the database is reset (downgrade or a failed migration); they are never listed again or removed | #379, fixed: the daily orphan sweep removes files and artwork no row refers to once they are a day old |
| No Room migration tests for the released schemas (14, 16, 18) while the fallback is destructive | #380 |
| The release notes do not say that the Favorites Home row moved to the Favorites tab (2.1.0) | not filed |
| The release notes do not warn that a downgrade below 2.3.0 signs you out and forgets downloads | not filed (the text above is the proposal) |

Two observations, not filed: on 2.0.0 a 360p transcoded download from the demo server stayed at 0 % for
over three minutes (Original quality worked; on 2.4.0 transcoded downloads fail with a 404 because of
#444); and the one ANR on 2.2.0 described in note 5.
