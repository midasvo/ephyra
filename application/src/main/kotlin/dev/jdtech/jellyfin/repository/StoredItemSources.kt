package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.AccountKey
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.ServerId
import kotlinx.coroutines.flow.Flow

/**
 * #966: the local sources the repository adds to what the server lists and plays first: the
 * downloads of one server, with their media streams, and whether anything is downloaded at all.
 *
 * Downloads are stored per server (#718): a query that is of one server takes its [ServerId], one
 * of a user on a server an [AccountKey]. A row is otherwise keyed as Room keys it. One of the roles
 * of the download store; `RoomDownloadStore` in `data` implements them all.
 */
interface StoredItemSources {
    /** Every local source of [itemId] stored for [serverId], finished or not, with its streams. */
    suspend fun allSourcesOfServer(itemId: ItemId, serverId: ServerId): List<ItemSource>

    /** The finished downloads of [itemId] stored for [serverId] (#454: no partial file). */
    suspend fun completedSourcesOfServer(itemId: ItemId, serverId: ServerId): List<ItemSource>

    /**
     * The local sources of every item in [itemIds] stored for [serverId], keyed by item id, read in
     * batches. Items without one are absent.
     */
    suspend fun localSourcesOfServer(
        itemIds: Collection<ItemId>,
        serverId: ServerId,
    ): Map<ItemId, List<ItemSource>>

    /** Whether any source is stored, of any server. */
    suspend fun hasSources(): Boolean

    /** As [hasSources], again on every change of the sources. */
    fun observeHasSources(): Flow<Boolean>
}
