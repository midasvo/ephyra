package dev.jdtech.jellyfin.testing

import dev.jdtech.jellyfin.domain.ports.ItemExtrasPort
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.testing.ContractLibrary.Part
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The contract of [ItemExtrasPort] (#771, #967): a server with a feature gives it for
 * [ContractLibrary], a server without it (its `ServerCapabilities`) gives nothing. Written like
 * [ItemContract].
 */
abstract class ItemExtrasContract : MediaServerContract() {
    @Test
    fun `a server without theme songs gives none`() = runTest {
        val server = server()
        assumeFalse(server.capabilities.themeSongs)
        assertNull(server.itemExtras.themeSongUrl(library.series.id))
    }

    @Test
    fun `a server without similar items gives none`() = runTest {
        val server = server()
        assumeFalse(server.capabilities.similarItems)
        assertTrue(server.itemExtras.similar(library.movieWithResume.id, limit = 12).isEmpty())
    }

    @Test
    fun `a server without extras gives none`() = runTest {
        val server = server()
        assumeFalse(server.capabilities.extras)
        val extras =
            server.itemExtras.extras(
                library.movieWithResume.id,
                localTrailers = true,
                specialFeatures = true,
            )
        assertTrue(extras.isEmpty())
    }

    @Test
    fun `a server without item collections gives none`() = runTest {
        val server = server()
        assumeFalse(server.capabilities.itemCollections)
        assertTrue(server.itemExtras.collectionsOf(library.movieWithResume.id).isEmpty())
    }

    @Test
    fun `similar items are there and are not the item itself`() = runTest {
        val server = server()
        assumeTrue(server.capabilities.similarItems)
        assumeOnServer(Part.SIMILAR_ITEMS)
        val similar =
            server.itemExtras.similar(library.movieWithResume.id, limit = 12).map { it.id }

        assertTrue(similar.isNotEmpty(), "no similar items")
        assertFalse(library.movieWithResume.id in similar, "similar: $similar")
    }

    @Test
    fun `the series has its theme song`() = runTest {
        val server = server()
        assumeTrue(server.capabilities.themeSongs)
        assumeOnServer(Part.THEME_SONG)
        val url = assertNotNull(server.itemExtras.themeSongUrl(library.series.id))

        assertTrue(url.startsWith("http"), url)
        assertTrue(library.seriesThemeSongId.asString() in url, url)
    }

    @Test
    fun `a movie without a theme song has none`() = runTest {
        val server = server()
        assumeTrue(server.capabilities.themeSongs)
        assertNull(server.itemExtras.themeSongUrl(library.movieWithResume.id))
    }
}
