package dev.jdtech.jellyfin.architecture

import com.lemonappdev.konsist.api.Konsist
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R22 (#966). An adapter implements its own ports and composes nothing: no production file of an
 * adapter module (`data`, `server/jellyfin`, `ratings/mdblist`, `device`) names a port that another
 * adapter implements. `data` naming `MediaServer` or one of its parts, or `server/jellyfin` naming
 * a store of `data`, would be application logic in an adapter: what uses two adapters goes in
 * `application`, over their ports (as the repository does since #966; it was in `data`, over
 * `MediaServer` and the DAO).
 *
 * A port is an interface declared in `domain/.../domain/ports/` or in `application`, read with
 * Konsist; the adapter that implements it is the module with a class or object that has it among
 * its written parents. The parents are compared by simple name, as no two ports share one (R21
 * checks that). A port nobody implements, or one more than one adapter implements, belongs to no
 * single adapter and is left out. A file names a port when the name appears in its code, comments
 * removed.
 *
 * No exceptions since #1012 handed `server/jellyfin` a `DeviceDecoders` value of `domain` instead
 * of `DeviceCodecs`.
 */
class AdapterPortsTest {

    @Test
    fun `R22 no adapter file names a port of another adapter`() {
        val ports = portNames(ProjectTree.productionKotlinFiles.filterKeys(::isPortFile))
        val owners = ownersOf(ports)
        assertTrue(
            owners["MediaServer"] == "server/jellyfin" && owners["StoredItems"] == "data",
            "R22 does not see MediaServer in server/jellyfin or StoredItems in data, so it reads " +
                "the wrong files: $owners",
        )

        val found = ADAPTERS.flatMap { adapter ->
            adapterFiles(adapter).flatMap { (path, file) ->
                namedPorts(file.readText(), owners, adapter).map { path to it }
            }
        }
        assertTrue(
            found.isEmpty(),
            found.joinToString("\n") { (path, port) ->
                "$path names $port, a port of ${owners.getValue(port)}. An adapter implements its " +
                    "own ports and composes nothing (R22): what uses two adapters goes in " +
                    "application, over their ports (docs/areas/architecture.md, recipe 5)."
            },
        )
    }

    /** The scanner finds a port by name in code, not in a comment, and not as part of a name. */
    @Test
    fun `R22 finds a port named in code only`() {
        val owners = mapOf("MediaServer" to "server/jellyfin", "StoredItems" to "data")
        val text =
            """
            package dev.jdtech.jellyfin.repository

            // MediaServer is mentioned in a comment.
            /** And [MediaServer] in KDoc. */
            class A(private val items: StoredItems, private val server: MediaServer)

            class B(val other: MediaServerish)
            """
                .trimIndent()

        assertEquals(listOf("MediaServer"), namedPorts(text, owners, adapter = "data"))
        assertEquals(listOf("StoredItems"), namedPorts(text, owners, adapter = "server/jellyfin"))
    }

    private companion object {
        val ADAPTERS = listOf("data", "server/jellyfin", "ratings/mdblist", "device")

        fun isPortFile(path: String): Boolean =
            path.startsWith("domain/src/main/kotlin/dev/jdtech/jellyfin/domain/ports/") ||
                path.startsWith("application/src/main/")

        fun adapterFiles(adapter: String): Map<String, File> =
            ProjectTree.productionKotlinFiles.filterKeys { it.startsWith("$adapter/src/") }

        fun scope(files: Map<String, File>) =
            // Konsist resolves the paths against the root it finds itself.
            File(Konsist.projectRootPath).let { root ->
                Konsist.scopeFromFiles(files.values.map { it.relativeTo(root).path })
            }

        /** The simple names of the interfaces of [files]. */
        fun portNames(files: Map<String, File>): Set<String> =
            scope(files).interfaces(includeNested = true).map { it.name }.toSet()

        /** Port to the one adapter that implements it, for the [ports] exactly one implements. */
        fun ownersOf(ports: Set<String>): Map<String, String> {
            val implementers = mutableMapOf<String, MutableSet<String>>()
            for (adapter in ADAPTERS) {
                val declarations = scope(adapterFiles(adapter))
                val parents =
                    declarations.classes(includeNested = true).flatMap { it.parents() } +
                        declarations.objects(includeNested = true).flatMap { it.parents() }
                parents
                    .map { it.name.substringBefore('<').substringAfterLast('.').trim() }
                    .filter { it in ports }
                    .forEach { implementers.getOrPut(it) { mutableSetOf() } += adapter }
            }
            return implementers.filterValues { it.size == 1 }.mapValues { it.value.single() }
        }

        /** The ports of [owners] that are not [adapter]'s and that [source] names in its code. */
        fun namedPorts(source: String, owners: Map<String, String>, adapter: String): List<String> {
            val code = UuidBoundaryTest.withoutComments(source)
            return owners
                .filter { (port, owner) ->
                    owner != adapter && Regex("\\b$port\\b").containsMatchIn(code)
                }
                .keys
                .sorted()
        }
    }
}
