package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.MediaSourceId

/**
 * #966: the files a download keeps next to its rows: the images (`ImagesDownloaderWorker`) and the
 * trickplay sheets (the downloader), in the app's own storage. What the repository reads of them;
 * `DownloadedFilesOnDisk` in `data` implements it.
 */
interface DownloadedFiles {
    /** Whether a download saved [image] (`backdrop`, `primary`, …) of [itemId], not empty. */
    fun hasImage(itemId: ItemId, image: String): Boolean

    /**
     * The stored trickplay sheet [index] of [itemId]: of version [mediaSourceId] (CE, #860), or of
     * whichever version is stored first when null. Null when no sheets are stored for it; throws
     * when they are but the sheet cannot be read.
     */
    fun trickplaySheet(itemId: ItemId, mediaSourceId: MediaSourceId?, index: Int): ByteArray?
}
