package dev.jdtech.jellyfin.testing

import dev.jdtech.jellyfin.domain.ports.UserDataPort
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The contract of [UserDataPort] (#771): the marks it sets are what the catalog gives back. Each
 * test puts back what it changed, so a live server ends as it started.
 */
abstract class UserDataContract : MediaServerContract() {
    @Test
    fun `a favourite round-trips through item`() = runTest {
        val server = server()
        val id = library.movieWithResume.id

        server.userData.setFavorite(id, true)
        assertEquals(true, server.items.item(id)?.favorite)
        server.userData.setFavorite(id, false)
        assertEquals(false, server.items.item(id)?.favorite)
    }

    @Test
    fun `played round-trips through item`() = runTest {
        val server = server()
        val id = library.favoriteMovie.id

        server.userData.setPlayed(id, true)
        assertEquals(true, server.items.item(id)?.played)
        server.userData.setPlayed(id, false)
        assertEquals(false, server.items.item(id)?.played)
    }

    @Test
    fun `an episode marked played moves next up on`() = runTest {
        val server = server()
        val next = library.nextUp.id

        server.userData.setPlayed(next, true)
        val after = server.items.nextUp(library.series.id, dateCutoff = null).firstOrNull()?.id
        server.userData.setPlayed(next, false)

        assertEquals(library.season1Episodes[2].id, after)
    }

    @Test
    fun `an unknown item fails with an error status`() = runTest {
        val server = server()

        assertErrorStatus { server.userData.setFavorite(library.unknownId, true) }
        assertErrorStatus { server.userData.setPlayed(library.unknownId, true) }
    }

    @Test
    fun `a failing server gives an error status`() = runTest {
        val userData = failingServer().userData

        assertErrorStatus { userData.setFavorite(library.movieWithResume.id, true) }
        assertErrorStatus { userData.setPlayed(library.movieWithResume.id, true) }
    }

    @Test
    fun `a server that does not answer gives an unanswered failure`() = runTest {
        val userData = unreachableServer().userData

        assertUnanswered { userData.setFavorite(library.movieWithResume.id, true) }
        assertUnanswered { userData.setPlayed(library.movieWithResume.id, true) }
    }
}
