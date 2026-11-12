package dev.jdtech.jellyfin.utils

import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure helper functions extracted from [DownloaderImpl.sweepOrphans]:
 * [shouldDeleteOrphanFile], [shouldDeleteOrphanArtwork], [shouldPruneEmptyDir] and
 * [isUnderMountedRoot].
 */
class OrphanSweepHelpersTest {

    private val root = "/storage/emulated/0/Android/data/nl.midasvo.findroid.ce/files/downloads"
    private val now = 1_800_000_000_000L
    private val old = now - ORPHAN_MIN_AGE_MS
    private val fresh = now - ORPHAN_MIN_AGE_MS + 1

    private fun deletes(
        path: String,
        lastModified: Long = old,
        knownPaths: Set<String> = emptySet(),
        livePaths: Set<String> = emptySet(),
        roots: List<String> = listOf(root),
    ) =
        shouldDeleteOrphanFile(
            path = path,
            lastModified = lastModified,
            now = now,
            downloadRoots = roots,
            knownPaths = knownPaths,
            knownFinalPaths = knownPaths.map { it.removeSuffix(".download") }.toSet(),
            livePaths = livePaths,
        )

    // ── shouldDeleteOrphanFile: .download partials (rule unchanged) ───────────

    @Test
    fun `subtitle partial in knownPaths is kept`() {
        val path = "$root/Show.sourceId.uuid.srt.download"
        assertFalse(deletes(path, knownPaths = setOf(path)))
    }

    @Test
    fun `partial actively written by the engine is kept`() {
        val path = "$root/Show/S01/S01E01.mkv.download"
        assertFalse(deletes(path, livePaths = setOf(path)))
    }

    @Test
    fun `untracked partial with no live task is deleted`() {
        assertTrue(deletes("$root/Show/S01/junk.mkv.download"))
    }

    @Test
    fun `untracked partial is deleted whatever its age, as before`() {
        assertTrue(deletes("$root/Show/S01/junk.mkv.download", lastModified = now))
    }

    @Test
    fun `nested untracked partial is deleted`() {
        assertTrue(deletes("$root/Movie (2024)/Movie (2024).mkv.download"))
    }

    // ── shouldDeleteOrphanFile: finished files (#379) ─────────────────────────

    @Test
    fun `untracked finished file a day old is deleted`() {
        assertTrue(deletes("$root/Pioneer One/S01/S01E01 - Earthfall.mkv"))
    }

    @Test
    fun `tracked finished file is kept`() {
        val path = "$root/Pioneer One/S01/S01E01 - Earthfall.mkv"
        assertFalse(deletes(path, knownPaths = setOf(path)))
    }

    @Test
    fun `finished file whose row still has the download path is kept`() {
        // finalizeSource renames the file before it updates the row.
        val path = "$root/Movie (2024)/Movie (2024).mkv"
        assertFalse(deletes(path, knownPaths = setOf("$path.download")))
    }

    @Test
    fun `finished file of a running or just finished engine task is kept`() {
        val path = "$root/Movie (2024)/Movie (2024).mkv"
        assertFalse(deletes(path, livePaths = setOf("$path.download")))
        assertFalse(deletes(path, livePaths = setOf(path)))
    }

    @Test
    fun `untracked finished file younger than a day is kept`() {
        assertFalse(deletes("$root/Movie (2024)/Movie (2024).mkv", lastModified = fresh))
    }

    @Test
    fun `untracked finished file with no or a future modification time is kept`() {
        assertFalse(deletes("$root/Movie (2024)/Movie (2024).mkv", lastModified = 0L))
        assertFalse(deletes("$root/Movie (2024)/Movie (2024).mkv", lastModified = now + 1))
    }

    @Test
    fun `known path that differs only in case keeps the file`() {
        val path = "$root/Pioneer One/S01/S01E01 - Earthfall.mkv"
        assertFalse(deletes(path, knownPaths = setOf(path.uppercase())))
    }

    @Test
    fun `known path with a trailing separator keeps the file`() {
        val path = "$root/Pioneer One/S01/S01E01 - Earthfall.mkv"
        assertFalse(deletes(path, knownPaths = setOf("$path/")))
    }

