package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemSource

/**
 * Deletes a downloaded item's local files and database rows. Implemented by `Downloader` in
 * `downloads`; bound to [None] on TV where downloads do not exist (TV ADR 0007).
 */
interface DownloadDeleter {
    suspend fun deleteItem(item: Item, source: ItemSource)

    object None : DownloadDeleter {
        override suspend fun deleteItem(item: Item, source: ItemSource) = Unit
    }
}
