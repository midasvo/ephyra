package dev.jdtech.jellyfin.architecture

import com.lemonappdev.konsist.api.Konsist
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R21 (#967). A port says what its callers use, so it stays small: no interface in the server ports
 * of `domain` (`domain/.../domain/ports/`) or in `application` (the repository's roles, the stores,
 * the settings ports) declares more than [MAX_MEMBERS] functions and properties. A port that needs
 * more is two roles: split it, as #967 split `MediaRepository` (52 members) into `CurrentAccount`,
 * `ItemCatalog`, `ItemLists`, `ItemExtras`, `UserDataUpdates`, `SeriesTrackPreferences`,
 * `PlaybackMedia` and `PlaybackReports`, and the server's catalog port (25) into `ItemPort`,
 * `ItemListPort`, `ItemExtrasPort` and `ServerInfoPort`.
 *
 * What is counted is what the interface declares itself: its functions and properties, with or
 * without a default body, and not those of a companion object or a nested type. A port that
 * inherits from other interfaces is counted with what it inherits from them, so a union of roles
 * fails like the wide port it replaces. Interfaces are told apart by their fully qualified name, a
 * parent is looked up among the interfaces of all of `domain` and `application`, by its written
 * name, its enclosing declarations, the file's imports (aliases included), its package, and last a
 * wildcard import, as Kotlin binds it: a parent from anywhere else (a Kotlin or JDK interface, as
 * R19 leaves no other) is not counted. A parent that can still be more than one interface fails the
 * rule rather than be guessed. An interface inherited along two paths counts once.
 *
 * A port over the limit would be listed in `architecture/baseline/wide-ports.txt` with its issue
 * (none is since #967), and the check is exact: a port that is split fails until its line is
 * deleted.
 */
class PortSizeTest {

    @Test
    fun `R21 no port declares more than fifteen functions and properties`() {
        val files =
            ProjectTree.productionKotlinFiles.filterKeys { path ->
                PURE_MODULES.any { path.startsWith(it) }
            }
        assertTrue(files.isNotEmpty(), "No file found under $PURE_MODULES")
        val sizes = sizesOf(files) { path -> PORT_FOLDERS.any { path.startsWith(it) } }
        assertTrue(
            "dev.jdtech.jellyfin.repository.ItemCatalog" in sizes &&
                "dev.jdtech.jellyfin.domain.ports.ItemListPort" in sizes &&
                "dev.jdtech.jellyfin.models.Item" !in sizes,
            "R21 does not see ItemCatalog and ItemListPort, so it reads the wrong files: $sizes",
        )
        val wide = sizes.filterValues { it > MAX_MEMBERS }
        BASELINE.assertExactly(
            wide.keys,
            unlisted = {
                "The port $it declares ${wide.getValue(it)} functions and properties, more than " +
                    "$MAX_MEMBERS (R21). Split it into roles a caller can be said to use, as " +
                    "#967 split MediaRepository (docs/areas/architecture.md, recipe 1)."
            },
        )
    }

    /**
     * The scanner counts what the interface declares and what it inherits, and nothing else; two
     * interfaces of the same simple name are two ports, and a parent from outside is not counted.
     */
    @Test
    fun `R21 counts the members an interface declares and inherits`() {
        val text =
            """
            package dev.jdtech.jellyfin.repository

            import dev.jdtech.jellyfin.repository.Small as Aliased
            import dev.jdtech.jellyfin.other.Wide

            interface Small {
                val name: String

                fun one()

                suspend fun two(id: String): Int = 2

                companion object {
                    const val LIMIT = 3

                    fun helper() = Unit
                }

                interface Nested {
                    fun nested()
                }
            }

            fun interface Single {
                fun get(): Int
            }

            interface Union : Small, Single, Comparable<Int> {
                fun own()

                interface Nested {
                    fun one()

                    fun two()
                }
            }

            interface Left : Small {
                fun left()
            }

            interface Right : Small {
                fun right()
            }

            // Small's three members count once, though both sides inherit them.
            interface Diamond : Left, Right

            interface ViaAlias : Aliased {
                fun own()
            }

            interface ViaImport : Wide

            interface Base {
                fun only()
            }

            interface Outer {
                interface Base {
                    fun a()

                    fun b()
                }

                // Kotlin binds the enclosing Outer.Base, not the package's Base.
                interface Child : Base
            }

            interface Closeable {
                fun x()

                fun y()
            }

            // java.io.Closeable is outside: not counted, and not this package's Closeable.
            interface External : java.io.Closeable

            class NotAPort {
                fun a() = Unit
            }
            """
                .trimIndent()
        val other =
            """
            package dev.jdtech.jellyfin.other

            // The same simple name in another package is another port.
            interface Small {
                fun a()

                fun b()
            }

            interface Wide : Small {
                fun c()
            }
            """
                .trimIndent()
        // Under build/, which no rule reads.
        val dir = ProjectTree.root.resolve("architecture/build/r21-scanner").apply { mkdirs() }
        val file = dir.resolve("Fixture.kt").apply { writeText(text) }
        val otherFile = dir.resolve("Other.kt").apply { writeText(other) }
        val third =
            """
            package dev.jdtech.jellyfin.third

            import dev.jdtech.jellyfin.other.*
            import dev.jdtech.jellyfin.repository.Outer

            // other.Small through the wildcard, though repository.Small has the same name.
            interface ViaWildcard : Small

            // Outer.Base through the import of Outer.
            interface ViaQualifier : Outer.Base
            """
                .trimIndent()
        val thirdFile = dir.resolve("Third.kt").apply { writeText(third) }
        assertEquals(
            mapOf(
                "$P.Small" to 3,
                "$P.Small.Nested" to 1,
                "$P.Single" to 1,
                "$P.Union" to 5,
                "$P.Union.Nested" to 2,
                "$P.Left" to 4,
                "$P.Right" to 4,
                "$P.Diamond" to 5,
                "$P.ViaAlias" to 4,
                "$P.ViaImport" to 3,
                "$P.Base" to 1,
                "$P.Outer" to 0,
                "$P.Outer.Base" to 2,
                "$P.Outer.Child" to 2,
                "$P.Closeable" to 2,
                "$P.External" to 0,
                "dev.jdtech.jellyfin.other.Small" to 2,
                "dev.jdtech.jellyfin.other.Wide" to 3,
                "dev.jdtech.jellyfin.third.ViaWildcard" to 2,
                "dev.jdtech.jellyfin.third.ViaQualifier" to 2,
            ),
            sizesOf(mapOf("fixture" to file, "other" to otherFile, "third" to thirdFile)) { true },
        )
    }

    private companion object {
        const val MAX_MEMBERS = 15

        private val IMPORT =
            Regex("""^import\s+([\w.`]+)(?:\s+as\s+(\w+))?""", RegexOption.MULTILINE)

        /**
         * The interface of [known] that [written], a parent named in [child], is, as Kotlin binds
         * it: a qualified name as written (or relative to [scopes]); a simple name in the enclosing
         * declarations, then an explicit import (aliases included), then the package, and last, for
         * a wildcard import, the one interface of that simple name. A name that points outside
         * [known] (a Kotlin or JDK interface) is null: it is not counted.
         */
        fun resolve(
            written: String,
            scopes: List<String>,
            pkg: String,
            imports: Map<String, String>,
            wildcards: List<String>,
            known: Set<String>,
            child: String,
        ): String? {
            if ('.' in written) {
                // `Outer.Base`: the leading name is bound like a simple one, the rest follows it.
                val head = written.substringBefore('.')
                val rest = written.substringAfter('.')
                return (listOf(written) +
                        scopes.map { "$it.$written" } +
                        listOfNotNull(imports[head]?.let { "$it.$rest" }) +
                        wildcards.map { "$it.$written" })
                    .firstOrNull { it in known }
            }
            val nested = scopes.filter { it != pkg }.map { "$it.$written" }
            nested
                .firstOrNull { it in known }
                ?.let {
                    return it
                }
            imports[written]?.let {
                return it.takeIf { it in known }
            }
            "$pkg.$written"
                .takeIf { it in known }
                ?.let {
                    return it
                }
            // A wildcard import; anything else (Kotlin's default imports) is outside.
            val found = wildcards.map { "$it.$written" }.filter { it in known }.distinct()
            assertTrue(found.size <= 1, "R21 cannot tell which $written $child extends: $found")
            return found.singleOrNull()
        }

        private val WILDCARD = Regex("""^import\s+([\w.`]+)\.\*""", RegexOption.MULTILINE)

        /** The packages (or declarations) a file imports with `.*`. */
        fun wildcardsOf(text: String): List<String> =
            WILDCARD.findAll(text).map { it.groupValues[1].replace("`", "") }.toList()

        /** The names a file can write a type by, to the type: its imports, aliases included. */
        fun importsOf(text: String): Map<String, String> =
            IMPORT.findAll(text).associate { match ->
                val name = match.groupValues[1].replace("`", "")
                (match.groupValues[2].ifEmpty { name.substringAfterLast('.') }) to name
            }

        val PORT_FOLDERS =
            listOf("domain/src/main/kotlin/dev/jdtech/jellyfin/domain/ports/", "application/src/")

        /** Where a port's parents can be: R19 keeps the rest out of these two modules. */
        val PURE_MODULES = listOf("domain/src/main/", "application/src/main/")

        val BASELINE = Baseline("wide-ports.txt")

        const val P = "dev.jdtech.jellyfin.repository"

        /**
         * Fully qualified interface name to the number of functions and properties it declares,
         * plus those of the interfaces of [files] it extends, for the interfaces of the files whose
         * path [reported] accepts.
         */
        fun sizesOf(files: Map<String, File>, reported: (String) -> Boolean): Map<String, Int> {
            // Konsist resolves the paths against the root it finds itself.
            val konsistRoot = File(Konsist.projectRootPath)
            val paths = files.entries.associate { (path, file) -> file.canonicalPath to path }
            val interfaces =
                Konsist.scopeFromFiles(files.values.map { it.relativeTo(konsistRoot).path })
                    .interfaces(includeNested = true)
            val byName = interfaces.groupBy { checkNotNull(it.fullyQualifiedName) { it.name } }
            val twice = byName.filterValues { it.size > 1 }.keys
            assertTrue(twice.isEmpty(), "Two interfaces have the same name: $twice")
            val own = byName.mapValues { (_, declarations) ->
                val declaration = declarations.single()
                declaration.functions(includeNested = false, includeLocal = false).size +
                    declaration.properties(includeNested = false).size
            }
            val parents = byName.mapValues { (name, declarations) ->
                val declaration = declarations.single()
                val text = File(declaration.containingFile.path).readText()
                val imports = importsOf(text)
                val wildcards = wildcardsOf(text)
                val pkg = declaration.packagee?.name.orEmpty()
                // The declarations that enclose this one, innermost first, then its package.
                val scopes =
                    generateSequence(name.substringBeforeLast('.', "")) { scope ->
                            scope.substringBeforeLast('.', "").takeIf { scope != pkg }
                        }
                        .filter { it.length >= pkg.length }
                        .toList()
                declaration.parents().mapNotNull { parent ->
                    val written = parent.name.substringBefore('<').trim()
                    resolve(written, scopes, pkg, imports, wildcards, own.keys, name)
                }
            }

            /** [name] and every interface it inherits from, each once (a diamond counts once). */
            fun lineage(name: String, seen: MutableSet<String> = mutableSetOf()): Set<String> {
                if (seen.add(name)) parents.getValue(name).forEach { lineage(it, seen) }
                return seen
            }

            fun total(name: String): Int = lineage(name).sumOf { own.getValue(it) }

            return interfaces
                .filter { reported(paths[File(it.containingFile.path).canonicalPath] ?: "") }
                .associate { declaration ->
                    val name = checkNotNull(declaration.fullyQualifiedName)
                    name to total(name)
                }
        }
    }
}
