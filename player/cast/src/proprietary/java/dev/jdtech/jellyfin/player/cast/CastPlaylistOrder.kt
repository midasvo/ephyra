package dev.jdtech.jellyfin.player.cast

import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import java.util.UUID

/**
 * The playlist's order, as far as it is known. A PlaylistManager returns a neighbour once, so the
 * answer is kept: it is asked again each time the receiver moves to another item, and after an
 * audio switch replaced the receiver's queue.
 */
internal class CastPlaylistOrder {
    private val playerItems = HashMap<UUID, PlayerItem>()
    private val nextOf = HashMap<UUID, UUID>()
    private val previousOf = HashMap<UUID, UUID>()

    /** [item] is in the playlist; where is not known yet. */
    fun add(item: PlayerItem) {
        playerItems[item.itemId] = item
    }

    /** A new playlist that starts with [item]: what was known of the last one is gone. */
    fun restart(item: PlayerItem) {
        clear()
        add(item)
    }

    /**
     * What the PlaylistManager answered for the items around [itemId]. `null` is no answer, not
     * "there is none": the manager gives each neighbour once, and an earlier answer stays.
     */
    fun setNeighbours(itemId: UUID, next: PlayerItem?, previous: PlayerItem?) {
        if (next != null) {
            playerItems[next.itemId] = next
            nextOf[itemId] = next.itemId
            previousOf[next.itemId] = itemId
        }
        if (previous != null) {
            playerItems[previous.itemId] = previous
            previousOf[itemId] = previous.itemId
            nextOf[previous.itemId] = itemId
        }
    }

    fun next(itemId: UUID): PlayerItem? = nextOf[itemId]?.let { playerItems[it] }

    fun previous(itemId: UUID): PlayerItem? = previousOf[itemId]?.let { playerItems[it] }

    fun hasNext(itemId: UUID): Boolean = next(itemId) != null

    fun hasPrevious(itemId: UUID): Boolean = previous(itemId) != null

    fun clear() {
        playerItems.clear()
        nextOf.clear()
        previousOf.clear()
    }
}

/** An item in the receiver's queue: the receiver's id for it, and the Jellyfin item it plays. */
internal data class ReceiverQueueItem(val queueItemId: Int, val itemId: UUID?)

/** Where a new item goes in the receiver's queue. */
internal sealed interface QueueInsert {
    /** Nowhere: the item is in the queue already, or the receiver plays something else now. */
    data object Skip : QueueInsert

    data class Before(val queueItemId: Int) : QueueInsert

    data object AtEnd : QueueInsert
}

/**
 * Where [itemId] goes in the receiver's [queue] to sit right [before] or after [current], the item
 * the neighbours were looked up for. [currentQueueItemId] is the queue item the receiver plays. If
 * that is no longer [current], the user started something else or the receiver moved on, and the
 * item would land next to the wrong one.
 */
internal fun queueInsertPosition(
    queue: List<ReceiverQueueItem>,
    currentQueueItemId: Int,
    current: UUID,
    itemId: UUID,
    before: Boolean,
): QueueInsert {
    val currentIndex = queue.indexOfFirst { it.queueItemId == currentQueueItemId }
    if (queue.getOrNull(currentIndex)?.itemId != current) return QueueInsert.Skip
    if (queue.any { it.itemId == itemId }) return QueueInsert.Skip
    if (before) return QueueInsert.Before(currentQueueItemId)
    return queue.getOrNull(currentIndex + 1)?.let { QueueInsert.Before(it.queueItemId) }
        ?: QueueInsert.AtEnd
}
