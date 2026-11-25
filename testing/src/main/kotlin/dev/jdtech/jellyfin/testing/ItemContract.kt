package dev.jdtech.jellyfin.testing

import dev.jdtech.jellyfin.domain.ports.ItemPort
import dev.jdtech.jellyfin.models.BoxSet
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.models.Show
import dev.jdtech.jellyfin.testing.ContractLibrary.Part
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The contract of [ItemPort] (#771, #967): what every implementation does with [ContractLibrary]. A
 * subclass says how to build the implementation over that library; the tests are written once.
 * [ItemListContract], [ItemExtrasContract] and [ServerInfoContract] are the rest of the catalog.
 *
 * The tests assert on the kind of outcome, not on one server's quirks (the order of equal names,
 * the status of a malformed id), so a server of another type can pass them. What is gated on a
 * capability reads it from [server], the implementation under test.
 */
abstract class ItemContract : MediaServerContract() {
    private fun items(): ItemPort = server().items

    @Test
    fun `a movie has its name, runtime and resume position`() = runTest {
        val expected = library.movieWithResume
        val movie = items().movie(expected.id)

        assertEquals(expected.id, movie.id)
        assertEquals("intentionally-broken", movie.name)
        assertEquals(expected.runtimeTicks, movie.runtimeTicks)
        assertEquals(expected.playbackPositionTicks, movie.playbackPositionTicks)
        assertFalse(movie.played)
    }

    @Test
    fun `item gives each item as its kind`() = runTest {
        val items = items()

        assertIs<Movie>(items.item(library.favoriteMovie.id))
        assertIs<Show>(items.item(library.series.id))
        val episode = assertIs<Episode>(items.item(library.nextUp.id))
        assertEquals(library.season1.id, episode.seasonId)
        assertEquals(library.series.id, episode.seriesId)
    }

    @Test
    fun `item gives null for a kind the app does not handle`() = runTest {
        assumeOnServer(Part.PERSON)
        // A person is an item on the server, of a kind the app shows only as a person.
        assertNull(items().item(library.person.id))
    }

    @Test
    fun `a box set is a box set`() = runTest {
        assumeOnServer(Part.BOX_SET)
        val boxSet = assertIs<BoxSet>(items().item(library.boxSet.id))

        assertEquals(library.boxSet.name, boxSet.name)
    }

    @Test
    fun `a person has a name`() = runTest {
        assumeOnServer(Part.PERSON)
        val person = items().person(library.person.id)

        assertEquals(library.person.id, person.id)
        assertEquals(library.person.name, person.name)
    }

    @Test
    fun `the seasons of a series come in their order`() = runTest {
        val seasons = items().seasons(library.series.id)

        assertEquals(library.seasons.map { it.id }, seasons.map { it.id })
        assertEquals(library.seasons.map { it.indexNumber }, seasons.map { it.indexNumber })
    }

    @Test
    fun `episodes of a season come in episode order`() = runTest {
        val episodes =
            items()
                .episodes(
                    library.series.id,
                    library.season1.id,
                    fields = null,
                    startItemId = null,
                    limit = null,
                )

        assertEquals(library.season1Episodes.map { it.id }, episodes.map { it.id })
        assertEquals((1..library.season1Episodes.size).toList(), episodes.map { it.indexNumber })
        assertTrue(episodes.all { it.seasonId == library.season1.id })
        assertEquals(listOf(true, false, false, false, false), episodes.map { it.played })
    }

    @Test
    fun `next up is the first unplayed episode`() = runTest {
        val nextUp = items().nextUp(library.series.id, dateCutoff = null)

        assertEquals(library.nextUp.id, nextUp.firstOrNull()?.id, "next up: $nextUp")
    }

    @Test
    fun `an unknown id fails with an error status from item and from movie`() = runTest {
        val items = items()

        val fromItem = assertFailsWith<ServerException> { items.item(library.unknownId) }
        assertIs<ServerException.HttpStatus>(fromItem)
        val fromMovie = assertFailsWith<ServerException> { items.movie(library.unknownId) }
        assertIs<ServerException.HttpStatus>(fromMovie)
        assertTrue(fromMovie.status in 400..499, "status ${fromMovie.status}")
    }

    @Test
    fun `a failing server gives ServerException, never another exception type`() = runTest {
        val items = failingServer().items
        val calls: List<suspend () -> Any?> =
            listOf(
                { items.item(library.movieWithResume.id) },
                { items.movie(library.movieWithResume.id) },
                { items.seasons(library.series.id) },
                { items.nextUp(library.series.id, dateCutoff = null) },
            )

        for (call in calls) assertErrorStatus(call)
    }

    @Test
    fun `a server that does not answer gives an unanswered failure`() = runTest {
        val items = unreachableServer().items

        assertUnanswered { items.item(library.movieWithResume.id) }
    }

    @Test
    fun `a call cancelled while it waits is cancelled`() = runTest {
        val items = stallingServer().items

        assertCancellable { items.item(library.movieWithResume.id) }
    }

    @Test
    fun `the episode of next up is the one item gives`() = runTest {
        val items = items()
        val nextUp = assertNotNull(items.nextUp(library.series.id, null).firstOrNull())
        val episode = items.episode(nextUp.id)

        assertEquals(nextUp.id, episode.id)
        assertEquals(nextUp.name, episode.name)
        assertEquals(library.season1.id, episode.seasonId)
    }
}
