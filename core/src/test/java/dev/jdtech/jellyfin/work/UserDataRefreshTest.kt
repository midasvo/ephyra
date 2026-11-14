package dev.jdtech.jellyfin.work

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CE (#635): what the sync copies from the server into the rows of downloads, and how often the app
 * asks for that while it is in use.
 */
class UserDataRefreshTest {

    @Test
    fun `the server's values are stored, with its last played date`() {
        val item =
            serverUserData(positionTicks = 1_270_000_000L, favorite = true, lastPlayed = 5_000L)

        assertEquals(
            listOf(item),
            serverUserDataToStore(mapOf(item.itemId to item), playingHere = null),
        )
    }

    @Test
    fun `the item open in the player here is left alone`() {
        val playing = serverUserData(positionTicks = 10L)
        val other = serverUserData(positionTicks = 20L)

        val stored =
            serverUserDataToStore(
                mapOf(playing.itemId to playing, other.itemId to other),
                playingHere = playing.itemId,
            )

        assertEquals(listOf(other.itemId), stored.map { it.itemId })
    }

    @Test
    fun `the first refresh is always due`() {
        assertTrue(refreshDue(lastAt = null, now = 0L, intervalMillis = INTERVAL))
    }

    @Test
    fun `a refresh is due again only after the interval`() {
        assertFalse(
            refreshDue(lastAt = 1_000L, now = 1_000L + INTERVAL - 1, intervalMillis = INTERVAL)
        )
        assertTrue(refreshDue(lastAt = 1_000L, now = 1_000L + INTERVAL, intervalMillis = INTERVAL))
    }

    @Test
    fun `a run refreshes the downloads only while nothing is playing here`() {
        assertTrue(refreshesDownloads(hasDownloads = true, playingHere = null))
        assertFalse(refreshesDownloads(hasDownloads = true, playingHere = UUID.randomUUID()))
        assertFalse(refreshesDownloads(hasDownloads = false, playingHere = null))
    }

    private fun serverUserData(
        positionTicks: Long,
        favorite: Boolean = false,
        lastPlayed: Long? = null,
    ) =
        ServerUserData(
            itemId = UUID.randomUUID(),
            played = false,
            favorite = favorite,
            playbackPositionTicks = positionTicks,
            lastPlayedDate = lastPlayed,
        )

    private companion object {
        const val INTERVAL = 15 * 60 * 1000L
    }
}
