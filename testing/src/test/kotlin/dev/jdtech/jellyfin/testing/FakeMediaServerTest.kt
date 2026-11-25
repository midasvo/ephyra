package dev.jdtech.jellyfin.testing

import dev.jdtech.jellyfin.models.ServerException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

/** #771: what the fake does beyond the contract, as the Jellyfin client does it. */
class FakeMediaServerTest {
    private val library = ContractLibrary

    @Test
    fun `a failing server still builds URLs and switches server`() {
        val server = FakeMediaServer().apply { failWith(ServerException.HttpStatus(500)) }

        assertNotNull(server.playback.chapterImageUrl(library.movieWithResume.id, 0, "tag"))
        assertNotNull(server.playback.seasonBackdropUrl(library.season1.id))
        server.session.use("http://other.server", "other", library.userId)
        assertEquals("http://other.server", server.session.baseUrl)
    }

    @Test
    fun `a 401 reports the session's token as refused`() = runTest {
        val server = FakeMediaServer().apply { failWith(ServerException.HttpStatus(401)) }
        val refused = async { server.session.unauthorized.first() }
        yield()

        runCatching { server.items.item(library.movieWithResume.id) }

        assertEquals(FakeMediaServer.TOKEN, refused.await())
    }
}
