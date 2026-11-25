package dev.jdtech.jellyfin.player.local.domain

import android.content.ContextWrapper
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.Chapter
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.repository.roomMediaRepository
import dev.jdtech.jellyfin.testing.ContractLibrary
import dev.jdtech.jellyfin.testing.FakeLibrary
import dev.jdtech.jellyfin.testing.FakeMediaServer
import dev.jdtech.jellyfin.testing.FakeResumeThresholdsStore
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * CE (#594): the episode a show's Play starts is the next-up one, which the server sends without
 * trickplay and chapters. The player gets the episode as its own page has it.
 *
 * #771: over the real repository and a [FakeMediaServer], which sends next up the way Jellyfin
 * does, instead of a reflection fake of the repository. The contract library's next-up episode is
 * given chapters here; its trickplay is the library's.
 */
class PlaylistManagerNextUpTest {
    private val seriesId = ContractLibrary.series.id
    private val episodeId = ContractLibrary.nextUp.id

    private val library =
        object : FakeLibrary by ContractLibrary {
            override val items =
                ContractLibrary.items.map {
                    if (it.id == episodeId) {
                        (it as Episode).copy(
                            chapters = listOf(Chapter(0L, "Opening"), Chapter(60_000L, "Part One"))
                        )
                    } else it
                }
        }
    private val server = FakeMediaServer(library)

    @Test
    fun `the show's next-up episode starts with its trickplay and chapters`() {
        val manager = repository().let { playlistManager(it, it, it) }

        val item = runBlocking { manager.getInitialItem(seriesId, ItemKind.SERIES) }

        assertNotNull(item)
        assertEquals(episodeId, item!!.itemId)
        assertEquals(320, item.trickplayInfo?.width)
        assertEquals(listOf("Opening", "Part One"), item.chapters.map { it.name })
        // The sources fetched next to the episode are the ones used.
        assertEquals(1, server.calls.count { it == "sources" })
    }

    @Test
    fun `the next-up episode still plays when the full episode cannot be fetched`() {
        server.failWith(ServerException.HttpStatus(500), only = setOf("episode"))
        val manager = repository().let { playlistManager(it, it, it) }

        val item = runBlocking { manager.getInitialItem(seriesId, ItemKind.SERIES) }

        assertNotNull(item)
        assertEquals(episodeId, item!!.itemId)
        assertNull(item.trickplayInfo)
    }

    private fun repository() =
        roomMediaRepository(
            context = ContextWrapper(null),
            server = server,
            database = noDownloads(),
            // Every setting at its default: no current server, no Next Up limit.
            currentServer = { null },
            nextUpMaxDays = { 0 },
            resumeThresholds = FakeResumeThresholdsStore(),
        )

    /** No downloads: with no current server set, the repository only asks whether there are any. */
    private fun noDownloads(): ServerDatabaseDao =
        fakeProxy(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, _ ->
            when (method.kotlinName) {
                "observeHasSources" -> flowOf(false)
                else -> throw NotImplementedError("ServerDatabaseDao.${method.kotlinName}")
            }
        }
            as ServerDatabaseDao
}
