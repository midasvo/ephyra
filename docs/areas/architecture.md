# Architecture

Status: built (ADR 0001 and ADR 0002); Jellyfin is the only server adapter. As of 2026-11-25 (`main` at `07d28476c`, with the catalog port split by role in #967).

## Start here

- This guide is the "how do I make this change" part. What was built and why is [`docs/architecture/README.md`](../architecture/README.md); the rules are [`rules.md`](../architecture/rules.md), the modules [`modules.md`](../architecture/modules.md). Both are generated: never edit them by hand.
- Server ports are in `domain/.../domain/ports/` (`MediaServer` with `ServerSession`, the catalog by role since #967 (`ItemPort`, `ItemListPort`, `ItemExtrasPort`, `ServerInfoPort`), `UserDataPort`, `PlaybackPort`; `ServerConnector`; `UserDataSyncPort`). The Jellyfin adapter is `server/jellyfin`, bound in `JellyfinServerModule`.
- Store ports are in `application/.../repository/` (`PendingUserDataStore`, and since #967 the roles of the server store, `StoredServers`, `ServerAddresses`, `StoredUsers`, `UserLocks`, and of the download store, `DownloadList`, `DownloadedItemRows`, `DownloadSources`, `DownloadMediaStreams`, `DownloadSegmentsAndTrickplay`, `DownloadUserData`, `QueuedDownloads`), implemented over Room in `data/.../database/` and bound in `DatabaseModule`.
- The rules are tests in `architecture/` (`./gradlew :architecture:test`). When one fails, fix the code. Never add a line to `architecture/baseline/` to get past it.
- The fakes and the contract suites are in `testing/` (`FakeMediaServer`, `FakeServerConnector`, `ItemContract`, …). The fake and the Jellyfin adapter pass the same suites.
- Test fast: `./gradlew :domain:test :application:test :architecture:test` (seconds, no Android), then `./gradlew :testing:test`.

## What it does

The apps (`app/phone`, `app/tv`) are the composition root. Features take the roles of the repository they use (`ItemCatalog`, `ItemLists`, `PlaybackMedia`, …, #967) or a port. `domain` and `application` are pure Kotlin and hold the ports. Four adapter modules implement them: `server/jellyfin` (the only module with the Jellyfin SDK), `data` (Room and nothing else, R20), `ratings/mdblist` and `device` (what asks Android about the device). Only the apps depend on an adapter; Hilt wires the ports to them there. Gradle is the boundary and the rule tests hold what Gradle cannot say (ADR 0001, decision 6).

## Where the code is

| Path | What |
|---|---|
| [`domain/.../domain/ports/`](../../domain/src/main/kotlin/dev/jdtech/jellyfin/domain/ports/) | The server ports, `ActiveServer`, `DeviceCodecs` |
| [`http/.../http/`](../../http/src/main/kotlin/dev/jdtech/jellyfin/http/) | `MediaHttpClient`, the API's OkHttp client that ExoPlayer streams media over (mpv: subtitle files only), provided by `server/jellyfin` (#964). Its own module because it names a library type, which no port may |
| [`application/.../repository/`](../../application/src/main/kotlin/dev/jdtech/jellyfin/repository/) | The roles of the repository (`CurrentAccount`, `ItemCatalog`, `ItemLists`, `ItemExtras`, `UserDataUpdates`, `SeriesTrackPreferences`, `PlaybackMedia`, `PlaybackReports`), the store ports, the settings ports (`DownloadSettings`, `CurrentServerId`, `NetworkTimeouts`, …) |
| [`server/jellyfin/.../server/jellyfin/`](../../server/jellyfin/src/main/java/dev/jdtech/jellyfin/server/jellyfin/) | `JellyfinMediaServer`, `JellyfinCatalog`, `JellyfinUserData`, `JellyfinPlayback`, `JellyfinServerConnector`; `mappers/`; `SdkIds.kt`, `SdkEnums.kt`, `SdkErrors.kt`, `JellyfinCalls.kt` |
| [`server/jellyfin/.../di/JellyfinServerModule.kt`](../../server/jellyfin/src/main/java/dev/jdtech/jellyfin/server/jellyfin/di/JellyfinServerModule.kt) | What the adapter provides; the maps keyed by `ServerTypeKey` |
| [`core/.../di/ServerModule.kt`](../../core/src/main/java/dev/jdtech/jellyfin/di/ServerModule.kt), [`core/.../core/server/`](../../core/src/main/java/dev/jdtech/jellyfin/core/server/) | `CurrentMediaServer` (picks the adapter by type), `ServerConnectors`, `UnsupportedMediaServer` |
| [`data/.../database/`](../../data/src/main/java/dev/jdtech/jellyfin/database/) | `ServerDatabase`, `ServerDatabaseDao`, `RoomServerStore`, `RoomDownloadStore`, `RoomPendingUserDataStore`; the entities are in `data/.../models/` |
| [`data/.../data/di/`](../../data/src/main/java/dev/jdtech/jellyfin/data/di/) | `DatabaseModule` (Room and the stores), `MediaRepositoryModule` (the repository, bound as each of its roles) |
| [`device/`](../../device/src/main/java/dev/jdtech/jellyfin/) | The adapters over the device (#965): `DeviceCodecProbe` with `DeviceCodec` and `CodecHelpers` (`DeviceCodecs`, what the device profiles are built from), `NetworkConnectivityImpl` (`NetworkConnectivity`); bound in [`DeviceModule`](../../device/src/main/java/dev/jdtech/jellyfin/device/di/DeviceModule.kt) |
| [`core/.../di/AppPreferencesPorts.kt`](../../core/src/main/java/dev/jdtech/jellyfin/di/AppPreferencesPorts.kt) | The settings ports of `application`, over `AppPreferences` |
| [`testing/.../testing/`](../../testing/src/main/kotlin/dev/jdtech/jellyfin/testing/) | The fakes, `ContractLibrary`, the contract suites, `ProxyCalls.kt`, `FakeRoles.kt` (a Proxy fake of the roles a test needs) |
| [`server/jellyfin/.../contract/`](../../server/jellyfin/src/test/java/dev/jdtech/jellyfin/server/jellyfin/contract/) | The adapter against the suites (`JellyfinContractTests.kt`), `ContractServer`, `LiveServer`, `ContractRecordingsTest` |
| [`architecture/.../architecture/`](../../architecture/src/test/kotlin/dev/jdtech/jellyfin/architecture/) | The rule tests, `ProjectTree`, `BuildFileDependencies`, `Baseline`, and `GeneratedDocsTest.kt` with `ArchitectureModel` |
| [`docs/architecture/model/`](../architecture/model/) | The LikeC4 model: `modules.generated.c4` is generated, the other `.c4` files are written by hand |

## Recipe 1: an operation on a port, or a new port

An operation on an existing server port (say `ItemListPort`):

1. Declare it in the port's file in `domain/.../domain/ports/`. Use our own types only: models of `domain`, ids as `ItemId`, `UserId`, `ServerId`, `MediaSourceId`. Say in the KDoc how it fails. Forgotten: R5 fails on an Android or SDK import, R10 on an id typed as `String`.
2. Implement it in the adapter class (`JellyfinCatalog`, `JellyfinUserData`, `JellyfinPlayback`). Run the request inside the class's `call { … }`, which is `pinnedCall` from `JellyfinCalls.kt`: it holds the request to one `ActiveServer` and turns the SDK's failures into `ServerException` (`asServerException` in `SdkErrors.kt`). Map SDK types in `mappers/`, ids through `SdkIds.kt`, enums through `SdkEnums.kt`. Forgotten: the adapter does not compile. An SDK type in the port's signature fails R1.
3. Implement it in every other implementation. The compiler names them: `FakeMediaServer` in `testing`, `UnsupportedMediaServer` in `core` (it throws), `UnansweredServer` in `data`'s tests, and test doubles such as those in `CurrentMediaServerTest`. In `FakeMediaServer`, go through its private `call(name)` so that `failWith`, `stall` and the `calls` list work for the new operation.
4. Add tests to the port's contract suite in `testing` (`ItemContract`, `ItemListContract`, `ItemExtrasContract`, `ServerInfoContract`, `UserDataContract`, `PlaybackContract`, `ServerConnectorContract`): what it returns for `ContractLibrary`, and its line in the suite's failure tests (a failing, an unreachable and a stalling server). `./gradlew :testing:test` runs them on the fake.
5. Record the adapter's answers. `ContractServer` replays `server/jellyfin/src/test/resources/contract/` and fails a test on a request without a recording. With the Docker test server running (`test-server` skill): `EPHYRA_TEST_SERVER=http://localhost:8096 ./gradlew :server:jellyfin:testDebugUnitTest --tests '*Contract*' -Dcontract.record=true`, as the user `contract` (see [setup-and-servers](setup-and-servers.md), "How to test it"). What the test server does not hold is a `ContractLibrary.Part`, with a recording written by hand (`"handwritten": true`). `ContractRecordingsTest` fails when a recording holds an id, a token or an address of the machine.
6. If not every server can do it, add a field to `ServerCapabilities` and gate the call on it: no version checks (ADR 0001, decision 9). `FakeMediaServerContractTest.kt` runs the suites with every feature and with none (`NONE` there lists every field).
7. Call it from `MediaRepositoryImpl` (and declare it on the role it belongs to, recipe 6), or inject the port. A ViewModel never names an adapter class.

A new port:

1. A new interface in `domain/.../domain/ports/`, with failures as `ServerException`.
2. If it is part of one server, add it as a property of `MediaServer`. Then `JellyfinMediaServer` builds it with its `pin`, `CurrentMediaServer` gets a getter that delegates to `current()`, and `UnsupportedMediaServer`, `FakeMediaServer` and `UnansweredServer` get one too. The compiler names each.
3. If it stands apart (as `ServerConnector` and `UserDataSyncPorts` do), provide it in `JellyfinServerModule` with `@IntoMap` and `@ServerTypeKey(ServerType.JELLYFIN)`, and select by type in `core` (`ServerModule`, `ServerConnectors`). Forgotten: Hilt reports a missing binding when an app is built.
4. A new abstract suite in `testing` that extends `MediaServerContract`, a subclass for the fake in `FakeMediaServerContractTest.kt` and one for the adapter in `JellyfinContractTests.kt`. Nothing fails when the suite is missing: the reviewer checks it.
5. Optional: a `port` element in `components.c4` (see recipe 3, step 6).

## Recipe 2: a store port over Room

1. Declare the interface in `application/.../repository/`. Types of `domain` only. State of one user takes an `AccountKey` (`serverId`, `userId`), as `PendingUserDataStore` does. Forgotten: R5 fails on any import of Android, Room or something `data` declares. A wildcard import of `dev.jdtech.jellyfin.models.*` fails too, because `data`'s entities share that package: import the models by name.
2. Add the entity in `data/.../models/` and list it in `entities` of `ServerDatabase`; add the queries to `ServerDatabaseDao`.
3. If the schema changes, raise `version` in `ServerDatabase` and add an `AutoMigration(from, to)` (with a spec when a table or column goes). A migration written by hand, like `MIGRATION_6_7`, is also added in `DatabaseModule` and to `MIGRATIONS` in `ServerDatabaseMigrationTest`. The build writes the new schema to `data/schemas/` (`room3 { schemaDirectory(…) }` in `data/build.gradle.kts`): commit that file. Forgotten: in `ServerDatabaseMigrationTest`, `every exported schema migrates to the current one` and `a fresh database has the schema of a migrated one` fail. In the app a missing migration would reach `fallbackToDestructiveMigration` and wipe the database.
4. Never change an existing file in `data/schemas/`. An entity changed without a new version makes KSP rewrite the current JSON in place, and the build passes. `released schemas are unchanged` catches it for the released versions it pins; for the current one, read the diff.
5. Write `Room<Name>Store` in `data/.../database/` over `ServerDatabaseDao`. It maps rows to the models of `domain`; no entity leaves `data`.
6. Bind it with a `@Provides` in `DatabaseModule`. Forgotten: Hilt reports a missing binding when an app is built.
7. Test it against a real in-memory database, as `RoomServerStoreTest` does. A feature's test may build the real store too: `testImplementation(projects.data)` is allowed in every module.
8. Callers inject the port. Forgotten: R4 fails on an import of `ServerDatabaseDao` outside `data`, R9 on a production dependency on `data` outside the apps.

A port for a setting is not a store over Room: declare it in `application` and implement it in `AppPreferencesPorts.kt` in `core` (R17). `FakeResumeThresholdsStore` in `testing` is the example of a fake for one.

A PR with a Room migration that no review bot read gets an independent review before it merges (`AGENTS.md`).

## Recipe 3: a Gradle module

1. Write `<module>/build.gradle.kts`. Copy the nearest one: `application/build.gradle.kts` for pure Kotlin, `ratings/mdblist/build.gradle.kts` for an Android adapter with its own Hilt module. A JVM module registers a `testDebugUnitTest` task that depends on `test`, because CI runs `./gradlew testDebugUnitTest`.
2. Add `include(":<name>")` to `settings.gradle.kts`. Other modules then name it as `projects.<name>`.
3. Add the dependencies. The apps take it with `implementation`. An adapter is taken by the apps only; `testing` only with `testImplementation` (R8).
4. Add a line to `ArchitectureModel.MODULES` in [`GeneratedDocsTest.kt`](../../architecture/src/test/kotlin/dev/jdtech/jellyfin/architecture/GeneratedDocsTest.kt): the layer and one plain sentence. Forgotten: `every module has an entry` fails. The test finds modules by their build files, not by `settings.gradle.kts`.
5. Regenerate the docs and commit them: `EPHYRA_WRITE_GENERATED=1 ./gradlew :architecture:test --rerun --tests '*GeneratedDocsTest*'` (PowerShell: `$env:EPHYRA_WRITE_GENERATED=1; ./gradlew …`). It writes `modules.md`, `rules.md` and `modules.generated.c4`. Forgotten: `the module page is up to date` and `the module model is up to date` fail. A new dependency between two existing modules needs the same step.
6. The model by hand, in `docs/architecture/model/`:
   - `views.c4`: the `modules` view includes every module but the tooling modules it names. A new tooling module is added to that `exclude` line and to the `tooling` view.
   - A new layer needs a value in `ArchitectureModel.Layer` and an `element` kind of the same name in `context.c4`.
   - `components.c4`, only when the module has parts worth showing: each `component` and `port` needs a `link` to its source file or folder in this repository, and an arrow from A to B is only accepted when a source of A names B. `check-docs.py` fails on a missing link and on an arrow the code does not back. `npm run check-model` in `devsite/` validates the LikeC4 files.
7. For a new adapter, extend the rules that name modules by hand: a test in `DataDependencyTest` (R9, as for `ratings/mdblist` and `device`), the `ADAPTER_MODULES` of `AdapterDependencyTest` (R18) and the `MODULES` list in `AdapterSettingsTest` (R17). R7 guards `server/jellyfin` alone. Nothing fails when one is forgotten: the new module is just not checked.
8. By hand: the diagram and "What was built" in [`README.md`](../architecture/README.md), and `docs/areas.yaml` when the module is an area.

## Recipe 4: a rule, and the baseline files

1. A rule is a test in `architecture/src/test/kotlin/dev/jdtech/jellyfin/architecture/`. Its number is the start of the test's name: `R<n> <what it says>`. Take the next free number. Several tests may share a number (R5, R9 and R10 have two each).
2. Read the tree through `ProjectTree` (`productionKotlinFiles`, `moduleBuildFiles`) and build files through `BuildFileDependencies`. Do not use Konsist's own project scope: from the main checkout it would also parse every copy of the repository in the agents' worktrees.
3. Make the rule prove that it looks at something. The existing rules assert that the files were found and that the allowed place does match ("so the check looks for the wrong text"), and have a test of the scanner on sample text. A test whose name after the number starts with `finds`, `counts` or `skips` is such a scanner test and is left out of `rules.md`.
4. Write the failure message for the person who trips it: the file, what is wrong, what to use instead.
5. Regenerate with the command of recipe 3, step 5. `rules.md` is built from the test names. Forgotten: `the rules page is up to date` fails.
6. By hand: the table in [`README.md`](../architecture/README.md#the-rules), the range of rule numbers in `AGENTS.md`, and the `rules` of the areas the rule touches in `docs/areas.yaml`.

A baseline is a file `architecture/baseline/<name>` read by `Baseline`: one entry per line, `#` for comments. `assertExactly` fails on a violation that is not listed and on a listed entry that no longer violates, so a cleanup deletes its line. `architecture/baseline/uuid-imports.txt` is an allowlist, not debt: files that may name `java.util.UUID` for a random value the app makes itself. It lists none since #918. The others hold exceptions with an issue each: `pure-kotlin-imports.txt` and `pure-kotlin-libraries.txt` (R19) and `room-adapter-imports.txt` (R20), all three empty since #968 took paging out of `application` and `data`. A new rule starts at zero, or with lines for what truly cannot move in its PR, each with its issue.

## Recipe 5: a rule fails on your change

Read the message: it names the file and what to use instead. Fix the code. Do not add a baseline line, do not move the file to where the rule does not look, and do not loosen the rule in the same PR as the code it would catch. If you think the rule is wrong, say so in the PR and change the rule with its scanner test.

The ones you are most likely to trip:

- **R5** (`DomainPurityTest`): a file in `domain` or `application` imports `android.`, `androidx.`, the SDK, `dev.jdtech.jellyfin.database.` or a type `data` declares. Keep the type in its adapter and give the port a type of `domain`.
- **R19** (`PureKotlinTest`): a file in `domain` or `application` imports anything but `kotlin.`, `kotlinx.`, `java.`, `javax.crypto.` or `dev.jdtech.jellyfin.`, or their build file declares a library but `libs.kotlinx.*`. The same fix as R5; a contract that has to name a library type (an OkHttp client) goes in a small module of its own, as `http` does. Its allowlists are `architecture/baseline/pure-kotlin-*.txt`, empty since #968. A list a screen pages is a plain read in `application` (`ItemLists.getItemsPage`: items from an index, the total on request) and a `PagingSource` in the feature (`IndexedItemsPagingSource` in `modes/film`).
- **R20** (`RoomAdapterTest`): a file in `data` imports something of Android (`android.`, `androidx.`) but Room (`androidx.room3.`), SQLite (`androidx.sqlite.`) or `android.content.Context`. What asks the device (codecs, display, network) goes in `device`, behind a port of `domain` or `application`; anything else goes behind a port in the module that owns it. Its allowlist is `architecture/baseline/room-adapter-imports.txt`, empty since #968.
- **R10** (`StringIdsTest`, `UuidBoundaryTest`): a parameter or property named `serverId`, `currentServerId`, `mediaSourceId` or `sourceId` typed as `String` (or a list or set of them), or `java.util.UUID` anywhere outside `server/jellyfin`. Use the id classes and `toServerId()`, `toMediaSourceId()`, `asString()`; a random value comes from `kotlin.uuid.Uuid`.
- **R1, R2** (`SdkBoundaryTest`): a file outside `server/jellyfin` mentions the SDK's package or imports `JellyfinApi`. Add the operation to a port (recipe 1).
- **R9, R4** (`DataDependencyTest`, `SdkBoundaryTest`): a feature takes `data` with `implementation`, or imports `ServerDatabaseDao`. Use a store port (recipe 2); for a test, `testImplementation`.

- **R21** (`PortSizeTest`): a port declares more than fifteen functions and properties, its inherited ones included. Split it by role (recipe 6). `architecture/baseline/wide-ports.txt` would list a port still over, with its issue; it is empty since #967 split the server's catalog port, the last one.

Also common: R11 for a type named `Jellyfin…` outside the adapter (`NamesTest`), and R17 for `AppPreferences` in `data`, `server/jellyfin`, `ratings/mdblist`, `device` or `downloads` (`AdapterSettingsTest`), and R18 for an adapter that takes a feature module (`AdapterDependencyTest`).

## Recipe 6: an operation of the repository, and its roles

`MediaRepository` was split by role in #967; the name is gone, and `MediaRepositoryImpl` in `data` implements every role.

1. Find the role the operation belongs to, by who calls it: `CurrentAccount` (the server and user in use), `ItemCatalog` (one item, a show's seasons and episodes, next up, people), `ItemLists` (libraries, library items, favourites, search, Home's rows, downloads), `ItemExtras` (what a detail page shows besides the item), `UserDataUpdates` (favourite and played), `SeriesTrackPreferences`, `PlaybackMedia` (sources, segments, trickplay, chapter images), `PlaybackReports` (the play session and the reports). What the server can do is `CurrentServerCapabilities` in `domain`, not a role.
2. Declare it there, implement it in `MediaRepositoryImpl`. A role that would pass fifteen members fails R21: split it instead, and add the new role to `MediaRepositoryImpl`'s supertypes and a `@Provides` to `MediaRepositoryModule` (each role is bound to the one `@Singleton` instance; a second instance would lose the play sessions and the stored lists).
3. A caller takes only the roles it uses, in its constructor. A test builds a fake of those roles with `FakeRoles` (several) or `fakeRole` (one) from `testing`, or implements a small role directly (`PlaybackReporterTest`).

## How to test it

```bash
./gradlew :domain:test :application:test :architecture:test   # the pure modules and the rules: seconds, no Android
./gradlew :testing:test                                        # the fakes against the contract suites
./gradlew :server:jellyfin:testDebugUnitTest                   # the adapter: contract recordings, mappers, requests
./gradlew :data:testDebugUnitTest                              # Room stores, migrations, the repository
./gradlew :core:testLibreDebugUnitTest                         # CurrentMediaServer, the selectors, the sync
python3 .claude/skills/start-work/scripts/check-docs.py        # docs and model against the tree: about a second
```

- One rule: `./gradlew :architecture:test --tests '*DomainPurityTest*'`.
- The rules read source files and build files, so nothing is compiled first. The task runs again when a Kotlin or build file, a baseline or a generated doc changes.
- Hilt bindings are only checked when an app is built: `./gradlew :app:phone:assembleLibreDebug :app:tv:assembleLibreDebug`.
- The adapter against a live server: `EPHYRA_TEST_SERVER`, see recipe 1, step 5. Not in CI.
- A migration is also checked on an emulator, as the upgrade checks of ADR 0002 were (its answer Q5): the previous build with data, then the new build over it, and the database compared.

## Before a PR

```bash
./gradlew ktfmtCheck
./gradlew :domain:test :application:test :architecture:test :testing:test
./gradlew testDebugUnitTest
python3 .claude/skills/start-work/scripts/check-docs.py
```

- A new module, module dependency or rule: the generated docs are regenerated and committed.
- A new port or store: this guide, the guide of its area, and the `application` or `domain` sentence in `ArchitectureModel.MODULES` if it names the ports.
- The `Docs` workflow ([`.github/workflows/docs.yml`](../../.github/workflows/docs.yml)) runs `check-docs.py` on every pull request. For a pull request that changes a generated file it also runs `GeneratedDocsTest`, because a docs-only change does not run the unit tests.
- A change no user can notice goes on a `refactor/` branch, which publishes no beta.

## Decisions and history

- [ADR 0001](../architecture/adr/0001-ports-and-adapters.md): ports and adapters, one adapter module per server type, Gradle as the boundary and Konsist as the ratchet, capabilities as data. [ADR 0002](../architecture/adr/0002-hexagonal-ddd.md): `application`, the three stores, `AccountKey`, opaque ids, `MediaRepository` (split into roles by #967). [ADR 0003](../architecture/adr/0003-playback-and-download-use-cases.md) (proposed, [#970](https://github.com/midasvo/ephyra/issues/970)): use cases for playback and downloads, what a use case looks like here (a rule in `domain` or a service in `application`, with its scope, dispatcher and clock injected), the download state machine, and the order of the PRs.
- [#733](https://github.com/midasvo/ephyra/issues/733): the tracking issue. [#735](https://github.com/midasvo/ephyra/issues/735): the `architecture` module. [#771](https://github.com/midasvo/ephyra/issues/771): `testing` and the contract suites.
- [#758](https://github.com/midasvo/ephyra/issues/758): the adapter as its own module (R7). [#770](https://github.com/midasvo/ephyra/issues/770): servers have a type; adapters in maps by `ServerType`.
- [#896](https://github.com/midasvo/ephyra/issues/896): `application` (R5, R9). [#899](https://github.com/midasvo/ephyra/issues/899): the store ports, `data`'s own Hilt modules, R4 without exceptions, `ActiveServer` (R16).
- [#773](https://github.com/midasvo/ephyra/issues/773), [#902](https://github.com/midasvo/ephyra/issues/902): ids as value classes (R10). [#774](https://github.com/midasvo/ephyra/issues/774): the names (R11). [#918](https://github.com/midasvo/ephyra/issues/918): settings through ports (R17).
- [#928](https://github.com/midasvo/ephyra/issues/928): the generated docs, the LikeC4 model and `check-docs.py`.
- The numbered files `1-inventory.md` to `5-hexagonal-ddd.md` in `docs/architecture/` describe the app before the work. Where they differ from the code, the code is right.

## Pitfalls

- **R1 is a text check.** `SdkBoundaryTest` looks for the SDK's package name in the code, with comments removed (as R10 and R17 do). A type used by its full name without an import, or a string that holds the name, still fails.
- **A folder is not a package.** Moved files kept their package (ADR 0001, decision 8): the ports of `application` are in package `dev.jdtech.jellyfin.repository`, and `data`'s entities share `dev.jdtech.jellyfin.models` with the models of `domain`. That is why R5 for `application` compares names, not package prefixes.
- **No production Java outside the adapter.** The rules read Kotlin only; `SdkBoundaryTest` fails on a `.java` file elsewhere instead of letting it pass unread.
- **Not everything is per server type yet.** `ServerSession`, `MediaHttpClient` and `DeviceReportSource` are bound once, to Jellyfin, in `JellyfinServerModule`; `MediaServer`, `ServerConnector` and the sync ports are maps by `ServerType` ([README](../architecture/README.md), "Not done").
- **A fake built with a proxy and an id.** Ids are value classes, so a function that takes one has a mangled JVM name and a `String` parameter. A fake made with `java.lang.reflect.Proxy` that switches on the method name stops matching without an error: use `fakeProxy` from `ProxyCalls.kt` (#773).
- **The contract suites are JUnit 4**, because the adapter's subclasses need Robolectric (#699, `testing/build.gradle.kts`). The tests of `domain`, `application` and `architecture` are JUnit Jupiter.
- **The catalog's parts are one object in the Jellyfin adapter.** `JellyfinMediaServer`, `FakeMediaServer`, `UnsupportedMediaServer` and `UnansweredServer` hand one object as `items`, `itemLists`, `itemExtras` and `info`; another adapter may use four. A test double of `MediaServer` needs all four properties, and `CurrentMediaServerTest` compares each (#967).
- **A request pinned to a server.** A port a caller takes from `CurrentMediaServer` is held to the `ActiveServer` of that moment (`MediaServer.at`, #899). An adapter call that skips `pinnedCall` follows whatever server is switched to while it runs.
- **`architecture/` paths are relative to the root.** Agents work in worktrees under `.claude`, so every absolute path there contains `.claude`; a filter on the absolute path would drop every file (`ProjectTree`).
- **A green `check-docs.py` says that nothing named is gone**, not that a sentence is true (`AGENTS.md`). It does not check that an arrow in the model is the right one, only that the source of A names B.
- **A missing migration does not crash, it wipes** (`fallbackToDestructiveMigration` in `DatabaseModule`); an older build over a newer schema loses the login the same way (`docs/dev-environment.md`).
