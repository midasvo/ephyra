package dev.jdtech.jellyfin.domain.ports

import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.Movie

/**
 * What a detail page shows next to an item (#967): similar items, extras, the collections it is in
 * and its theme song. A server without one of these features (`ServerCapabilities`) gives nothing
 * for it. Failures are thrown as `ServerException` (#756).
 */
interface ItemExtrasPort {
    /** Without [itemId] itself. */
    suspend fun similar(itemId: ItemId, limit: Int): List<Item>

    suspend fun extras(
        itemId: ItemId,
        localTrailers: Boolean,
        specialFeatures: Boolean,
    ): List<Movie>

    suspend fun collectionsOf(itemId: ItemId): List<Item>

    /** Null when the item has no theme song. */
    suspend fun themeSongUrl(itemId: ItemId): String?
}
