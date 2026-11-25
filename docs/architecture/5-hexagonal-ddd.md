# 5. Further towards hexagonal, with DDD where it pays

**Status: built, 2026-11-23.** Every task of 5.3 is merged. The rest of this file is as written on
2026-11-22; the code as it is now is described in [README.md](README.md) and `CLAUDE.md`.

| Task | Issue | Pull requests |
|---|---|---|
| T1, ids named by kind | #773 | #879, #883, #882, #886, #885 |
| T2, ids as value classes | #773 | #888 |
| T4, `MediaRepository` and the model names (Q2 b) | #774 | #895 |
| T3, the `application` module, rule R9 | #896 | #904 |
| T8, `Ticks`, rule R12 | #900 | #903 |
| T5, `DownloadState`, rule R13 | #897 | #905 |
| T6, `PlayState`, rule R14 | #898 | #906 |
| `ServerId` and `MediaSourceId` as value classes, R10 second half | #902 | #908 |
| T7, the store ports and the switch as one `ActiveServer`, rule R16 | #899 | #910, #911, #913, #915 |
| T9, the `downloads` module, rule R15 | #901 | #914 |
| T10, the docs | #772 | this file's status, the README, `CLAUDE.md`, the upstream-sync skill |

Decided against, as recommended below: a use case per repository function, an `Item` aggregate or
domain events for the catalog, ports for preferences, splitting `MediaRepository` per context, a
server-neutral device profile, `Ticks` as a value class, one id type per kind of item (Q4), and
renaming the packages (a possible follow-up, Q2). What is left is in the README under "Not done".

