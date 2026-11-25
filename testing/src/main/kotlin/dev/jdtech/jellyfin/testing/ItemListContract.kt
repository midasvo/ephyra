package dev.jdtech.jellyfin.testing

import dev.jdtech.jellyfin.domain.ports.ItemListPort
import dev.jdtech.jellyfin.domain.ports.ItemQuery
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.Show
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The contract of [ItemListPort] (#771, #967): what every implementation's lists, counts and search
 * give for [ContractLibrary]. Written like [ItemContract].
 */
abstract class ItemListContract : MediaServerContract() {
    private fun lists(): ItemListPort = server().itemLists

    @Test
    fun `the favourite movie is a favourite`() = runTest {
        val server = server()

        assertTrue(server.items.movie(library.favoriteMovie.id).favorite)
        val favorites = server.itemLists.favorites().map { it.id }
        assertTrue(library.favoriteMovie.id in favorites, "favorites: $favorites")
        assertFalse(library.movieWithResume.id in favorites, "favorites: $favorites")
    }

    @Test
    fun `the resumed movie is among the items to resume`() = runTest {
        val resume = lists().resumeItems().map { it.id }

        assertTrue(library.movieWithResume.id in resume, "resume items: $resume")
        assertFalse(library.favoriteMovie.id in resume, "resume items: $resume")
    }

    @Test
    fun `items of a kind are only that kind`() = runTest {
        val lists = lists()
        val movies =
            lists.items(ItemQuery(kinds = listOf(ItemKind.MOVIE), recursive = true, limit = 20))
        val shows =
            lists.items(ItemQuery(kinds = listOf(ItemKind.SERIES), recursive = true, limit = 20))

        assertTrue(movies.items.isNotEmpty())
        assertTrue(movies.items.all { it is Movie }, "movies: ${movies.items}")
        assertTrue(shows.items.isNotEmpty())
        assertTrue(shows.items.all { it is Show }, "shows: ${shows.items}")
    }

    private val season1Query =
        ItemQuery(parentId = library.season1.id, kinds = listOf(ItemKind.EPISODE))

    @Test
    fun `a page reports the total only when asked`() = runTest {
        val lists = lists()

        assertNull(lists.items(season1Query).totalRecordCount)
        val counted = lists.items(season1Query.copy(limit = 2), includeTotal = true)
        assertEquals(library.season1Episodes.size, counted.totalRecordCount)
        assertEquals(2, counted.items.size)
        assertEquals(library.season1Episodes.size, lists.itemCount(season1Query.copy(limit = 2)))
    }

    @Test
    fun `start index and limit page through the items`() = runTest {
        val lists = lists()
        val all = lists.items(season1Query).items.map { it.id }
        val page = lists.items(season1Query.copy(startIndex = 1, limit = 2)).items.map { it.id }

        assertEquals(library.season1Episodes.map { it.id }.toSet(), all.toSet())
        assertEquals(all.drop(1).take(2), page)
    }

    @Test
    fun `a page past the end is empty and still counts all`() = runTest {
        val past = library.season1Episodes.size + 5
        val page = lists().items(season1Query.copy(startIndex = past), includeTotal = true)

        assertTrue(page.items.isEmpty(), "items: ${page.items}")
        assertEquals(library.season1Episodes.size, page.totalRecordCount)
    }

    @Test
    fun `items come in the sort order and direction asked for`() = runTest {
        val lists = lists()
        val query = season1Query.copy(sortBy = SortBy.NAME)
        val ascending = lists.items(query.copy(sortOrder = SortOrder.ASCENDING)).items
        val descending = lists.items(query.copy(sortOrder = SortOrder.DESCENDING)).items

        // The episodes' names are distinct and in episode order.
        assertEquals(library.season1Episodes.map { it.id }, ascending.map { it.id })
        assertEquals(ascending.map { it.id }.reversed(), descending.map { it.id })
    }

    @Test
    fun `search finds by a word of the title`() = runTest {
        val found = lists().search("Versions", kinds = null).map { it.id }

        assertTrue(library.movieWithTwoVersions.id in found, "found: $found")
        // A search that ignored the word would give every movie.
        assertFalse(library.movieWithResume.id in found, "found: $found")
        assertFalse(library.series.id in found, "found: $found")
    }

    @Test
    fun `search of a kind finds only that kind`() = runTest {
        val found = lists().search("Test Show", kinds = listOf(ItemKind.SERIES))

        assertTrue(library.series.id in found.map { it.id }, "found: $found")
        assertTrue(found.all { it is Show }, "found: $found")
    }

    @Test
    fun `the libraries are there`() = runTest {
        val lists = lists()

        assertTrue(lists.libraries().isNotEmpty())
        assertTrue(lists.userViews().isNotEmpty())
    }

    @Test
    fun `a failing server gives ServerException, never another exception type`() = runTest {
        val lists = failingServer().itemLists
        val calls: List<suspend () -> Any?> =
            listOf(
                { lists.items(ItemQuery(parentId = library.season1.id)) },
                { lists.search("Versions", kinds = null) },
                { lists.libraries() },
            )

        for (call in calls) assertErrorStatus(call)
    }

    @Test
    fun `a server that does not answer gives an unanswered failure`() = runTest {
        val lists = unreachableServer().itemLists

        assertUnanswered { lists.items(ItemQuery(parentId = library.season1.id)) }
    }
}
