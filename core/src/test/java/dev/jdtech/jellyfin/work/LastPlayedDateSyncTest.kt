package dev.jdtech.jellyfin.work

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CE (#633): the sync sends a row's LastPlayedDate only when it is newer than the server's, so a
 * device that watched the item later keeps it at the top of Continue Watching.
 */
class LastPlayedDateSyncTest {

    @Test
    fun `a later local time is sent`() {
        assertTrue(isNewerThanServer(local = 2_000L, server = 1_000L))
    }

    @Test
    fun `an earlier local time is not sent`() {
        // Another device watched the item after this one did.
        assertFalse(isNewerThanServer(local = 1_000L, server = 2_000L))
    }

    @Test
    fun `the same time is not sent`() {
        assertFalse(isNewerThanServer(local = 1_000L, server = 1_000L))
    }

    @Test
    fun `a local time is sent when the server has none`() {
        assertTrue(isNewerThanServer(local = 1_000L, server = null))
    }

    @Test
    fun `no local time is never sent`() {
        assertFalse(isNewerThanServer(local = null, server = null))
        assertFalse(isNewerThanServer(local = null, server = 1_000L))
    }

    @Test
    fun `the server's user data is read by item, with its last played date`() {
        // The SDK hands dates over in the device's zone; in a zone that is not UTC, a conversion
        // that ignored it would be hours off.
        val default = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        try {
            readsServerUserData()
        } finally {
            TimeZone.setDefault(default)
        }
    }

    private fun readsServerUserData() {
        // 2026-11-13 03:02:51 UTC, as the SDK hands it over in Los Angeles.
        val played = LocalDateTime.of(2026, 11, 12, 19, 2, 51)
        val withDate = item(lastPlayed = played, positionTicks = 1_270_000_000L, favorite = true)
        val withoutDate = item(lastPlayed = null)
        val withoutUserData = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.EPISODE)

        val read = serverUserDataOf(listOf(withDate, withoutDate, withoutUserData))

        assertEquals(setOf(withDate.id, withoutDate.id), read.keys)
        assertEquals(
            ServerUserData(
                itemId = withDate.id,
                played = false,
                favorite = true,
                playbackPositionTicks = 1_270_000_000L,
                lastPlayedDate = Instant.parse("2026-11-13T03:02:51Z").toEpochMilli(),
            ),
            read[withDate.id],
        )
        assertEquals(null, read[withoutDate.id]!!.lastPlayedDate)
    }

    @Test
    fun `dates convert between the SDK's local time and epoch milliseconds`() {
        val zone = ZoneId.of("America/Los_Angeles")
        // 2026-11-13 03:02:51 UTC, as the SDK hands it over in this time zone.
        val local = LocalDateTime.of(2026, 11, 12, 19, 2, 51)

        val millis = epochMillisOf(local, zone)

        assertEquals(Instant.parse("2026-11-13T03:02:51Z").toEpochMilli(), millis)
        assertEquals(local, localDateTimeOf(millis, zone))
    }

    private fun item(
        lastPlayed: LocalDateTime?,
        positionTicks: Long = 0L,
        favorite: Boolean = false,
    ): BaseItemDto {
        val id = UUID.randomUUID()
        return BaseItemDto(
            id = id,
            type = BaseItemKind.EPISODE,
            userData =
                UserItemDataDto(
                    playbackPositionTicks = positionTicks,
                    playCount = 0,
                    isFavorite = favorite,
                    played = false,
                    key = "",
                    itemId = id,
                    lastPlayedDate = lastPlayed,
                ),
        )
    }
}
