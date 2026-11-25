package dev.jdtech.jellyfin.domain.ports

import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemFilter
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.Library
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.repository.ItemsPage

/** Which items of the catalog a list or a count is about. */
data class ItemQuery(
    val parentId: ItemId? = null,
    val kinds: List<ItemKind>? = null,
    val recursive: Boolean = false,
    val sortBy: SortBy = SortBy.defaultValue,
    val sortOrder: SortOrder = SortOrder.ASCENDING,
    val filters: Set<ItemFilter> = emptySet(),
    val startIndex: Int? = null,
    val limit: Int? = null,
)

/**
 * The server's lists of items (#967): the libraries and views, a query's page and count, search,
 * favourites, suggestions, items to resume and the latest. Knows nothing about downloads: an item
 * has only its remote sources. Failures are thrown as `ServerException` (#756).
 */
interface ItemListPort {
    suspend fun libraries(): List<Library>

    suspend fun userViews(): List<Library>

    /** [ItemsPage.totalRecordCount] is null unless [includeTotal]. */
    suspend fun items(query: ItemQuery, includeTotal: Boolean = false): ItemsPage

    /** How many items [query] has; its sort, start index and limit do not count. */
    suspend fun itemCount(query: ItemQuery, nameLessThan: String? = null): Int

    suspend fun randomItemsWithBackdrop(kinds: List<ItemKind>, limit: Int): List<Item>

    suspend fun favorites(): List<Item>

    /**
     * Up to [SEARCH_LIMIT] movies and shows when [kinds] is null, else up to
     * [SEARCH_LIMIT_PER_KIND] of each kind.
     */
    suspend fun search(query: String, kinds: List<ItemKind>?): List<Item>

    suspend fun suggestions(): List<Item>

    suspend fun resumeItems(): List<Item>

    suspend fun latest(parentId: ItemId, kinds: List<ItemKind>?): List<Item>

    companion object {
        /** How many results [search] gives without kinds; the search of downloads keeps to it. */
        const val SEARCH_LIMIT = 50

        /** How many results of each kind [search] gives. */
        const val SEARCH_LIMIT_PER_KIND = 20
    }
}