    @Test
    fun `subtitle next to a tracked video is kept through its own row`() {
        val video = "$root/Pioneer One/S01/S01E01 - Earthfall.mkv"
        val subtitle = "$root/Earthfall.source.0b5c.srt"
        val known = setOf(video, subtitle)
        assertFalse(deletes(video, knownPaths = known))
        assertFalse(deletes(subtitle, knownPaths = known))
    }

    @Test
    fun `untracked old subtitle next to a tracked video is deleted`() {
        val video = "$root/Pioneer One/S01/S01E01 - Earthfall.mkv"
        assertTrue(deletes("$root/Earthfall.source.0b5c.srt", knownPaths = setOf(video)))
    }

    @Test
    fun `file outside a downloads root is never a candidate`() {
        val other = "/storage/emulated/0/Android/data/nl.midasvo.findroid.ce/files/mpv/mpv.conf"
        assertFalse(deletes(other))
        assertFalse(deletes("/storage/emulated/0/Android/data/x/files/junk.mkv.download"))
        assertFalse(deletes("$root/Movie.mkv", roots = emptyList()))
    }

    // ── shouldDeleteOrphanArtwork ─────────────────────────────────────────────

    private val itemId = UUID.fromString("158fe3fa-2f4f-73fa-c381-57483068d1c1")

    @Test
    fun `artwork of an item without a row is deleted once a day old`() {
        assertTrue(shouldDeleteOrphanArtwork(itemId.toString(), old, now, emptySet()))
    }

    @Test
    fun `artwork of a stored item is kept`() {
        assertFalse(shouldDeleteOrphanArtwork(itemId.toString(), old, now, setOf(itemId)))
    }

    @Test
    fun `artwork with a fresh, missing or future newest file is kept`() {
        assertFalse(shouldDeleteOrphanArtwork(itemId.toString(), fresh, now, emptySet()))
        assertFalse(shouldDeleteOrphanArtwork(itemId.toString(), 0L, now, emptySet()))
        assertFalse(shouldDeleteOrphanArtwork(itemId.toString(), now + 1, now, emptySet()))
    }

    @Test
    fun `folder whose name is not an item id is kept`() {
        assertFalse(shouldDeleteOrphanArtwork("home-cache", old, now, emptySet()))
        assertFalse(shouldDeleteOrphanArtwork("1-2-3-4-5", old, now, emptySet()))
    }

    // ── shouldPruneEmptyDir ───────────────────────────────────────────────────

    @Test
    fun `old empty folder under the root is pruned`() {
        assertTrue(shouldPruneEmptyDir("$root/Bluey/S01", true, old, now, listOf(root)))
    }

    @Test
    fun `root, non-empty and fresh folders are kept`() {
        assertFalse(shouldPruneEmptyDir(root, true, old, now, listOf(root)))
        assertFalse(shouldPruneEmptyDir("$root/Bluey/S01", false, old, now, listOf(root)))
        // A folder a new download has just made, before its file exists.
        assertFalse(shouldPruneEmptyDir("$root/Bluey/S01", true, fresh, now, listOf(root)))
    }

    // ── isUnderMountedRoot ────────────────────────────────────────────────────

    @Test
    fun `path under mounted root returns true`() {
        assertTrue(isUnderMountedRoot("$root/Show/S01/S01E01.mkv.download", listOf(root)))
    }

    @Test
    fun `path not under any mounted root returns false`() {
        assertFalse(
            isUnderMountedRoot(
                path = "/storage/sdcard1/Android/data/nl.midasvo.findroid.ce/files/downloads/Movie.mkv",
                mountedRoots = listOf(root),
            ),
        )
    }

    @Test
    fun `empty mounted roots list returns false`() {
        assertFalse(isUnderMountedRoot("$root/anything.mkv.download", emptyList()))
    }

    @Test
    fun `path equal to root but without trailing slash does not match`() {
        assertFalse(isUnderMountedRoot(root, listOf(root)))
    }
}
