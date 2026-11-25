package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.domain.userdata.PlayState
import dev.jdtech.jellyfin.models.AccountKey
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.UserId

/**
 * #967: the user data a download stores with its item, and what of it is still to be sent to the
 * server (#281).
 *
 * Downloads are stored per server (#718): a query that is of one server takes its [ServerId], one
 * of a user on a server an [AccountKey]. A row is otherwise keyed as Room keys it. One of the roles
 * the download store was split into (#967); `RoomDownloadStore` in `data` implements them all.
 */
interface DownloadUserData {
    /**
     * Stores [item]'s user data, as the server sent it, for [userId], unless the user has progress
     * for it that is still to be sent to the server: that is newer, and stays.
     */
    suspend fun storeUserDataOfDownload(userId: UserId, item: Item)

    /** Whether [userId] has user data for [itemId] still to be sent to the server. */
    suspend fun hasUserDataToBeSynced(userId: UserId, itemId: ItemId): Boolean

    /**
     * Deletes the user data of [itemId] that has reached the server, of every user. What is still
     * to be sent outlives the download, and the sync removes it (#281).
     */
    suspend fun deleteSyncedUserData(itemId: ItemId)

    /**
     * #966: applies [change] to the user data of [itemId] for [userId] and marks it to be sent to
     * the server, in one transaction, so the sync's refresh from the server (which leaves marked
     * rows alone) cannot land in between (CE, #635). Without a row (the item is not stored) nothing
     * is written or marked. Says whether a row was marked, and what it holds after the write.
     * [change] always answers a state: a write that may change nothing would still mark the row,
     * and a marked row is sent as it is and skipped by the refresh from the server.
     */
    suspend fun writeAndMarkToBeSynced(
        userId: UserId,
        itemId: ItemId,
        change: (PlayState) -> PlayState,
    ): MarkedUserData

    /** #966: marks the user data of [itemId] for [userId] to be sent; false without a row. */
    suspend fun markToBeSynced(userId: UserId, itemId: ItemId): Boolean

    /**
     * #966: takes the mark off the user data of [itemId] for [userId], only while it still holds
     * [written]: a later change keeps its mark.
     */
    suspend fun clearToBeSynced(userId: UserId, itemId: ItemId, written: PlayState)

    /**
     * #966: applies [change] to the user data of [itemId] for [userId], in one transaction, without
     * touching its mark. Without a row or when [change] answers null, nothing is written.
     */
    suspend fun updatePlayState(userId: UserId, itemId: ItemId, change: (PlayState) -> PlayState?)
}

/**
 * #966: what [DownloadUserData.writeAndMarkToBeSynced] did: whether it marked a row, and the play
 * state the row holds after the write (null without a row).
 */
data class MarkedUserData(val marked: Boolean, val written: PlayState?)