Files 1 to 4 planned the move to ports and adapters (#733). Thirty-six of its forty tasks are
merged. On 2026-11-22 the owner asked to take the structure as far towards hexagonal architecture
with DDD as is sensible, before any Emby work. This file measures where the code stands, says what
DDD means for this app, and lists the tasks. The decision is [ADR 0002](adr/0002-hexagonal-ddd.md).

All counts were taken on `main` at `9460ef95c` (2026-11-22), production sources only
(`src/main`, `src/libre`, `src/proprietary`). Where this file and the code differ later, the code
is right.

## 5.1 Where the code is not hexagonal yet

### Module arrows

```
app/phone ─► core data domain modes/film player/{cast,core,local} server/jellyfin settings setup
app/tv    ─► core data domain modes/film player/{core,local} server/jellyfin settings setup
modes/film, setup, player/cast ─► core data domain settings          (player/cast also player/local)
player/local ─► data domain player/core settings
core ─► data domain player/core settings
data ─► domain settings
server/jellyfin ─► domain settings           (tests: data, testing)
settings, player/core ─► domain
domain ─► nothing (kotlinx-coroutines, kotlinx-serialization, OkHttp)
```

What points the wrong way:

1. **Every feature module depends on `data`, which is the Room adapter.** It has to: the driving
   port, `JellyfinRepository`, lives in `data` next to the DAO. So `modes/film`, `setup`, `core`,
   `player/local` and `player/cast` can all see Room. Rule R4 freezes the DAO at 20 files outside
   `data`, but the entities (`Findroid*Dto`, 9 imports outside `data`) and the `database` package
   (20 files) are visible everywhere. In hexagonal terms the application's port sits inside one of
   its driven adapters.
2. **`core` is three things**: the composition root (`di/`, 4 Hilt modules that build the adapters),
   two bounded contexts (the download engine `DownloaderImpl`, 1,559 lines, with
   `DownloadQueue`, 947, and the user-data sync `SyncWorker`, 444), and shared Compose. Features
   depend on all of it to get one of them.
3. **`server/jellyfin` and `data` read `AppPreferences`.** The repository reads the current server
   id, the bitrate cap and the player backend from preferences (`currentServerId()`,
   `getPlaybackSources`, `localPlayerTarget()`). The adapter's Hilt module reads timeouts. A port
   implementation that reads the UI's preferences decides things the caller should say.
   #766 (PR 874) removes the player part.
4. `domain` itself is clean: no Android, no SDK, no Room (rule R5). Its two outside
   dependencies are deliberate: coroutines (`Flow` in ports) and serialization (one annotation on
   `FindroidChapter`, for a Room JSON column). It had OkHttp too, for `MediaHttpClient`, until #964
   moved that into the module `http`; rule R19 now allows `domain` and `application` nothing but
   Kotlin, kotlinx and the JDK.

### What `domain` lacks or leaks

| Leak | Where | Size |
|---|---|---|
| Ids are `java.util.UUID` | `Ids.kt` aliases; 173 production files import `UUID`, 49 calls to `UUID.fromString`; only 13 files use the alias names | #773 |
| `@Serializable` on `FindroidChapter` | a storage concern of `data` in the model | 1 file |
| Models named after the upstream app | 31 `Findroid*` types, 317 files name one | §5.3 |
| The driving port in an adapter | `JellyfinRepository` in `data`, returning `androidx.paging.PagingData` | 1 interface, 52 functions |
| "Finished download" is a file name | `path.endsWith(".download")` decides it in 8 files, 24 times (`FindroidItem.isDownloaded`, `PlaylistManager`, `ItemButtonsBar`, three download ViewModels, `DownloaderImpl`) | §5.2 |
| Ticks as bare `Long` | conversions with four local constants (`TICKS_PER_MS`, `TICKS_PER_MILLISECOND`, `TICKS_PER_MINUTE` twice, `TICKS_PER_SECOND`) and literal `10_000` in 22 files | §5.2 |
| User-data rules outside `domain` | see 5.2: the rules are in three modules | §5.2 |

The models are anaemic but that is mostly right: an item is what the server says it is. The
exceptions are in 5.2.

### The driven side

| Outside world | Behind a port? | Notes |
|---|---|---|
| Jellyfin server | Yes: five ports, `MediaServer`, `ServerConnector`, contract suites | done in #733 |
| Room | **No.** 20 files outside `data` use `ServerDatabaseDao` (R4 baseline) | three groups, see below |
| DataStore / preferences | `AppPreferences` (in `settings`), used by 56 files in 9 modules | it is already a typed abstraction; a port per preference would be ceremony. Only `data` and the adapter should stop reading it |
| Download engine and files | `Downloader` interface in `core`; the implementation mixes DAO, files, server and Android `DownloadManager` | |
| WorkManager | `UserDataSyncRequester` in `data` is the port; the worker is in `core` | fine |
| Player engines | Yes: `PlayerEngine`, rule R6 | done in #764 |
| Cast | Yes: interfaces in `player/cast/src/main`, SDK in `proprietary` | done |
| Image loading | Coil in the UI, URLs from the models, URL rules in the adapter | fine |
| Reachability | `ServerReachability` in `core` over `ServerSession` | fine |

The 20 DAO users fall into three groups, which are the three stores a port would have to offer:

- **Known servers, addresses, users, the current one** (8 files): `ServerSessionKeeper`,
  `ApiSession`, `MainViewModel`, `AboutViewModel`, `HomeViewModel`, `HomeRowsStore`,
  `SetupRepositoryImpl`, `SetupDataModule`.
- **Downloads** (6): `DownloaderImpl` (61 DAO calls), `DownloadQueue`, `SmartDownloads`,
  `DownloadsViewModel`, `DownloadedShowViewModel`, `ShowViewModel` (episode counts).
- **Pending user data for the sync** (2): `SyncWorker`, `UserDataSync`.
- Plus the four Hilt modules that wire them, which are the composition root and may keep it.

### The driving side

There are 31 ViewModels (14 in `modes/film`, 9 in `setup`, 3 each in `core` and `settings`, one
each in the players). They call the 52-function `JellyfinRepository`; the most called are the
user-data marks (`markAsPlayed` and its three siblings, 4 ViewModels each), `getSeasons` and
`getEpisodes` (5 each). Thirty of the 52 functions are "ask the server, fall back to Room, map":
a use case class around one of them would forward one line. The logic that is neither a call nor a
screen is already in classes of its own in `player/local/domain` (26 files: `PlaybackReporter`,
`PlaylistManager`, `PlaybackStartGate`, …) and `core/presentation/downloader` (`DownloadQueue`,
`SmartDownloadPlanner`).

What the four ViewModels with `setPlayed`/`setFavorite` duplicate is presentation (catch, log,
reload policy, patch the state); a shared helper would save lines, not rules. It is not a reason
for a use-case layer.

### Single Jellyfin bindings, and the split switch

From the comments on #733 (2026-11-22), still true:

- `ServerSession`, `MediaHttpClient`, `DeviceReportSource` and the session restore are bound once,
  to Jellyfin. `MediaServer`, `ServerConnector` and `UserDataSyncPorts` are already maps keyed by
  `ServerType`. Since #899 the session restore is not: it fills the `ActiveServerHolder` in `core`,
  which every adapter reads.
- The server type and the session are set at two moments (`currentType.stored = …`, then
  `session.use(…)`, in `SetupRepositoryImpl` and `ApiSession.kt`). With two adapters a request in
  between gets the new adapter with the old address and token. Fixed in #899 (T7): one
  `ActiveServer` value on an `ActiveServerHolder`, which `CurrentMediaServer` reads once per
  access and hands to the adapter (`MediaServer.at`); rule R16.
- Downloads of a server whose type this build does not know cannot be listed or played.

## 5.2 What DDD means here

### The claim to test

File 4 rejected tactical DDD because "the domain is thin". Measured now:

- **It holds for the catalog.** Browsing, details, search and home rows are reads with a stored
  fallback. No invariant, no state of our own. Aggregates or use cases there would be wrappers.
- **It does not hold for user data.** "Played", "favourite", "resume position" and "last played"
  have rules, and they are spread over three modules:
  - `domain`: `stopUserData` (resume thresholds, #637);
  - `data`: `markAsPlayed` sets the last-played date when unknown (#633), `markAsUnplayed` takes
    the time before the request and clears the date only when the server was reached (#635),
    `writeAndReport` writes and marks before reporting (#281);
  - `core`: `SyncWorker` decides who wins with `serverIsNewer`, `isNewerThanServer`,
    `keepServerProgress` and `serverUserDataToStore`.
  These are one concept, the play state of an item for one user, with rules that must agree with
  each other. Today a change to one rule has to be found in three places.
- **It does not hold for downloads.** A download has a life cycle (asked, queued, transferring,
  finished, failed, stale) that is encoded in a file name suffix and in which tables have a row.
  Eight files decide "finished" by `endsWith(".download")`.
- **It partly holds for playback.** The logic is real but was already given classes of its own in
  phase 5 (`PlaybackReporter`, `PlaySessions`, `ResumeThresholds`, `ResolvePlayback` in #766).
  What is left is naming, not structure.

So: no aggregates with repositories per entity, no domain events beyond the two event flows that
exist (`DataChangeEvents`, `PlaybackEvents`), no use case per call. Two domain models where the
rules are (`PlayState`, `DownloadState`), value objects where a primitive is misread today, and
the stores the downloads and the sync need.

### Bounded contexts

| Context | What it owns | Where it is now | Where it should be |
|---|---|---|---|
| Catalog | items, libraries, people, search, home rows | `domain/models`, the catalog's ports (split by role in #967: `ItemPort`, `ItemListPort`, `ItemExtrasPort`, `ServerInfoPort`), the read side of `JellyfinRepositoryImpl`, `modes/film` | unchanged |
| Playback | what plays, with which source, the queue, reports, play sessions | `player/*`, `PlaybackPort`, `PlaySessions`, `ResumeThresholds` | unchanged after #766 |
| User data and sync | played, favourite, position, last played, the pending marks | `domain` + `data` + `core/work` | rules in `domain` (`PlayState`), store behind a port |
| Downloads | asked, queued, stored items and files, storage | `core/presentation/downloader`, `core/utils/DownloaderImpl`, `data` tables, `modes/film/.../downloads` | state in `domain` (`DownloadState`), store behind a port; a module of its own is optional (T9) |
| Identity and session | servers, addresses, users, tokens, profile PINs, the current one | `ServerConnector`, `ServerSession`, `setup`, `core/session`, `core/di/ApiSession` | store behind a port, one switch call |
| Settings | preferences | `settings` | unchanged (a generic subdomain) |

### Ubiquitous language

| Concept | Call it | The code also says |
|---|---|---|
| A media server the app knows | server | `Server` (Room), `ServerInfo` (port), `currentServer` (preference) |
| Where it answers | address | `ServerAddress`, `baseUrl`, `address`, `serverAddress` |
| A person on a server | user | `User`, `UserId`, `currentUser` |
| The app logged in to a server as a user | **server session** (`ServerSession`) | `ApiSession`, `ServerSessionKeeper`, "session" alone |
| The server's job for one stream (transcode) | **play session** (`playSessionId`) | "session" alone |
| A Cast connection | **Cast session** | `CastSessionManager` |
| Anything in the catalog | item (`FindroidItem`) | media, `BaseItem` (Jellyfin), `MediaItem` (media3, only in the player) |
| A library the user sees | library | `FindroidCollection`, user view, collection |
| One file or transcode of an item | **version** in the UI, source in code (`FindroidSource`) | media source, `getVersions()` |
| An audio, video or subtitle part of a source | stream (`FindroidMediaStream`) | track (player), "stream URL" means the URL |
| An item kept on the device | download | a `LOCAL` source; "offline" (no offline mode since #281) |
| Where to continue | resume position (`playbackPositionTicks`) | progress, position |
| Seen | played | watched (UI text) |

"Session" alone is ambiguous in three ways and should not be used for a new name. The glossary
goes into `CLAUDE.md` with #772.

### Value objects

| Concept | Today | Worth it? |
|---|---|---|
| `ItemId`, `UserId` | `UUID` aliases | **Yes** (#773): an Emby id is not a UUID, and a value class stops a user id going where an item id belongs |
| `ServerId`, `MediaSourceId` | `String` aliases | Yes, in the same task, cheap: they are strings already |
| `DownloadState` | file name suffix | **Yes** (T5): it is the invariant most often re-derived |
| `PlayState` | fields of the models and the Room row, rules in three modules | **Yes** (T6) |
| `AccountKey(serverId, userId)` | two loose parameters on 53 DAO queries, read from preferences at the call | Yes, as the parameter of the stores (T7): "whose state is this" is the class of #810 and #866 |
| Ticks | `Long` | One helper object, not a value class (T8): a value class would touch every model and Room column for a unit conversion |
| Bitrate | `Int?`, `BitrateCap` | No: `BitrateCap` exists |
| Address | `String` | No: normalisation lives in setup only |

### Invariants: where they are enforced

| Invariant | Where today | After |
|---|---|---|
| Resume thresholds decide a stop | `stopUserData` in `domain` (one place) | part of `PlayState` |
| A finished download plays before the server is asked | `JellyfinRepositoryImpl.getPlaybackSources` and `PlaylistManager` | `ResolvePlayback` (#766), with `DownloadState` |
| Write to Room, then report | `writeAndReport` in `data` (one place) | unchanged, over the user-data store |
| Played/unplayed dates, sync conflict rules | `data` and `core/work` | `PlayState` in `domain`, tested without Android |
| State belongs to a server and a user | every caller passes `userId`/`serverId` read from preferences or the session | the stores take an `AccountKey`; the switch sets type, address, token and account in one call |
| A download is of one server | Room key, #718 | unchanged, named by `AccountKey` |

## 5.3 Target and tasks

Target, in one picture:

```
app/phone, app/tv  (composition root, screens)
   │
   ├─► modes/film, setup, settings, player/*, core   ─► application ─► domain
   │                                                       ▲
   ├─► data (Room: implements the stores, the repository) ─┘
   └─► server/jellyfin (implements the server ports) ───────► domain
```

`application` is a new module (T3): the repository interface and the store ports that features
use, with no Room. Features stop depending on `data`; only the apps do (rule R9). Whether it is a
module of its own or the interface moves to `domain` is question Q3.

Each task is one PR (or a few, where it says) that leaves the app working.

| # | Task | Cost | Buys | Device test |
|---|---|---|---|---|
| T1 | **#773 slice a**: every `UUID` that is an id becomes `ItemId`/`UserId` (still aliases), every `UUID.fromString`/`toString()` on an id becomes `String.toItemId()`/`ItemId.asString()` helpers. Four PRs by module group | 173 files, textual, no behaviour change | the flip becomes small | no |
| T2 | **#773 slice b**: the flip to value classes | ~40 files: `Ids.kt`, the adapter mappers, Room converters, `PlayerItem`, the fakes; rule R10 | opaque ids; Emby can use its own | yes: login, play, a download made before the update |
| T3 | `application` module: the repository interface and the event flows leave `data`. Modules with no DAO use left (`player/local`, `player/cast`) drop `data` now; rule R9 with a baseline of the modules that still declare it (`core`, `modes/film`, `setup`), which T7 empties | ~25 build files and moves, packages kept | the player modules can no longer see Room; `core`, `modes/film` and `setup` still can until T7 (R4 freezes only their DAO use) | no |
| T4 | **#774**: `JellyfinRepository` → `MediaRepository`, and the model names (Q2) | 85 files (rename only) or ~320 (with models) | the language says what the code is | no |
| T5 | `DownloadState` on a source, one rule against `".download"` outside the downloader | 8 files | one definition of "finished" | downloads screen |
| T6 | `PlayState` in `domain`: the mark, stop and sync-merge rules move there with their tests | `data`, `core/work`, `domain`; ~6 files | one place for user-data rules | sync after offline marks |
| T7 | Room behind three store ports: `ServerStore` (servers and addresses keyed by `ServerId`, users by `AccountKey`, so a server with no user yet, as during setup, is a valid state), `DownloadStore` and `PendingUserDataStore` keyed by `AccountKey`; the switch publishes type, address, token and account as one immutable value (one `StateFlow` the adapters read once per request), so a request sees the old set or the new one, never a mix; R4's baseline goes to 0, then `core`, `modes/film` and `setup` drop `data` and R9's baseline goes to 0 | 20 files, 3 PRs | the #733 note fixed; stores fakeable | login, switch user and server |
| T8 | `Ticks` helper, the local constants go | 22 files | one conversion | no |
| T9 | `downloads` module out of `core` | ~20 files moved | `core` shrinks to wiring and UI | downloads |
| T10 | #772: `CLAUDE.md`, upstream-sync skill, this folder | docs | the docs describe the end state | no |

Order: T1 → T2 → T4 → T3 → T5 → T6 → T7 → T8 → T9 → T10 (the owner chose to do T9 too, Q1). T1, T2 and T4 touch most files and go
one after the other, each when few PRs are open. T4 before T3 so the moved interface already has
its new name. T5 and T6 are independent of each other after T3.

Upstream syncs: since PR 844 a sync merges translations and cherry-picks code. T1, T2 and T4 make
most upstream Kotlin changes a re-apply by hand; the rest touches CE files. That is accepted.

### #773: opaque ids

- **One `ItemId` for every kind of item**, not one per kind. Both servers give all items (movies,
  episodes, seasons, shows, people, libraries) ids from one space, `FindroidItem.id` is used
  polymorphically, and a per-kind id would add conversions at every list of mixed items. Plus
  `UserId`, and `ServerId` and `MediaSourceId` as value classes over the strings they are now.
- `@JvmInline value class ItemId(val value: String)`. For Jellyfin, `value` is the dashed lowercase
  form `UUID.toString()` gives, which is what Room holds today. The adapter owns the conversion
  (`UUID.toItemId()`, `ItemId.toUuid()`); a rule (R10) allows `java.util.UUID` only in
  `server/jellyfin` and in the few files that make random ids.
- **Room**: no schema change. A column converter `ItemId ↔ String` if Room 3's KSP accepts value
  classes there (checked first in T2; there is no Android-free way to know); otherwise entities keep
  `String` columns and their mappers convert. Either way the stored text is byte for byte what is
  there now, so no migration; a test opens a schema-21 database written with `UUID` text and reads
  the same ids.
- **Navigation**: routes already carry `String`; `UUID.fromString(route.id)` becomes
  `ItemId(route.id)`. Saved back stacks keep working because the string does not change.
- **Parcelize** (`PlayerItem`): supported for value classes over `String`; checked in T2.
- **Traps**: Kotlin mangles the JVM names of functions with value-class parameters, and the test
  fakes are `java.lang.reflect.Proxy` objects that may match on `method.name` (27 files): they
  need `startsWith` or the Kotlin name. `itemId.toString() == mediaItem.mediaId` comparisons in the
  player must become `asString()`. Jellyfin also sends ids without dashes in some answers; the
  adapter parses through `UUID` so the form stays canonical.
- **Steps**: T1 in four PRs (domain + server/jellyfin + testing; data + core; modes/film, setup,
  settings, player/*; app/phone + app/tv). The first by Opus to set the pattern, the rest by
  Sonnet. Then T2 as one PR by Opus, with an emulator run.

### #774: the name, and the other names

`JellyfinRepository` → **`MediaRepository`**, `JellyfinRepositoryImpl` → `MediaRepositoryImpl`,
same package. Then the question the owner asked to have put to them (Q2):

- **(a) Only the repository.** 85 files.
- **(b) Also the `Findroid*` models (recommended).** `FindroidMovie` → `Movie`, `FindroidItem` →
  `Item`, `FindroidSource` → `ItemSource`, `FindroidCollection` → `Library`, the Room entities
  `Findroid*Dto` → `*Entity`. About 320 files, in the same wave as the repository so the files are
  rewritten once. Names that clash (`MediaStream` with the SDK inside the adapter, `TrickplayInfo`
  with `player/core`) are settled in the PR with an import alias in the adapter. This is what makes
  the ubiquitous language the code's own.
- **(c) Also the `dev.jdtech.jellyfin` packages. Not recommended.** Every file, and it is not only
  text: WorkManager stores worker class names (`SyncWorker`) in its database, so queued work is
  lost on update unless re-enqueued; kotlinx-serialization uses class names as default serial names
  in saved navigation state; ProGuard rules and the baseline profiles name packages. All for a
  prefix an agent does not read.

### Tasks not recommended

- **A use case per repository function.** 30 of the 52 forward one line (5.1).
- **An `Item` aggregate or domain events for the catalog.** The server owns that state.
- **Ports for preferences.** `AppPreferences` is already typed; removing it from `data` and the
  adapter (part of T3 and T7) is enough.
- **Splitting `MediaRepository` into one interface per context now.** It would rewrite the same 85
  files again; the user-data and download parts shrink by themselves through T6 and T7.
- **A server-neutral device profile** (D3 stands).
- **Renaming packages** (above).

## 5.4 Questions for the owner

Asked and answered on 2026-11-22; the answers are in ADR 0002. In short: everything including T9, (b), `application`, one `ItemId`, emulators only and one beta for the owner at the end. Renaming the packages is a possible later follow-up.

1. **Q1, scope.** Do T1 to T8 and T10, and file T9 (a `downloads` module) as an issue for later?
   *Recommended: yes.*
2. **Q2, names (#774).** (a) only the repository, (b) also the `Findroid*` models, (c) also the
   packages? *Recommended: (b).*
3. **Q3, where the repository interface goes.** A new `application` module (features depend on it,
   not on `data`), or into `domain` (no new module, but `domain` then knows paging)? *Recommended:
   `application`*, with `paging-common`, which is plain Kotlin. (Since #968 `application` has no
   paging: it offers the plain page read `getItemsPage`, and the pagers are in `modes/film`.)
4. **Q4, ids per kind.** One `ItemId` for all items, or `MovieId`, `EpisodeId`, …?
   *Recommended: one `ItemId`.*
5. **Q5, device tests.** T2 and T7 change what is stored and how a server is switched. On the
   emulators with throwaway users, as before, and no check by you? *Recommended: yes, emulators
   only.*
