package dev.jdtech.jellyfin.models

import androidx.room3.Entity
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

@Entity(tableName = "userdata", primaryKeys = ["userId", "itemId"])
data class FindroidUserDataDto(
    val userId: UUID,
    val itemId: UUID,
    val played: Boolean,
    val favorite: Boolean,
    val playbackPositionTicks: Long,
    val toBeSynced: Boolean = false,
    /**
     * CE (#633): when this item was last played, in epoch milliseconds: set by playback here, or
     * the server's, copied by the sync. Sent with the row, so offline playback moves the item up
     * in Continue Watching on other devices. Null when unknown.
     */
    val lastPlayedDate: Long? = null,
)

fun FindroidItem.toFindroidUserDataDto(userId: UUID): FindroidUserDataDto {
    return FindroidUserDataDto(
        userId = userId,
        itemId = id,
        played = played,
        favorite = favorite,
        playbackPositionTicks = playbackPositionTicks,
        // CE (#633): the server's, so marking played offline keeps it, as the server does.
        lastPlayedDate = lastPlayedDateOf(this)?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli(),
    )
}

/** CE (#633): when the server last saw [item] played, for the item types that carry it. */
private fun lastPlayedDateOf(item: FindroidItem): LocalDateTime? =
    when (item) {
        is FindroidMovie -> item.lastPlayedDate
        is FindroidEpisode -> item.lastPlayedDate
        else -> null
    }
