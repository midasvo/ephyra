package dev.jdtech.jellyfin.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The parts of the architecture docs that are read from the tree (#928): the modules with their
 * dependencies, from the build files, and the list of rules, from the tests of this module. Each
 * file is compared with what the tree says now, so a PR that adds a module or a dependency, or a
 * rule, fails until it regenerates them:
 * ```
 * EPHYRA_WRITE_GENERATED=1 ./gradlew :architecture:test --rerun --tests '*GeneratedDocsTest*'
 * ```
 *
 * (PowerShell: `$env:EPHYRA_WRITE_GENERATED=1; ./gradlew …`). The model files next to the generated
 * one (the `.c4` files in `docs/architecture/model/`) are written by hand and refer to its
 * elements.
 */
class GeneratedDocsTest {

    @Test fun `the module model is up to date`() = check(MODEL, ArchitectureModel.likeC4())

    @Test
    fun `the module page is up to date`() = check(MODULES_PAGE, ArchitectureModel.modulesPage())

    @Test fun `the rules page is up to date`() = check(RULES_PAGE, ArchitectureModel.rulesPage())

    @Test
    fun `every module has an entry`() {
        val modules = ArchitectureModel.modules()
        val problems = buildList {
            for (module in modules.filter { it !in ArchitectureModel.MODULES }) {
                add(
                    "No entry for the module $module. Add a line to ArchitectureModel.MODULES in " +
                        "architecture/src/test/kotlin/dev/jdtech/jellyfin/architecture/" +
                        "GeneratedDocsTest.kt: \"$module\" to Module(Layer.FEATURE, \"What it is, " +
                        "in one plain sentence.\"), with the layer one of " +
                        "${ArchitectureModel.Layer.entries.joinToString()}."
                )
            }
            for (module in ArchitectureModel.MODULES.keys.filter { it !in modules }) {
                add(
                    "ArchitectureModel.MODULES has an entry for $module, which is no module: remove it."
                )
            }
        }
        if (problems.isNotEmpty()) {
            fail(
                problems.joinToString("\n") +
                    "\nThen regenerate the docs and commit them: " +
                    "`$WRITE=1 ./gradlew :architecture:test --rerun --tests '*GeneratedDocsTest*'`"
            )
        }
    }

    @Test
    fun `module dependencies are read from every way to write them`() {
        val deps =
            ArchitectureModel.dependenciesIn(
                mapOf(
                    "a" to
                        """
                        dependencies {
                            implementation(projects.b)
                            api(project(":c"))
                            testImplementation(projects.d)
                            // implementation(projects.e)
                        }
                        """
                            .trimIndent(),
                    "b" to "",
                    "c" to "",
                    "d" to "",
                    "e" to "",
                )
            )
        assertEquals(
            listOf(
                ArchitectureModel.Dependency("a", "b", test = false),
                ArchitectureModel.Dependency("a", "c", test = false),
                ArchitectureModel.Dependency("a", "d", test = true),
            ),
            deps,
        )
    }

    private fun check(path: String, expected: String) {
        val file = ProjectTree.root.resolve(path)
        if (System.getenv(WRITE) == "1") {
            file.parentFile.mkdirs()
            file.writeText(expected)
            return
        }
        val actual = if (file.isFile) file.readText().replace("\r\n", "\n") else ""
        if (actual != expected) {
            fail(
                "$path is out of date. Regenerate it with " +
                    "`$WRITE=1 ./gradlew :architecture:test --rerun --tests '*GeneratedDocsTest*'` " +
                    "and commit the result. Don't edit it by hand."
            )
        }
    }

    private companion object {
        const val WRITE = "EPHYRA_WRITE_GENERATED"
        const val MODEL = "docs/architecture/model/modules.generated.c4"
        const val MODULES_PAGE = "docs/architecture/modules.md"
        const val RULES_PAGE = "docs/architecture/rules.md"
    }
}

/** Renders the modules and rules of the tree as a LikeC4 model and as Markdown. */
object ArchitectureModel {

    /**
     * Each module: its layer (how the model groups and colours it) and what it is, in one plain
     * sentence (its description in the model and the "What" column of modules.md). A new module
     * fails `every module has an entry` until it is added here.
     */
    val MODULES: Map<String, Module> =
        mapOf(
            "app/phone" to
                Module(
                    Layer.APP,
                    "The phone app: screens, navigation and layouts; with app/tv the composition root.",
                ),
            "app/tv" to
                Module(
                    Layer.APP,
                    "The Android TV app: its own screens in its ce package, next to upstream's (TV ADR 0001).",
                ),
            "modes/film" to
                Module(
                    Layer.FEATURE,
                    "The view models with their State and Action classes for home, library, search, the item screens and downloads.",
                ),
            "setup" to
                Module(Layer.FEATURE, "Server discovery, signing in and the onboarding flow."),
            "settings" to
                Module(
                    Layer.FEATURE,
                    "AppPreferences (over SharedPreferences), SettingsViewModel and the preference models; the screens are in the apps.",
                ),
            "player/local" to
                Module(
                    Layer.FEATURE,
                    "The player: PlayerViewModel and playback, on ExoPlayer or mpv behind PlayerEngine (in its engine package).",
                ),
            "player/cast" to
                Module(
                    Layer.FEATURE,
                    "Google Cast: the session and playback on a receiver; real in the proprietary flavour, stubs in libre.",
                ),
            "player/core" to
                Module(
                    Layer.FEATURE,
                    "The player models shared by the player and its callers: PlayerItem, Track, Trickplay.",
                ),
            "core" to
                Module(
                    Layer.FEATURE,
                    "Hilt modules, the server in use (CurrentMediaServer), its session keeper and reachability, the sync worker, DownloaderViewModel, MainViewModel and shared Composables.",
                ),
            "downloads" to
                Module(
                    Layer.FEATURE,
                    "The download engine (Downloader, DownloaderImpl, MediaDownloadEngine), DownloadQueue, smart downloads and their workers.",
                ),
            "application" to
                Module(
                    Layer.APPLICATION,
                    "Pure Kotlin: the repository's roles (ItemCatalog, ItemLists, PlaybackMedia, …) and their implementation MediaRepositoryImpl over the server's ports and the stores, the store ports (the roles of the server and download stores, PendingUserDataStore) and the event flows (DataChangeEvents, ReloadPolicy, …).",
                ),
            "domain" to
                Module(
                    Layer.DOMAIN,
                    "Pure Kotlin, no Android or SDK: the models (Item, Movie, ItemSource, …), the ids, PlayState, DownloadState, Ticks, ActiveServer and the server ports.",
                ),
            "data" to
                Module(
                    Layer.ADAPTER,
                    "Room: the database and the stores (Room*Store) behind the store ports, and the downloaded files (DownloadedFilesOnDisk); no SDK, and of Android only Room (R20).",
                ),
            "server/jellyfin" to
                Module(
                    Layer.ADAPTER,
                    "The Jellyfin adapter, the only module with the Jellyfin SDK: JellyfinApi, the mappers, the port implementations, the device profiles and its own Hilt module.",
                ),
            "ratings/mdblist" to
                Module(
                    Layer.ADAPTER,
                    "The MDBList adapter of the external ratings on the movie and show pages: its HTTP client and the ratings cache on disk, with its own Hilt module.",
                ),
            "device" to
                Module(
                    Layer.ADAPTER,
                    "The adapters over the device: the codec probe (DeviceCodecs, what the device profile is built from) and the connectivity check (NetworkConnectivity), with its own Hilt module.",
                ),
            "http" to
                Module(
                    Layer.SHARED,
                    "MediaHttpClient, the API's OkHttp client: provided by server/jellyfin, used by the player (ExoPlayer's media, mpv's subtitle files), so they share the API's connections.",
                ),
            "testing" to
                Module(
                    Layer.TOOLING,
                    "Test only (R8): FakeMediaServer, FakeServerConnector and the contract suites a server adapter passes.",
                ),
            "architecture" to
                Module(
                    Layer.TOOLING,
                    "The Konsist rules over the source tree, their allowlists in architecture/baseline/, and the generated architecture docs.",
                ),
            "baselineprofile" to
                Module(
                    Layer.TOOLING,
                    "Macrobenchmark: the baseline profile and startup benchmark of app/phone.",
                ),
            "baselineprofile-tv" to
                Module(
                    Layer.TOOLING,
                    "Macrobenchmark: the baseline profile and startup benchmark of app/tv.",
                ),
        )

    data class Module(val layer: Layer, val what: String)

    enum class Layer(val kind: String, val title: String) {
        APP("app", "Apps (composition root)"),
        FEATURE("feature", "Features"),
        APPLICATION("application", "Application"),
        DOMAIN("domain", "Domain"),
        ADAPTER("adapter", "Adapters"),
        /** A contract between an adapter and a feature that names a library type (#964). */
        SHARED("shared", "Shared contracts"),
        TOOLING("tooling", "Tests and tooling"),
    }

    data class Dependency(val from: String, val to: String, val test: Boolean)

    /** The module paths, without the root build file, sorted. */
    fun modules(): List<String> = ProjectTree.moduleBuildFiles.keys.filter { it != "." }.sorted()

    fun dependencies(): List<Dependency> =
        dependenciesIn(
            ProjectTree.moduleBuildFiles.filterKeys { it != "." }.mapValues { it.value.readText() }
        )

    /**
     * The dependencies between the modules of [buildFiles] (module path to build file text): one
     * per pair, production when any mention of the module is outside a test configuration.
     */
    fun dependenciesIn(buildFiles: Map<String, String>): List<Dependency> =
        buildFiles.keys.sorted().flatMap { from ->
            buildFiles.keys.sorted().mapNotNull { to ->
                if (from == to) return@mapNotNull null
                val configurations =
                    BuildFileDependencies.configurationsOf(
                        buildFiles.getValue(from),
                        BuildFileDependencies.markerOf(to),
                    )
                if (configurations.isEmpty()) null
                else Dependency(from, to, configurations.all(BuildFileDependencies::isTestOnly))
            }
        }

    /** The LikeC4 name of a module: `server/jellyfin` → `serverJellyfin`. */
    fun idOf(module: String): String =
        module
            .split('/', '-')
            .mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar(Char::uppercase) }
            .joinToString("")

    private fun layerOf(module: String): Layer = MODULES[module]?.layer ?: Layer.TOOLING

    private fun whatOf(module: String): String = MODULES[module]?.what.orEmpty()

    /**
     * The modules every module may use. Both overviews leave out the arrows into them, except
     * application → domain, so the graph shows the dependencies that say something.
     */
    private val SHARED = setOf("domain", "application")

    private fun inOverview(dep: Dependency): Boolean =
        dep.to !in SHARED || (dep.from == "application" && dep.to == "domain")

    private fun likeC4String(text: String): String = text.replace("\\", "\\\\").replace("'", "\\'")

    private fun header(comment: String) =
        "$comment Generated by architecture/…/GeneratedDocsTest.kt from the build files and the rule tests. Don't edit:\n" +
            "$comment EPHYRA_WRITE_GENERATED=1 ./gradlew :architecture:test --rerun --tests '*GeneratedDocsTest*'\n"

    fun likeC4(): String = buildString {
        append(header("//"))
        append("\nmodel {\n  extend ephyra {\n")
        for (module in modules()) {
            val layer = layerOf(module)
            append("    ${idOf(module)} = ${layer.kind} '$module' {\n")
            append("      description '${likeC4String(whatOf(module))}'\n")
            append("      link https://github.com/midasvo/ephyra/tree/main/$module 'Source'\n")
            append("    }\n")
        }
        append("\n")
        for (dep in dependencies()) {
            val arrow = if (dep.test) "-[test]->" else "->"
            append("    ${idOf(dep.from)} $arrow ${idOf(dep.to)}\n")
        }
        append("  }\n}\n")
    }

    fun modulesPage(): String = buildString {
        val deps = dependencies()
        append("# Modules\n\n")
        append(header("<!--").lines().filter { it.isNotEmpty() }.joinToString("\n") { "$it -->" })
        append("\n\n")
        append(
            "The Gradle modules and what each depends on, read from their `build.gradle.kts`. " +
                "The graph leaves out test-only dependencies (`testImplementation`, …), the " +
                "arrows of the apps, which depend on every module, and the arrows into `domain` " +
                "and `application`, which every module may use (`application` → `domain` " +
                "stays). The table has every dependency. " +
                "The rules that guard these arrows are in [rules.md](rules.md); the interactive " +
                "model is [model/](model/).\n\n"
        )
        append("```mermaid\nflowchart TD\n")
        for (layer in Layer.entries.filter { it != Layer.TOOLING }) {
            append("  subgraph ${layer.kind}[\"${layer.title}\"]\n")
            for (module in modules().filter { layerOf(it) == layer }) {
                append("    ${idOf(module)}[\"$module\"]\n")
            }
            append("  end\n")
        }
        val shown = modules().filter { layerOf(it) != Layer.TOOLING }.toSet()
        val arrows = deps.filter {
            !it.test &&
                it.from in shown &&
                it.to in shown &&
                layerOf(it.from) != Layer.APP &&
                inOverview(it)
        }
        for (dep in arrows) {
            append("  ${idOf(dep.from)} --> ${idOf(dep.to)}\n")
        }
        append("```\n\n")
        append("| Module | Layer | What | Depends on | Test-only |\n|---|---|---|---|---|\n")
        for (module in modules()) {
            val prod = deps.filter { it.from == module && !it.test }.joinToString { "`${it.to}`" }
            val test = deps.filter { it.from == module && it.test }.joinToString { "`${it.to}`" }
            val what = whatOf(module).replace("|", "\\|")
            append("| `$module` | ${layerOf(module).title} | $what | $prod | $test |\n")
        }
    }

    data class Rule(val id: String, val tests: List<String>, val files: List<String>) {
        /** What the rule says: the names of its tests, but those that test the scanner itself. */
        val statements: List<String>
            get() = tests.filterNot { name -> SCANNER_TESTS.any { name.startsWith("$it ") } }
    }

    /** The first word of a rule test that checks the scanner, not the tree: `R13 finds …`. */
    private val SCANNER_TESTS = listOf("finds", "counts", "skips")

    private val RULE_TEST = Regex("""fun `(R\d+) ([^`]*)`""")

    fun rules(): List<Rule> {
        val dir = "architecture/src/test/kotlin/dev/jdtech/jellyfin/architecture"
        val found = mutableMapOf<String, Pair<MutableSet<String>, MutableList<String>>>()
        ProjectTree.root
            .resolve(dir)
            .listFiles { f: File -> f.name.endsWith(".kt") }
            .orEmpty()
            .sortedBy { it.name }
            .forEach { file ->
                RULE_TEST.findAll(file.readText()).forEach { match ->
                    val (id, name) = match.destructured
                    val entry = found.getOrPut(id) { sortedSetOf<String>() to mutableListOf() }
                    entry.first += "$dir/${file.name}"
                    entry.second += name
                }
            }
        return found.entries
            .sortedBy { it.key.removePrefix("R").toInt() }
            .map { (id, value) -> Rule(id, value.second, value.first.toList()) }
    }

    fun rulesPage(): String = buildString {
        append("# Architecture rules\n\n")
        append(header("<!--").lines().filter { it.isNotEmpty() }.joinToString("\n") { "$it -->" })
        append("\n\n")
        append(
            "The Konsist rules in `architecture/`, read from the names of their tests. Run them " +
                "with `./gradlew :architecture:test` (also part of `testDebugUnitTest`). When one " +
                "fails, fix the code: the files in `architecture/baseline/` are allowlists, " +
                "not debt. A rule says what each of its tests checks; the tests of the scanner " +
                "itself (`finds …`, `counts …`, `skips …`) are left out. Why each rule exists is " +
                "in [README.md](README.md#the-rules) and the ADRs.\n\n"
        )
        append("| Rule | Says | Tests |\n|---|---|---|\n")
        for (rule in rules()) {
            val links = rule.files.joinToString { "[${it.substringAfterLast('/')}](../../$it)" }
            val says =
                rule.statements
                    .ifEmpty { rule.tests }
                    .joinToString("<br>") { it.replace("|", "\\|") }
            append("| ${rule.id} | $says | $links |\n")
        }
    }
}
