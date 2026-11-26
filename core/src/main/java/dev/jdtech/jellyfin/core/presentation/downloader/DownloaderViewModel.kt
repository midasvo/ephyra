package dev.jdtech.jellyfin.core.presentation.downloader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.isDownloading
import dev.jdtech.jellyfin.repository.DownloadDeleter
import dev.jdtech.jellyfin.repository.DownloadEntryState
import dev.jdtech.jellyfin.repository.DownloadQueuePort
import dev.jdtech.jellyfin.utils.download.DownloadStatus
import dev.jdtech.jellyfin.utils.toUiText
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@HiltViewModel
class DownloaderViewModel
@Inject
constructor(
    private val downloadDeleter: DownloadDeleter,
    private val downloadQueue: DownloadQueuePort,
) : ViewModel() {
    private val _state = MutableStateFlow(DownloaderState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<DownloaderEvent>()
    val events = eventsChannel.receiveAsFlow()

    private var trackedItemId: ItemId? = null
    private var observerJob: Job? = null
    private var wasCompleted = false
    private var wasFailed = false

    fun update(item: Item) {
        if (trackedItemId == item.id && observerJob?.isActive == true) return
        trackedItemId = item.id
        wasCompleted = false
        wasFailed = false

        // Restore if in-flight from a previous session
        if (item.isDownloading()) {
            val localSource = item.sources.firstOrNull { it.type == ItemSourceType.LOCAL }
            val dlId = localSource?.downloadId
            if (dlId != null) {
                viewModelScope.launch { downloadQueue.restore(item, dlId) }
            }
        }

        startObserving(item.id)
    }

    private fun startObserving(itemId: ItemId) {
        observerJob?.cancel()
        observerJob = viewModelScope.launch {
            downloadQueue.queueEntries.collect { entries ->
                val entry = entries.firstOrNull { it.id == itemId }
                val newState =
                    when (val s = entry?.state) {
                        is DownloadEntryState.Pending ->
                            DownloaderState(
                                status =
                                    if (entry.waitingForNetwork) DownloadStatus.PAUSED
                                    else DownloadStatus.PENDING
                            )
                        is DownloadEntryState.Downloading ->
                            DownloaderState(
                                status = DownloadStatus.RUNNING,
                                progress = entry.progress / 100f,
                            )
                        is DownloadEntryState.Paused ->
                            DownloaderState(
                                status = DownloadStatus.PAUSED,
                                progress = entry.progress / 100f,
                            )
                        is DownloadEntryState.Completed ->
                            DownloaderState(status = DownloadStatus.SUCCESSFUL, progress = 1f)
                        is DownloadEntryState.Failed ->
                            DownloaderState(
                                status = DownloadStatus.FAILED,
                                errorText = s.error?.toUiText(),
                            )
                        null -> DownloaderState()
                    }
                if (newState.status == DownloadStatus.SUCCESSFUL && !wasCompleted) {
                    wasCompleted = true
                    eventsChannel.trySend(DownloaderEvent.Successful)
                }
                // Only fire the Failed event on an actual transition — otherwise
                // revisiting a screen with an already-failed item would re-toast
                // every time.
                val prevStatus = _state.value.status
                val wasInFlight =
                    prevStatus == DownloadStatus.RUNNING || prevStatus == DownloadStatus.PENDING
                if (newState.status == DownloadStatus.FAILED && !wasFailed && wasInFlight) {
                    wasFailed = true
                    eventsChannel.trySend(DownloaderEvent.Failed(newState.errorText))
                }
                _state.emit(newState)
            }
        }
    }

    private fun download(item: Item, maxBitrate: Int?) {
        trackedItemId = item.id
        wasCompleted = false
        wasFailed = false
        viewModelScope.launch { downloadQueue.enqueue(item, maxBitrate) }
        startObserving(item.id)
    }

    private fun cancelDownload(item: Item) {
        downloadQueue.remove(item.id)
    }

    private fun deleteDownload(item: Item) {
        viewModelScope.launch {
            // Ensure it's not tracked as an active download anymore
            downloadQueue.remove(item.id)
            val source =
                item.sources.firstOrNull { it.type == ItemSourceType.LOCAL } ?: return@launch
            downloadDeleter.deleteItem(item = item, source = source)
            eventsChannel.send(DownloaderEvent.Deleted)
        }
    }

    fun onAction(action: DownloaderAction) {
        when (action) {
            is DownloaderAction.Download -> download(action.item, action.maxBitrate)
            is DownloaderAction.DeleteDownload -> deleteDownload(action.item)
            is DownloaderAction.CancelDownload -> cancelDownload(action.item)
        }
    }

    override fun onCleared() {
        super.onCleared()
        observerJob?.cancel()
        eventsChannel.close()
    }
}
