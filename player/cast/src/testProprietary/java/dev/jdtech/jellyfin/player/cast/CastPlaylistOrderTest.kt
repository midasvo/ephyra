package dev.jdtech.jellyfin.player.cast

import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CastPlaylistOrder] and [queueInsertPosition]: which items the receiver's queue
 * gets around the current one, and where (#626).
 */
class CastPlaylistOrderTest {

    private val episode1 = item("Episode 1")
    private val episode2 = item("Episode 2")
    private val episode3 = item("Episode 3")
    private val movie = item("Movie")

    @Test
    fun `an item that was only added has no neighbours`() {
        val order = CastPlaylistOrder()
        order.add(episode1)

        assertNull(order.next(episode1.itemId))
        assertNull(order.previous(episode1.itemId))
        assertFalse(order.hasNext(episode1.itemId))
        assertFalse(order.hasPrevious(episode1.itemId))
    }

    @Test
    fun `the neighbours of an item are known in both directions`() {
        val order = CastPlaylistOrder()
        order.add(episode2)
        order.setNeighbours(episode2.itemId, next = episode3, previous = episode1)

        assertEquals(episode3, order.next(episode2.itemId))
        assertEquals(episode1, order.previous(episode2.itemId))
        assertTrue(order.hasNext(episode2.itemId))
        assertTrue(order.hasPrevious(episode2.itemId))
        // The receiver moves on to episode 3: its previous item needs no asking.
        assertEquals(episode2, order.previous(episode3.itemId))
        assertEquals(episode2, order.next(episode1.itemId))
        assertNull(order.next(episode3.itemId))
        assertNull(order.previous(episode1.itemId))
    }

    @Test
    fun `the first item of a playlist has a next item after the first load`() {
        // The first test on a real receiver: episode 1 played and nothing came after it.
        val order = CastPlaylistOrder()
        order.add(episode1)
        order.setNeighbours(episode1.itemId, next = episode2, previous = null)

        assertTrue(order.hasNext(episode1.itemId))
        assertFalse(order.hasPrevious(episode1.itemId))
        assertEquals(
            QueueInsert.AtEnd,
            queueInsertPosition(
                queue = listOf(ReceiverQueueItem(1, episode1.itemId)),
                currentQueueItemId = 1,
                current = episode1.itemId,
                itemId = episode2.itemId,
                before = false,
            ),
        )
    }

    @Test
    fun `a neighbour stays known when the playlist manager does not give it again`() {
        // The manager hands each item out once. After an audio switch replaced the receiver's
        // queue it is asked again and answers nothing, and the neighbours still have to be queued.
        val order = CastPlaylistOrder()
        order.add(episode2)
        order.setNeighbours(episode2.itemId, next = episode3, previous = episode1)
        order.setNeighbours(episode2.itemId, next = null, previous = null)

        assertEquals(episode3, order.next(episode2.itemId))
        assertEquals(episode1, order.previous(episode2.itemId))

        // The load left the receiver with the current item only, under a new queue item id.
        val queue = listOf(ReceiverQueueItem(7, episode2.itemId))
        assertEquals(
            QueueInsert.AtEnd,
            queueInsertPosition(queue, 7, episode2.itemId, episode3.itemId, before = false),
        )
        assertEquals(
            QueueInsert.Before(7),
            queueInsertPosition(queue, 7, episode2.itemId, episode1.itemId, before = true),
        )
    }

    @Test
    fun `a new playlist knows nothing of the last one`() {
        val order = CastPlaylistOrder()
        order.add(episode1)
        order.setNeighbours(episode1.itemId, next = episode2, previous = null)

        order.clear()
        order.add(movie)

        assertNull(order.next(episode1.itemId))
        assertNull(order.previous(episode2.itemId))
        assertFalse(order.hasNext(movie.itemId))
        assertFalse(order.hasPrevious(movie.itemId))
    }

    @Test
    fun `a restored session starts its playlist over`() {
        val order = CastPlaylistOrder()
        order.add(episode1)
        order.setNeighbours(episode1.itemId, next = episode2, previous = null)

        order.restart(episode2)

        assertNull(order.previous(episode2.itemId))
        assertNull(order.next(episode1.itemId))
        order.setNeighbours(episode2.itemId, next = episode3, previous = episode1)
        assertEquals(episode3, order.next(episode2.itemId))
    }

    @Test
    fun `the old show's next episode is not queued behind an item that was started since`() {
        // Episode 1 plays and the user starts a movie. Until the load reaches the receiver it
        // still plays episode 1; once it has, the queue is the movie alone.
        val stillTheEpisode = listOf(ReceiverQueueItem(1, episode1.itemId))
        val theMovie = listOf(ReceiverQueueItem(2, movie.itemId))

        // Asked for the movie's neighbours while the receiver is not on the movie yet.
        assertEquals(
            QueueInsert.Skip,
            queueInsertPosition(stillTheEpisode, 1, movie.itemId, episode2.itemId, before = false),
        )
        // Asked for episode 1's neighbours when the receiver has left it.
        assertEquals(
            QueueInsert.Skip,
            queueInsertPosition(theMovie, 2, episode1.itemId, episode2.itemId, before = false),
        )
        assertEquals(
            QueueInsert.Skip,
            queueInsertPosition(theMovie, 2, episode1.itemId, episode2.itemId, before = true),
        )
    }

    @Test
    fun `the next item goes right after the current one`() {
        val queue =
            listOf(
                ReceiverQueueItem(1, episode1.itemId),
                ReceiverQueueItem(2, episode2.itemId),
                ReceiverQueueItem(3, movie.itemId),
            )

        // In front of the item that follows the current one.
        assertEquals(
            QueueInsert.Before(3),
            queueInsertPosition(queue, 2, episode2.itemId, episode3.itemId, before = false),
        )
        // The current item is the last: at the end.
        assertEquals(
            QueueInsert.AtEnd,
            queueInsertPosition(queue.take(2), 2, episode2.itemId, episode3.itemId, before = false),
        )
    }

    @Test
    fun `the previous item goes right before the current one`() {
        val queue =
            listOf(ReceiverQueueItem(4, episode2.itemId), ReceiverQueueItem(5, episode3.itemId))

        assertEquals(
            QueueInsert.Before(4),
            queueInsertPosition(queue, 4, episode2.itemId, episode1.itemId, before = true),
        )
        assertEquals(
            QueueInsert.Before(5),
            queueInsertPosition(queue, 5, episode3.itemId, movie.itemId, before = true),
        )
    }

    @Test
    fun `an item that is in the queue is not queued again`() {
        val queue =
            listOf(
                ReceiverQueueItem(1, episode1.itemId),
                ReceiverQueueItem(2, episode2.itemId),
                ReceiverQueueItem(3, episode3.itemId),
            )

        assertEquals(
            QueueInsert.Skip,
            queueInsertPosition(queue, 2, episode2.itemId, episode3.itemId, before = false),
        )
        assertEquals(
            QueueInsert.Skip,
            queueInsertPosition(queue, 2, episode2.itemId, episode1.itemId, before = true),
        )
    }

    @Test
    fun `nothing is queued when the receiver's current item is not known`() {
        // No queue yet, a current id that is not in the queue, or an item of another sender.
        assertEquals(
            QueueInsert.Skip,
            queueInsertPosition(emptyList(), 0, episode1.itemId, episode2.itemId, before = false),
        )
        assertEquals(
            QueueInsert.Skip,
            queueInsertPosition(
                listOf(ReceiverQueueItem(1, episode1.itemId)),
                9,
                episode1.itemId,
                episode2.itemId,
                before = false,
            ),
        )
        assertEquals(
            QueueInsert.Skip,
            queueInsertPosition(
                listOf(ReceiverQueueItem(1, null)),
                1,
                episode1.itemId,
                episode2.itemId,
                before = true,
            ),
        )
    }

    private fun item(name: String) =
        PlayerItem(
            name = name,
            itemId = UUID.randomUUID(),
            mediaSourceId = "source",
            playbackPosition = 0L,
        )
}
