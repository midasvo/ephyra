package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.AccountKey
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemTrickplayInfo
import dev.jdtech.jellyfin.models.MediaSourceId
import dev.jdtech.jellyfin.models.Segment
import dev.jdtech.jellyfin.models.ServerId

/**
 * #967: the segments and the trickplay layout a download stores with its item.
 *
 * Downloads are stored per server (#718): a query that is of one server takes its [ServerId], one
 * of a user on a server an [AccountKey]. A row is otherwise keyed as Room keys it. One of the roles
 * the download store was split into (#967); `RoomDownloadStore` in `data` implements them all.
 */
interface DownloadSegmentsAndTrickplay {
    /** #966: the stored segments of [itemId], none when nothing is stored for it. */
    suspend fun segments(itemId: ItemId): List<Segment>

    /** Replaces the stored segments of [itemId] with [segments], in one transaction. */
    suspend fun replaceSegments(itemId: ItemId, segments: List<Segment>)

    suspend fun deleteSegments(itemId: ItemId)

    /** Stores [info], the trickplay layout of source [sourceId]. */
    suspend fun insertTrickplayInfo(sourceId: MediaSourceId, info: ItemTrickplayInfo)
}
