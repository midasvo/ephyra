package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.AccountKey
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemFilter
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.Library
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.models.UserId

/**
 * CE (#775), #966: the lists the store answers while the server is away: downloaded favourites,
 * search results and the stored libraries (`StoredLibraries`). Only the rows of the given server
 * and the user data of the given user (#718), and only what has a finished download: something that
 * can play. The rows are sorted and filtered before they are mapped, so only what a screen shows is
 * made into items ([StoredRows]).
 *
 * Downloads are stored per server (#718): a query that is of one server takes its [ServerId], one
 * of a user on a server an [AccountKey]. One of the roles of the download store;
 * `RoomDownloadStore` in `data` implements them all.
 */
interface StoredItemLists {
    /** The downloaded favourite movies, shows and episodes of [userId], in that order. */
    suspend fun favorites(serverId: ServerId, userId: UserId): StoredRows<Item>

    /**
     * By name, as the server's search: movies and shows unless [kinds] says otherwise, at most
     * [limitPerKind] of each kind.
     */
    suspend fun search(
        serverId: ServerId,
        query: String,
        kinds: List<ItemKind>?,
        limitPerKind: Int,
    ): StoredRows<Item>

    /** Whether anything of the server is downloaded (finished), favourite or not. */
    suspend fun hasDownloads(serverId: ServerId): Boolean

    /**
     * Each stored library that holds something, named by [names]. CE (#781): with the artwork of
     * the most recently downloaded of its items that has some stored ([storedImage]: whether the
     * file of that image was saved with the download), a backdrop before a poster.
     */
    suspend fun libraries(
        serverId: ServerId,
        names: StoredLibraryNames,
        storedImage: (itemId: ItemId, image: String) -> Boolean,
    ): List<Library>

    /**
     * The rows of stored library [libraryId], sorted as the server would. Filters the store cannot
     * answer are left out rather than failing the grid; so are sorts it has no data for (critic
     * rating), which fall back to the name.
     */
    suspend fun libraryContents(
        serverId: ServerId,
        userId: UserId,
        libraryId: ItemId,
        sortBy: SortBy,
        sortOrder: SortOrder,
        filters: Set<ItemFilter>,
    ): StoredRows<Item>

    /** How many rows of [libraryId] sort before [nameLessThan] (all with null), for the A–Z bar. */
    suspend fun libraryCount(
        serverId: ServerId,
        userId: UserId,
        libraryId: ItemId,
        filters: Set<ItemFilter>,
        nameLessThan: String?,
    ): Int
}
