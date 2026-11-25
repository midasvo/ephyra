package dev.jdtech.jellyfin.data.files

import android.content.Context
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.MediaSourceId
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.repository.DownloadedFiles
import java.io.File

/**
 * #966: [DownloadedFiles] in the app's files directory, where the downloader and
 * `ImagesDownloaderWorker` write them: `images/<item>/<image>` and `trickplay/<item>/<version>/
 * <index>`. Moved out of the repository, which read them with its own `Context`. The directory is
 * looked up on each read, as the repository did.
 */
class DownloadedFilesOnDisk(private val context: Context) : DownloadedFiles {
    override fun hasImage(itemId: ItemId, image: String): Boolean =
        File(context.filesDir, "images/${itemId.asString()}/$image").length() > 0

    override fun trickplaySheet(
        itemId: ItemId,
        mediaSourceId: MediaSourceId?,
        index: Int,
    ): ByteArray? {
        // CE (#860): the sheets of the version asked for, not those of whichever version the
        // folder lists first; a version not downloaded is not stored.
        val source =
            if (mediaSourceId != null) {
                File(context.filesDir, "trickplay/${itemId.asString()}/${mediaSourceId.asString()}")
                    .takeIf { it.isDirectory }
            } else {
                // An empty folder holds no sheets (#966: it threw before, and the server was asked
                // after an error in the log; now the server is asked without one).
                File(context.filesDir, "trickplay/${itemId.asString()}").listFiles()?.firstOrNull()
            } ?: return null
        return File(source, index.toString()).readBytes()
    }
}
