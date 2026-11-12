package dev.jdtech.jellyfin.film.presentation.home

import dev.jdtech.jellyfin.database.ServerDatabaseDao
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline Home says nothing is downloaded only when the server has no downloaded item. */
class OfflineDownloadsTest {
    @Test
    fun `no movies and no shows is nothing downloaded`() {
        assertTrue(runBlocking { nothingDownloaded(dao(movies = 0, shows = 0), "server") })
    }

    @Test
    fun `a downloaded movie counts`() {
        assertFalse(runBlocking { nothingDownloaded(dao(movies = 1, shows = 0), "server") })
    }

    @Test
    fun `a downloaded episode's show counts`() {
        assertFalse(runBlocking { nothingDownloaded(dao(movies = 0, shows = 2), "server") })
    }

    @Test
    fun `the counts are for this server`() {
        val other = dao(movies = 3, shows = 3, serverId = "other")
        assertTrue(runBlocking { nothingDownloaded(other, "server") })
    }

    /** Answers the two count queries, for [serverId] only; any other call fails the test. */
    private fun dao(movies: Int, shows: Int, serverId: String = "server"): ServerDatabaseDao =
        Proxy.newProxyInstance(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, args ->
            val forServer = args?.getOrNull(0) == serverId
            when (method.name) {
                "countMoviesByServerId" -> if (forServer) movies else 0
                "countShowsByServerId" -> if (forServer) shows else 0
                else -> throw NotImplementedError("ServerDatabaseDao.${method.name}")
            }
        } as ServerDatabaseDao
}
