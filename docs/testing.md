# Testing

What kinds of test the app has, where each lives, which one to write for a change, and what is
still checked by hand. For contributors and for agents.

Every test that checks what the app does is a unit test that runs on the JVM, without a device: `./gradlew
testDebugUnitTest` runs all of them, and CI runs the same command. What needs a device (the
player on a real decoder, layouts on five screen sizes, a real server) has a checklist instead:
see [What the unit tests do not cover](#what-the-unit-tests-do-not-cover).

## The kinds of test

| Kind | Where | What it checks |
|---|---|---|
| Pure Kotlin | `domain/src/test/`, `application/src/test/` | The rules and the models, with no Android: `PlayStateTest`, `SyncMergeTest`, `TicksTest`, `IdsTest`, `ReloadPolicyTest` |
| Architecture rules | `architecture/src/test/` | R1 to R17 of [rules.md](architecture/rules.md), read from the source tree with Konsist and plain file reading. Nothing is compiled first |
| Generated docs | `GeneratedDocsTest` in `architecture` | That `docs/architecture/modules.md`, `rules.md` and `model/modules.generated.c4` match the build files and the rule tests |
| Contract suites | `testing/src/main/` | What every implementation of a server port must do: `ItemContract`, `ItemListContract`, `ItemExtrasContract`, `ServerInfoContract` (the catalog's roles, #967), `UserDataContract`, `PlaybackContract` (all extend `MediaServerContract`) and `ServerConnectorContract` |
| Adapter tests | `server/jellyfin/src/test/` | The Jellyfin adapter: the contract suites, the requests it sends, the mapping of SDK types (`ItemMapperGoldenTest`) |
| Room and repository | `data/src/test/` | The stores and `MediaRepositoryImpl` on an in-memory database, and the migrations (`ServerDatabaseMigrationTest`) |
| ViewModel and logic | `modes/film`, `setup`, `settings`, `core`, `downloads`, `player/local`, `player/cast`, `ratings/mdblist` | ViewModels with fakes, the download queue, the playlist, both player engines' own logic |
| Screens | `app/phone/src/test/`, `app/tv/src/test/` | Compose layouts and TV focus on the JVM, with Robolectric and the Compose test rule |
| Guard tests | `PlayerInvariantsTest` in `player/local` | Source lines that a compile cannot catch and a device test is easy to skip |

`player/core` has no tests.

### The libraries

- **JUnit.** Everything runs on the JUnit Platform (the root `build.gradle.kts` sets it for every
  Android module). Most tests without Android classes use JUnit Jupiter. A test that needs
  Robolectric is JUnit 4 and runs
  through the Vintage engine, because Robolectric has no Jupiter runner. The contract suites in
  `testing` are JUnit 4 for the same reason.
- **Assertions** are `kotlin.test` (`assertEquals`, `assertIs`, `assertFailsWith`). They work
  under both engines.
- **Coroutines** are tested with `kotlinx-coroutines-test`: `runTest`, and `setMain` with a test
  dispatcher for a ViewModel. A ViewModel that moves work to `Dispatchers.Default` or `IO` takes
  that dispatcher as a constructor parameter (the `@Inject` constructor passes the real one, as in
  `MovieViewModel` or `DownloadsViewModel`), and its test passes the same `StandardTestDispatcher`
  it gives `setMain` and `runTest`, then `advanceUntilIdle()`. No `runBlocking` with real
  dispatchers: work a test did not wait for comes back to `Main` after `resetMain()` and fails
  the next `runTest` in the module ("uncaught exceptions before the test started"), a test that
  is innocent
  ([#809](https://github.com/midasvo/ephyra/issues/809)).
- **Robolectric** where a test needs Android classes: `data`, `core`, `downloads`, `setup`,
  `server/jellyfin`, `player/local` and both apps.
- **Room** tests use `inMemoryDatabaseBuilder`; the migration test uses `MigrationTestHelper`.
  `SyncWorkerRunTest` in `core` uses WorkManager's test artifact.
- **Konsist** only in `architecture`.

There is no mocking library and no Turbine. A test double is a fake written by hand:
`FakeMediaServer`, `FakeServerConnector` and `FakeResumeThresholdsStore` in `testing`, and small
ones next to the tests that use them (`FakeServerSession`, `FakeConnection`, `FakeMdbList`). A
fake built with `java.lang.reflect.Proxy` uses the helpers in `ProxyCalls.kt`, which handle the
id value classes.

### The contract suites and the fake

`testing` is a pure Kotlin module that other modules take with
`testImplementation(projects.testing)` only (rule R8, `TestingModuleTest`). It holds the fakes,
the suites, and `ContractLibrary`: the small library every suite expects a server to present.

Two implementations run the suites:

| Who | Classes | How |
|---|---|---|
| The fake | `FakeMediaServerContractTest.kt` in `testing/src/test/` | `FakeMediaServer` and `FakeServerConnector`, with and without the optional server features (the `WithoutFeatures` classes). The suites are what keep the fake honest |
| The Jellyfin adapter | `JellyfinContractTests.kt` in `server/jellyfin/src/test/.../contract/` | `JellyfinMediaServer` and `JellyfinServerConnector` over a local stand-in HTTP server (`ContractServer`) that replays recordings from `server/jellyfin/src/test/resources/contract/` |

A request without a recording fails the test. With the environment variable
`EPHYRA_TEST_SERVER` set to the address of the Docker test server, the adapter's suites talk to
that server instead, and `-Dcontract.record=true` writes its answers as the new recordings (see
the comment on `ContractServer`, and [setup-and-servers](areas/setup-and-servers.md)). CI only
replays. `ContractRecordingsTest` checks that no recording holds a token or a host name.

### The architecture rules and their baseline

Each rule is a test named after its number, such as `R8 the testing module is only a test
dependency`. When one fails, fix the code. `architecture/baseline/` holds allowlists read by
`Baseline`: the check is exact, so a new violation fails and a listed entry that no longer
violates fails too. Today the one file, `uuid-imports.txt`, has no entries.

A new module, a module dependency or a rule changes the generated docs. Write them again and
commit the result:

```bash
EPHYRA_WRITE_GENERATED=1 ./gradlew :architecture:test --rerun --tests '*GeneratedDocsTest*'
```

### What does not exist

- **No instrumented tests of what the app does.** No module has an `androidTest` source set, and
  no test in CI runs on a device or an emulator.
- **No screenshot tests.**
- `baselineprofile` and `baselineprofile-tv` are the exception: Macrobenchmark modules whose
  classes (`StartupBenchmark`, `BaselineProfileGenerator`) are instrumented tests. They record the baseline
  profiles and measure startup on a connected device, by hand: [performance.md](performance.md)
  and [`baselineprofile/README.md`](../baselineprofile/README.md). They are not part of
  `testDebugUnitTest`.

### Rough numbers

Counted as `@Test` annotations in tracked files, so about: 2,480 in total. A contract suite runs
once per subclass, so more tests run than are written.

| Module | About | Module | About |
|---|---:|---|---:|
| `player/local` | 490 | `downloads` | 155 |
| `app/phone` | 300 | `data` | 150 |
| `app/tv` | 290 | `setup` | 100 |
| `server/jellyfin` | 260 | `domain` | 90 |
| `modes/film` | 190 | `player/cast` | 80 |
| `core` | 170 | `testing` (mostly the suites) | 70 |
| `architecture` | 40 | `application` | 40 |
| `settings` | 25 | `ratings/mdblist` | 20 |

## Which test for which change

| Change | Write or run |
|---|---|
| A user-data rule (played, favourite, resume position, the sync's merge) | The rule goes into `PlayState`, with a test in `PlayStateTest`, `PlayStopTest` or `SyncMergeTest`. R14 (`PlayStateRulesTest`) fails if the rule is written elsewhere. See [user-data](areas/user-data.md) |
| A new method on a server port | A test in the port's contract suite, an implementation in `FakeMediaServer` and in the Jellyfin adapter, and a recording for the adapter's run. Both runs must pass |
| A new port | The interface in `domain/.../domain/ports/`, a fake in `testing`, and a suite there when more than one implementation is expected |
| A new adapter for a server type | Subclass every suite (the seven server suites of the table above) as `JellyfinContractTests.kt` does. Add mapping tests of its own for what the suites cannot see (the exact request, odd answers) |
| A store (Room behind a port of `application`) | A Robolectric test in `data/src/test/` on an in-memory database, as `RoomServerStoreTest` and `PendingUserDataSyncTest` do |
| A new Room schema version | `ServerDatabaseMigrationTest`: its target is the highest schema in `data/schemas/`, so a version without a migration fails it. Add the rows the new version must keep |
| A ViewModel | A test in the same module with `FakeMediaServer` or a small fake, `setMain` and `runTest`: `SearchViewModelTest`, `HomeViewModelUserSwitchTest` |
| A phone or TV screen | A Robolectric test of the stateless layout with the Compose test rule: `HomeScreenLayoutTest`, `MainScreenTest`. For TV, test where focus lands |
| The player | Unit tests in `player/local` for the logic (`PlaylistManagerQueueTest`, `PlaybackReporterTest`, the `Mpv*Test` classes), then [mpv-checklist.md](mpv-checklist.md) on both backends. A line that must never come back gets a test in `PlayerInvariantsTest` |
| Cast | `player/cast/src/testProprietary/`; compile both flavours |
| A string | Follow the `translate-strings` skill, then `python3 .claude/skills/translate-strings/scripts/check-translations.py` |
| A doc, a rename or a move | `python3 .claude/skills/start-work/scripts/check-docs.py`. When the change touches a file an area guide names, `check-guides-follow.py` in the same folder |

## What the unit tests do not cover

| What | How it is checked |
|---|---|
| Playback on a real decoder, on ExoPlayer and on mpv | [mpv-checklist.md](mpv-checklist.md), for every PR that touches `player/local`, the player screens or the device profile |
| The app against a real Jellyfin server | The throwaway test server: `tools/test-server/setup.sh`, described in [dev-environment.md](dev-environment.md). It has generated media for the awkward cases and test users |
| The app on a phone, tablet, foldable and Android TV | The emulators of `scripts/avds.sh` ([dev-environment.md](dev-environment.md)) |
| Tablet and foldable layouts | [tablet/6-test-matrix.md](tablet/6-test-matrix.md): five sizes, for a PR that touches a screen in its matrix |
| Track selection across episodes | [track-test-series.md](track-test-series.md) |
| Decoders, HDR, refresh rates, audio passthrough, performance | A real device. The emulator plays everything in software |
| Cast to a real receiver | A real receiver. A debug build of the `proprietary` flavour has a stand-in (`FakeCast.kt`) for the states of the UI only |

The area guides in [areas/](areas/) have a "How to test it" section with the steps for their
area.

## Commands, fastest first

```bash
# A second, no build: the docs against the tree, and the translations
python3 .claude/skills/start-work/scripts/check-docs.py
python3 .claude/skills/translate-strings/scripts/check-translations.py

# Seconds, no Android: the pure Kotlin modules and the architecture rules
./gradlew :domain:test :application:test :architecture:test

# The fake against the contract suites
./gradlew :testing:test

# Formatting
./gradlew ktfmtCheck

# One module
./gradlew :data:testDebugUnitTest
./gradlew :core:testLibreDebugUnitTest
./gradlew :player:cast:testProprietaryDebugUnitTest

# One class, or the classes that match a pattern
./gradlew :domain:test --tests '*PlayStateTest'
./gradlew :player:local:testDebugUnitTest --tests '*PlayerInvariantsTest'
./gradlew :server:jellyfin:testDebugUnitTest --tests '*ContractTest'

# Everything: every module and flavour
./gradlew testDebugUnitTest
```

A module with flavours (`app/phone`, `app/tv`, `core`, `player/cast`) names the flavour in the
task: `testLibreDebugUnitTest`. The plain `testDebugUnitTest` exists there too and runs every
flavour. The pure Kotlin modules have `test`, and `testDebugUnitTest` as a second name for it, so
the one command at the end reaches them.

The translation check compares with upstream's strings: it needs a git remote named `upstream`
that has been fetched.

## What CI runs

| Workflow | When | What |
|---|---|---|
| `build.yaml` | Pull requests and pushes to `main`, unless only docs-like paths changed | `check-translations.py`; a debug build of every app variant; `./gradlew testDebugUnitTest --continue`, with a table of the test counts per module in the run's summary and the reports uploaded when a test fails |
| `format.yaml` | When a Kotlin file or `gradle/libs.versions.toml` changes | `./gradlew ktfmtCheck` |
| `docs.yml` | Every pull request and every push to `main` | `check-docs.py`; on a pull request also `check-guides-follow.py`, and `GeneratedDocsTest` when the pull request changes a generated file |

The build jobs do not compile the test sources, so a test that stops compiling shows up only in
the unit test job. A pull request that changes only docs runs no unit tests. `main` has no
required checks: wait for the checks yourself before merging.
