# Profiles and PIN

Status: saved users on a server as profiles with avatars (#245) and an optional local PIN (#299) or phone unlock pattern (#330); built on phone and TV. As of 2026-11-25 (`main`).

## Start here

- Lock rules, pattern geometry, and PBKDF2 hashing live in `application/.../profilelock/`: `ProfileLock` (process-wide singleton bound in `data`'s `DatabaseModule`), `ProfilePinHasher`, `ProfilePattern`, and `ProfileLockEntry`.
- Switching and PIN/pattern prompts live in `setup/.../presentation/`: `UsersViewModel`, `ProfilePinGateViewModel`, `ProfilePinSettingsViewModel`, and `PinPrompt`.
- Phone UI lives in `app/phone/.../presentation/setup/pin/` (`ProfilePinGateScreen`, `ProfilePinScreen`, `PinDialog`, `PatternGrid`, `PatternDialog`, `SwitchPinDialog`) and `ProfileBottomSheet` (`app/phone/.../presentation/film/components/ProfileBottomSheet.kt`). TV UI lives in `app/tv/.../ce/presentation/pin/` (`TvProfilePinGateScreen`, `TvProfilePinScreen`, `TvPinDialog`, `TvPinPad`, `TvSwitchPinDialog`) and `app/tv/.../presentation/setup/users/UsersScreen.kt`.
- Start-up gating (`MainState.profileLocked` and `MainState.askUserOnStart`) is derived in `MainViewModel` (`core/.../viewmodels/MainViewModel.kt`) and enforced in both `NavigationRoot`s before Home renders.
- Reference screenshots for phone and TV are in [`docs/profiles/pin/`](../../docs/profiles/pin/).
- Test fast: `./gradlew :application:test`, `./gradlew :data:testDebugUnitTest --tests '*ProfileLockTest*'`, and `./gradlew :setup:testDebugUnitTest --tests '*Pin*'`.

## What it does

Each signed-in user on a server is stored in Room's `users` table (`UserEntity` / `User`). `EphyraAvatar` (`core/.../presentation/ephyra/EphyraAvatar.kt`) shows the user's Jellyfin picture when `User.primaryImageTag` is present, or initials (`avatarInitials`, falling back to `"?"` when blank) on one of four theme container colours picked deterministically from `UserId` (`avatarColorIndex`, preserving pre-#773 UUID hash colours). A locked profile (`User.hasPin`) displays a lock badge; the signed-in user gets a check badge (`current`). Phone and TV wrap `EphyraAvatar` as `UserAvatar`.

Users switch profiles from `HomeHeader` → `ProfileBottomSheet` on phone (which lists other saved users and server public users), from the drawer's profile header (`MainScreen`) → `UsersScreen` on TV, or from Settings → Users. Both `ProfileBottomSheet` and `UsersScreen` drive `UsersViewModel`. When `appPreferences.askUserOnStart` is enabled and the active server has more than one saved user, `MainState.askUserOnStart` is true and both `NavigationRoot`s push `UsersRoute` once per app start (`rememberSaveable`; on TV a `WatchNextLink` takes precedence).

A profile may hold one local lock at a time (`User.pinHash`, `User.pinSalt`, `User.lockType`, `User.pinOnStart`):
- **PIN** (`ProfileLockType.PIN`, phone and TV): 4 to 6 ASCII digits (`ProfilePinHasher.MIN_LENGTH`..`MAX_LENGTH`).
- **Pattern** (`ProfileLockType.PATTERN`, phone only): 4 to 9 distinct dots on a 3×3 grid numbered `0..8` (`ProfilePattern`), serialized as dash-separated indices (e.g. `"0-1-2-5-8"`). A straight line across an unconnected middle dot automatically connects that dot (`ProfilePattern.connect`). Setting one type replaces the other (`ProfileLock.setPin`, `ProfileLock.setPattern`).
- Both secrets are hashed locally by `ProfilePinHasher` (PBKDF2-HMAC-SHA256, 20,000 iterations, 16-byte random salt, 256-bit key, constant-time `MessageDigest.isEqual` comparison) and never leave the device.

`ProfileLock` keeps in-memory unlock state for the process lifetime: `lastUnlockedUserId` tracks the single active profile, while `unlockedUserIds` accumulates profiles unlocked or switched to in this process so `MainViewModel.profileLocked` does not flash the start gate on the outgoing profile while Room catches up. Switching to another locked profile requires its lock (`ProfileLock.needsPinToSwitch`). Setting a PIN/pattern or toggling `pinOnStart` from inside Settings unlocks that profile immediately.

On a new process, `ProfileLock.isLockedOnStart` is true when the current user has a lock, `pinOnStart` is true (default), and `user.id !in unlockedUserIds`. Both `NavigationRoot`s render `ProfilePinGateScreen` / `TvProfilePinGateScreen` *instead of* Home while `setupComplete && profileLocked`, so Home loads nothing until unlocked (and on TV a Watch Next deep link waits on the back stack underneath). Cancelling at start (`ProfilePinGateAction.OnCancel` or Back) calls `SetupRepository.clearCurrentUser()`, dropping to `UsersRoute`.

Settings → Users → Profile lock (phone) / Profile PIN (TV, switched by `AppVariant` in `SettingsViewModel`) opens `ProfilePinScreen` / `TvProfilePinScreen` backed by `ProfilePinSettingsViewModel`. Setting a new lock asks twice (`PinStep.New`, `PinStep.Confirm`); changing, switching type, or removing asks for the current lock first (`PinStep.Current`). A forgotten lock cannot be recovered: the user must remove the profile or sign in again, which clears the lock.

## Where the code is

| Path | What |
|---|---|
| [`domain/.../models/User.kt`](../../domain/src/main/kotlin/dev/jdtech/jellyfin/models/User.kt) | `User`, `ProfileLockType` (`PIN`, `PATTERN`), `User.hasPin`, `User.profileLockType` |
| [`application/.../profilelock/ProfileLock.kt`](../../application/src/main/kotlin/dev/jdtech/jellyfin/profilelock/ProfileLock.kt) | Process unlock state, `setPin`, `setPattern`, `clearPin`, `verify`, `needsPinToSwitch`, `isLockedOnStart` |
| [`application/.../profilelock/ProfilePinHasher.kt`](../../application/src/main/kotlin/dev/jdtech/jellyfin/profilelock/ProfilePinHasher.kt) | PBKDF2-HMAC-SHA256 hashing (`ProfilePinHasher`), [`ProfilePattern`](../../application/src/main/kotlin/dev/jdtech/jellyfin/profilelock/ProfilePattern.kt), [`ProfileLockEntry`](../../application/src/main/kotlin/dev/jdtech/jellyfin/profilelock/ProfileLockEntry.kt) |
| [`application/.../repository/UserLocks.kt`](../../application/src/main/kotlin/dev/jdtech/jellyfin/repository/UserLocks.kt) | Store port for writing `pinHash`, `pinSalt`, `lockType`, and `pinOnStart` |
| [`data/.../database/RoomServerStore.kt`](../../data/src/main/java/dev/jdtech/jellyfin/database/RoomServerStore.kt) | Room `UserLocks` implementation over [`ServerDatabaseDao`](../../data/src/main/java/dev/jdtech/jellyfin/database/ServerDatabaseDao.kt) and [`UserEntity`](../../data/src/main/java/dev/jdtech/jellyfin/models/UserEntity.kt); singleton `ProfileLock` bound in [`DatabaseModule`](../../data/src/main/java/dev/jdtech/jellyfin/data/di/DatabaseModule.kt) |
| [`setup/.../presentation/pin/`](../../setup/src/main/java/dev/jdtech/jellyfin/setup/presentation/pin/) | `ProfilePinGateViewModel`, `ProfilePinSettingsViewModel` (`PinStep`), `PinPrompt` (`PinError`) |
| [`setup/.../presentation/users/UsersViewModel.kt`](../../setup/src/main/java/dev/jdtech/jellyfin/setup/presentation/users/UsersViewModel.kt) | Profile switching, public user refresh, switch lock prompt |
| [`core/.../viewmodels/MainViewModel.kt`](../../core/src/main/java/dev/jdtech/jellyfin/viewmodels/MainViewModel.kt) | Derives `MainState.profileLocked` and `MainState.askUserOnStart` |
| [`core/.../presentation/ephyra/EphyraAvatar.kt`](../../core/src/main/java/dev/jdtech/jellyfin/core/presentation/ephyra/EphyraAvatar.kt) | Shared avatar (picture, initials, deterministic colour, lock/current badges); [`ErrorShake.kt`](../../core/src/main/java/dev/jdtech/jellyfin/core/presentation/ephyra/ErrorShake.kt) for `errorShake` |
| [`app/phone/.../presentation/setup/pin/`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/setup/pin/) | `ProfilePinGateScreen`, `ProfilePinScreen`, `PinDialog`, `PatternGrid`, `PatternDialog`, `SwitchPinDialog` |
| [`app/phone/.../presentation/film/components/ProfileBottomSheet.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/components/ProfileBottomSheet.kt) | Phone Home profile sheet; [`HomeHeader`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/film/components/HomeHeader.kt), [`UserAvatar`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/components/UserAvatar.kt), [`UsersScreen`](../../app/phone/src/main/java/dev/jdtech/jellyfin/presentation/setup/users/UsersScreen.kt) |
| [`app/tv/.../ce/presentation/pin/`](../../app/tv/src/main/java/dev/jdtech/jellyfin/ce/presentation/pin/) | `TvProfilePinGateScreen`, `TvProfilePinScreen`, `TvPinDialog`, `TvPinPad`, `TvSwitchPinDialog` |
| [`app/tv/.../presentation/setup/users/UsersScreen.kt`](../../app/tv/src/main/java/dev/jdtech/jellyfin/presentation/setup/users/UsersScreen.kt) | TV user picker, [`UserItem`](../../app/tv/src/main/java/dev/jdtech/jellyfin/presentation/setup/components/UserItem.kt), drawer item in [`MainScreen`](../../app/tv/src/main/java/dev/jdtech/jellyfin/ce/presentation/main/MainScreen.kt) |
| [`app/phone/.../NavigationRoot.kt`](../../app/phone/src/main/java/dev/jdtech/jellyfin/NavigationRoot.kt), [`app/tv/.../NavigationRoot.kt`](../../app/tv/src/main/java/dev/jdtech/jellyfin/NavigationRoot.kt) | Gates Home behind `ProfilePinGateScreen` / `TvProfilePinGateScreen`, pushes `UsersRoute` for `askUserOnStart`, hosts `ProfilePinRoute` |

## Rules

- **R4**: no production file outside `data` imports `ServerDatabaseDao`. `ProfileLock` reads users through `StoredUsers` and writes locks through `UserLocks`.
- **R9**: only the apps depend on `data`; `setup`, `settings`, and `core` never depend on `data` (`DatabaseModule` in `data` provides `UserLocks` and the singleton `ProfileLock` to Hilt).
- **R10**: `UserId` and `ServerId` are value classes (`Ids.kt`), never `String` or `java.util.UUID` in production signatures outside the adapter.
- **R19**: `application/.../profilelock/` stays pure Kotlin (`ProfilePinHasher` uses only JDK `javax.crypto`, `java.security`, and `java.util.Base64`).
- **Translations**: all CE profile/lock strings (`ce_profile_*`, `ce_users_*`, `ce_settings_profile_*`) must be added to `strings_ce.xml` in all 36 languages and pass `check-translations.py`.

## How to test it

```bash
./gradlew :application:test
./gradlew :data:testDebugUnitTest --tests '*ProfileLockTest*'
./gradlew :setup:testDebugUnitTest --tests '*Pin*'
./gradlew :core:testLibreDebugUnitTest --tests '*EphyraAvatarTest*'
./gradlew :app:phone:testLibreDebugUnitTest --tests '*ProfileBottomSheetTest*'
./gradlew :app:tv:testLibreDebugUnitTest --tests '*TvPinPadTest*' --tests '*MainReturnLoadsTest*'
```

Unit tests cover hashing and pattern geometry (`ProfilePinHasherTest`, `ProfilePatternTest`), lock state and cross-type verification (`ProfileLockTest`), switch/gate/settings flows over `FakeRoles` (`ProfilePinSwitchTest`), avatar initials and legacy UUID colour stability (`EphyraAvatarTest`), landscape profile sheet layout (`ProfileBottomSheetTest`), TV remote key handling (`TvPinPadTest`), and TV drawer user reload on return (`MainReturnLoadsTest`).

On a device or emulator, `tools/test-server/setup.sh` creates multiple users (`tester`, `second`, `nopass`, `nodownload`; see [test-environment.md](test-environment.md)) so multi-profile switching, `askUserOnStart`, and PIN/pattern locks can be exercised against `http://10.0.2.2:8096`.

**What unit tests do not cover:**
- `PatternGrid` touch-drag gestures, `errorShake` timing, and `ProfileBottomSheet` cross-window hiding on `HomeScreen` (#872).
- Room auto-migrations (14→15, 16→17, 17→18) preserving existing logins and lock rows on upgrade (`ProfilePinSwitchTest` and `ProfileLockTest` run against in-memory `FakeRoles`).
- TV Watch Next deep links launching into a locked profile (holding the Watch Next stack beneath `TvProfilePinGateScreen` until unlocked) and physical remote Enter/OK quirks.

## Before a PR

```bash
./gradlew ktfmtCheck
./gradlew :application:test :architecture:test
./gradlew :data:testDebugUnitTest --tests '*ProfileLockTest*'
./gradlew :setup:testDebugUnitTest --tests '*Pin*'
./gradlew :app:phone:compileLibreDebugKotlin :app:tv:compileLibreDebugKotlin
python3 .claude/skills/start-work/scripts/check-docs.py
python3 .claude/skills/translate-strings/scripts/check-translations.py   # when strings changed
```

## Decisions and history

- [#245](https://github.com/midasvo/ephyra/issues/245) / [#248](https://github.com/midasvo/ephyra/pull/248): profile switching UI (`ProfileBottomSheet`, `UsersScreen`), `EphyraAvatar` deterministic initials/colours (preserving legacy `UUID.hashCode()` colours in [#773](https://github.com/midasvo/ephyra/issues/773)), `User.primaryImageTag`, and `askUserOnStart`.
- [#299](https://github.com/midasvo/ephyra/issues/299) / [#329](https://github.com/midasvo/ephyra/pull/329): local profile PIN stored as a salted PBKDF2-HMAC-SHA256 hash on the `User` row (20,000 iterations so slow TV boxes check without a visible wait — couch-level privacy rather than resistance to offline DB extraction; no attempt lockout or inactivity re-lock), `ProfileLock` singleton, start gate before Home, `TvPinPad`, and Settings → Profile PIN.
- [#330](https://github.com/midasvo/ephyra/issues/330) / [#333](https://github.com/midasvo/ephyra/pull/333): unlock pattern on phone (`ProfileLockType`, `ProfilePattern`, `PatternGrid`, `PatternDialog`), reusing `pinHash`/`pinSalt` so each profile has one lock at a time, and extending `ProfilePinSettingsViewModel` / `PinPrompt` while keeping TV's exhaustive `when` branches untouched.
- [#408](https://github.com/midasvo/ephyra/issues/408), [#409](https://github.com/midasvo/ephyra/issues/409) / [#532](https://github.com/midasvo/ephyra/pull/532): TV drawer reloads the user on every return (`loadsOnReturn`) so the lock badge tracks PIN changes immediately; `TvPinPad` moves focus to OK on a remote number key press so OK submits instead of typing a digit.
- [#440](https://github.com/midasvo/ephyra/issues/440): `ProfileBottomSheet` opens fully (`skipPartiallyExpanded = true`) and scrolls vertically in short landscape windows so Settings remains reachable.
- [#866](https://github.com/midasvo/ephyra/issues/866) / [#873](https://github.com/midasvo/ephyra/pull/873), [#872](https://github.com/midasvo/ephyra/issues/872) / [#881](https://github.com/midasvo/ephyra/pull/881): tab root ViewModels (`FavoritesViewModel`, `SearchViewModel`, `DownloadsViewModel`) and `HomeScreen` (`SessionUser`, hiding the sheet before removal, `key(state.user?.id)`) drop the previous user's rows immediately on switch.
- [#967](https://github.com/midasvo/ephyra/issues/967), [#988](https://github.com/midasvo/ephyra/issues/988): user lock writes live behind `UserLocks` (`RoomServerStore`), and `setCurrentUser` updates only `currentUserId` instead of replacing the `ServerEntity` row.

## Pitfalls

- **tv-material button closure capture (`TvPinPad.kt`):** `androidx.tv.material3.Button` retains the click lambda from its first composition for D-pad OK. Because multi-step dialogs (`TvProfilePinScreen`) pass a new `pin` state per step, `TvPinPad` reads `pin` and `onSubmit` via `rememberUpdatedState` — otherwise Change PIN re-saves the old PIN.
- **Remote OK vs number keys (`TvPinPad.kt`, #409):** `TvPinPad` intercepts digit keys regardless of focused button, so a digit key must also request focus on `okKey`; `Key.Enter` / `Key.DirectionCenter` is deliberately not handled in `onPreviewKeyEvent` because some remotes send Enter for D-pad OK.
- **Re-authenticating wipes the lock (`SetupRepositoryImpl.kt`):** `insertUser` uses `OnConflictStrategy.REPLACE` with default `pinHash = null`, so signing in again as a locked user clears their local PIN/pattern (the documented recovery path for a forgotten lock).
- **Do not shrink `unlockedUserIds` on switch (`ProfileLock.kt`):** `unlockedUserIds` intentionally keeps previously unlocked user IDs while `lastUnlockedUserId` tracks only the latest profile. Removing the old user ID immediately on switch causes `MainViewModel.profileLocked` to flash the start gate on the outgoing profile while Room catches up.
- **Cross-user frame leakage on switch (#866, #872):** `ProfileBottomSheet` switches users while `HomeScreen` and tab root ViewModels remain alive; user-scoped content must be gated with `shownTo(userId)` / `LocalServerSession.current.userId` (and `SessionUser` on `HomeScreen`) rather than waiting for Room's flow.
- **Keep `PinStep` and `PinError` TV-safe:** `ProfilePinSettingsViewModel` and `PinPrompt` are shared by phone and TV; adding new `PinStep` or `PinError` subtypes breaks TV's exhaustive `when` branches unless TV is updated in the same change.
