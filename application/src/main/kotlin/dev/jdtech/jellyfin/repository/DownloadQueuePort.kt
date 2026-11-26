package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Pure-Kotlin text carried by a failed [DownloadEntry] across the port boundary without pulling
 * Android or Compose `UiText` into `application` (rule R19).
 */
sealed interface DownloadText {
    data class Dynamic(val value: String) : DownloadText

    class Resource(val resId: Int, vararg val args: Any) : DownloadText {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Resource && resId == other.resId && args.contentEquals(other.args))

        override fun hashCode(): Int = 31 * resId + args.contentHashCode()
    }
}

sealed interface DownloadEntryState {
    data object Pending : DownloadEntryState

    data object Downloading : DownloadEntryState

    data object Paused : DownloadEntryState

    data object Completed : DownloadEntryState

    data class Failed(val error: DownloadText? = null) : DownloadEntryState
}

data class DownloadEntry(
    val id: ItemId,
    val item: Item,
    val state: DownloadEntryState,
    val downloadId: Long? = null,
    val progress: Int = 0,
    val bytesDownloaded: Long = -1L,
    val totalBytes: Long = -1L,
    val totalBytesEstimated: Boolean = false,
    val isTranscode: Boolean = false,
    val bytesPerSecond: Long = 0L,
    val waitingForNetwork: Boolean = false,
)

/**
 * Port over the in-process download queue for shared ViewModels in `core` and `modes/film`.
 * Implemented by `DownloadQueue` in `downloads`; bound to [None] on TV where downloads do not exist
 * (TV ADR 0007).
 */
interface DownloadQueuePort {
    val queueEntries: StateFlow<List<DownloadEntry>>

    suspend fun enqueue(item: Item, maxBitrate: Int? = null, smartQueued: Boolean = false)

    suspend fun enqueueAll(items: List<Item>, maxBitrate: Int? = null)

    fun remove(id: ItemId)

    fun retry(id: ItemId)

    suspend fun restore(item: Item, downloadId: Long)

    object None : DownloadQueuePort {
        override val queueEntries: StateFlow<List<DownloadEntry>> = MutableStateFlow(emptyList())

        override suspend fun enqueue(item: Item, maxBitrate: Int?, smartQueued: Boolean) = Unit

        override suspend fun enqueueAll(items: List<Item>, maxBitrate: Int?) = Unit

        override fun remove(id: ItemId) = Unit

        override fun retry(id: ItemId) = Unit

        override suspend fun restore(item: Item, downloadId: Long) = Unit
    }
}
