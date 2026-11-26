package dev.jdtech.jellyfin.player.cast

import dev.jdtech.jellyfin.models.toItemId
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit tests for [resolveReceiverItems]: ensuring a queue transition on the receiver does not
 * attribute the next item's loading/initial status to the previous item (#1027).
 */
class CastReceiverItemsTest {
    private val itemA = UUID.randomUUID().toString().toItemId()
    private val itemB = UUID.randomUUID().toString().toItemId()

    @Test
    fun `steady playback names the current item for both position and reporting`() {
        assertEquals(
            ReceiverItems(positionItemId = itemA, receiverItemId = itemA),
            resolveReceiverItems(
                currentQueueItemId = 1,
                loadingQueueItemId = NO_QUEUE_ITEM,
                currentItemId = itemA,
                loadingItemId = null,
                mediaInfoItemId = itemA,
            ),
        )
        assertEquals(
            ReceiverItems(positionItemId = itemA, receiverItemId = itemA),
            resolveReceiverItems(
                currentQueueItemId = 1,
                loadingQueueItemId = 1,
                currentItemId = itemA,
                loadingItemId = itemA,
                mediaInfoItemId = null,
            ),
        )
    }

    @Test
    fun `loading another queue item hides positionItemId and tells reporter the next item`() {
        assertEquals(
            ReceiverItems(positionItemId = null, receiverItemId = itemB),
            resolveReceiverItems(
                currentQueueItemId = 1,
                loadingQueueItemId = 2,
                currentItemId = itemA,
                loadingItemId = itemB,
                mediaInfoItemId = itemA,
            ),
        )
        assertEquals(
            ReceiverItems(positionItemId = null, receiverItemId = null),
            resolveReceiverItems(
                currentQueueItemId = 1,
                loadingQueueItemId = 2,
                currentItemId = itemA,
                loadingItemId = null,
                mediaInfoItemId = itemA,
            ),
        )
    }

    @Test
    fun `mediaInfo changing ahead of currentItem hides positionItemId and reports the new item`() {
        assertEquals(
            ReceiverItems(positionItemId = null, receiverItemId = itemB),
            resolveReceiverItems(
                currentQueueItemId = 1,
                loadingQueueItemId = NO_QUEUE_ITEM,
                currentItemId = itemA,
                loadingItemId = null,
                mediaInfoItemId = itemB,
            ),
        )
    }
}
