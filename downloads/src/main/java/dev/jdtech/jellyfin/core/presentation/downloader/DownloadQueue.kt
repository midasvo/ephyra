package dev.jdtech.jellyfin.core.presentation.downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.downloads.R as DownloadsR
import dev.jdtech.jellyfin.models.DownloadFiles
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.Playable
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.repository.DataChangeEvents
import dev.jdtech.jellyfin.repository.DownloadSettings
import dev.jdtech.jellyfin.repository.DownloadSources
import dev.jdtech.jellyfin.repository.ServerAvailability
import dev.jdtech.jellyfin.utils.Downloader
import dev.jdtech.jellyfin.utils.NetworkConnectivity
import dev.jdtech.jellyfin.utils.download.DownloadStatus
import dev.jdtech.jellyfin.utils.download.DownloadTexts
import dev.jdtech.jellyfin.utils.download.estimateCappedDownloadSize
import dev.jdtech.jellyfin.utils.download.isPermanentHttpFailure
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * CE (#560): whether [entries] hold anything the queue still has to do: a download that is running
 * or paused, or one that waits for a free slot, its retry or the network. The pump runs for as long
 * as this holds, and the download service runs for as long as the pump does.
 */
internal fun hasDownloadWork(entries: List<DownloadQueue.Entry>): Boolean = entries.any {
    it.isDownloadWork()
}

internal fun DownloadQueue.Entry.isDownloadWork(): Boolean =
    state is DownloadQueue.EntryState.Downloading ||
        state is DownloadQueue.EntryState.Pending ||
        state is DownloadQueue.EntryState.Paused

/**
 * Central download scheduler. Holds a list of queued/active downloads, respects the user's
 * max-concurrent-downloads setting, and drives Downloader.downloadItem() as slots open up. Single
 * source of truth for download state across the app.
 */
@Singleton
class DownloadQueue
@Inject
constructor(
    private val downloader: Downloader,
    private val settings: DownloadSettings,
    private val downloadSources: DownloadSources,
    @ApplicationContext private val context: Context,
    // CE (#901): the texts of core's resources.
    private val texts: DownloadTexts,
    private val dataChanges: DataChangeEvents = DataChangeEvents(),
    private val connectivity: NetworkConnectivity = AlwaysOnline,
    // CE (#912): a download that failed for want of the server goes on when the server is back.
    private val serverAvailability: ServerAvailability = ServerAvailability.NONE,
) {
    sealed interface EntryState {
        data object Pending : EntryState

        data object Downloading : EntryState

        data object Paused : EntryState

        data object Completed : EntryState

        data class Failed(val error: UiText?) : EntryState
    }

    data class Entry(
        val id: ItemId,
        val item: Item,
        val addedAt: Long,
        val state: EntryState,
        val downloadId: Long? = null,
        val startedAt: Long? = null,
        /** 0..100 */
        val progress: Int = 0,
        /** Bytes downloaded so far, -1 if unknown. */
        val bytesDownloaded: Long = -1L,
        /** Total bytes, -1 if unknown. */
        val totalBytes: Long = -1L,
        /**
         * True when [totalBytes] is an estimate rather than a figure from the download engine —
         * happens for transcodes, whose length is not known up front so we fall back to the item's
         * original file size.
         */
        val totalBytesEstimated: Boolean = false,
        /** True when the server is transcoding this download (e.g. Dolby Vision). */
        val isTranscode: Boolean = false,
        /**
         * User-selected download quality cap (bits/sec) for this entry, or null for original
         * quality. Carried through retries.
         */
        val maxBitrate: Int? = null,
        /**
         * True when Smart Downloads queued this entry, which is what lets it remove the download
         * once the episode has been watched.
         */
        val smartQueued: Boolean = false,
        /** Moving-average bytes/sec, or 0 if not computed yet. */
        val bytesPerSecond: Long = 0L,
        /** How many times this entry has been auto-retried. */
        val retryCount: Int = 0,
        /** Epoch ms at which an auto-retry should fire, or null. */
        val retryAt: Long? = null,
        /**
         * CE (#530): a Pending entry held back because the device has no network. It starts by
         * itself once the network returns; the UI shows it as waiting for the network.
         */
        val waitingForNetwork: Boolean = false,
        /**
         * CE (#912): a Failed entry that failed for want of the server (it did not answer, as
         * opposed to refusing the download), so it goes on by itself when the server is back.
         */
        val retryWhenServerReturns: Boolean = false,
        /**
         * CE (#1003): attempt id held while [startDownload] is in flight, so a stale start's result
         * never attaches to a newer entry for the same item.
         */
        val startAttemptId: Long? = null,
    )

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /**
     * CE (#560): true from the moment the pump is started until it finds no work left. Both ends
     * are decided under the mutex, so this is the one place that says whether the download service
     * is needed; the service follows it instead of counting entries by itself.
     */
    private val _pumpActive = MutableStateFlow(false)
    val pumpActive: StateFlow<Boolean> = _pumpActive.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    init {
        scope.launch {
            serverAvailability.serverConfirmed.collect {
                lastServerConfirmedAt = System.nanoTime()
                retryAfterServerReturned()
            }
        }
    }

    /**
     * CE (#912): when the server was last confirmed, so a confirmation that arrives while the pump
     * is publishing an engine failure still counts for that failure ([pump] checks it).
     */
    @Volatile private var lastServerConfirmedAt = 0L

    // Refreshed at most every MEDIA_STREAM_RETRY_INTERVAL_MS, keyed by downloadId, by a
    // fire-and-forget check so the tight progress-polling loop below is never blocked on
    // a network call.
    private val lastMediaStreamRetryCheck = ConcurrentHashMap<Long, Long>()
    private var pumpJob: Job? = null
    private val nextStartAttemptId = AtomicLong(0L)

    /** Previous bytes + wall-clock (ms) sample per downloadId, for speed calc. */
    private val lastSamples = mutableMapOf<Long, Pair<Long, Long>>()

    suspend fun enqueue(item: Item, maxBitrate: Int? = null, smartQueued: Boolean = false) {
        var persisted = false
        var claimed = false
        mutex.withLock {
            val existing =
                _entries.value.firstOrNull {
                    it.id == item.id &&
                        it.state !is EntryState.Failed &&
                        it.state !is EntryState.Completed
                }
            if (existing != null) {
                // The user asking for a download Smart Downloads already queued makes it theirs,
                // so it is never removed automatically.
                if (existing.smartQueued && !smartQueued) {
                    _entries.value =
                        _entries.value.map {
                            if (it === existing) it.copy(smartQueued = false) else it
                        }
                    claimed = true
                }
                return@withLock
            }
            // If a previous Failed/Completed entry exists for this id, replace it.
            val filtered = _entries.value.filter { it.id != item.id }
            val newEntry =
                Entry(
                    id = item.id,
                    item = item,
                    addedAt = System.currentTimeMillis(),
                    state = EntryState.Pending,
                    maxBitrate = maxBitrate,
                    smartQueued = smartQueued,
                )
            _entries.value = sort(filtered + newEntry)
            persisted = true
        }
        if (persisted) {
            try {
                downloader.savePendingDownload(item, maxBitrate, smartQueued)
            } catch (e: Exception) {
                Timber.e(e, "Failed to persist pending download ${item.name}")
            }
        }
        if (claimed) claimSmartQueued(item.id)
        ensurePump()
    }

    private suspend fun claimSmartQueued(itemId: ItemId) {
        try {
            downloadSources.clearSmartQueued(itemId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to mark download ${itemId.asString()} as user-started")
        }
    }

    /**
     * Re-attaches an in-flight download started in a previous app session. No-op if the item is
     * already tracked.
     */
    suspend fun restore(item: Item, downloadId: Long) {
        mutex.withLock {
            val existing = _entries.value.firstOrNull { it.id == item.id }
            // If it's already live (pending/downloading), leave it alone.
            if (
                existing != null &&
                    existing.state !is EntryState.Failed &&
                    existing.state !is EntryState.Completed
            ) {
                return@withLock
            }
            // Replace stale Failed/Completed entry so the UI reflects the
            // in-flight download instead of the old terminal state.
            val filtered = _entries.value.filter { it.id != item.id }
            val entry =
                Entry(
                    id = item.id,
                    item = item,
                    addedAt = System.currentTimeMillis(),
                    state = EntryState.Downloading,
                    downloadId = downloadId,
                    startedAt = System.currentTimeMillis(),
                )
            _entries.value = sort(filtered + entry)
        }
        ensurePump()
    }

    /**
     * Re-attaches every in-flight download from the DB. Call on app startup to recover queue state
     * after process death. Unlike the old DownloadManager model (where DM continued running
     * independently), the OkHttp engine is in-process and does NOT survive process death. So
     * restored active partials are added as [EntryState.Pending] rather than Downloading, causing
     * the pump to call startDownload -> downloadItem, which detects the existing source row and
     * resumes from the partial file via Range. Ordering: restored actives sort before fresh
     * pendings via their addedAt timestamp.
     */
    suspend fun restoreAll() {
        // CE (#573): subtitles of finished downloads that older builds left as `.download`.
        try {
            downloader.finalizeFinishedMediaStreams()
        } catch (e: Exception) {
            Timber.e(e, "Failed to finalize the subtitles of finished downloads")
        }
        val active =
            try {
                downloader.getActiveDownloads()
            } catch (e: Exception) {
                Timber.e(e, "Failed to query active downloads for restore")
                emptyList()
            }
        val pending =
            try {
                downloader.getPendingDownloads()
            } catch (e: Exception) {
                Timber.e(e, "Failed to query pending downloads for restore")
                emptyList()
            }
        if (active.isEmpty() && pending.isEmpty()) return
        mutex.withLock {
            val known = _entries.value.map { it.id }.toSet()
            val now = System.currentTimeMillis()
            // Restored active partials re-enter as Pending so the pump drives the resume
            // through downloadItem (which detects the existing source row and does Range resume).
            // Use addedAt = now - 1 so they sort before brand-new pending entries.
            val addedActive =
                active
                    .filter { it.item.id !in known }
                    .map { restored ->
                        Entry(
                            id = restored.item.id,
                            item = restored.item,
                            addedAt = now - 1,
                            state = EntryState.Pending,
                            maxBitrate = restored.maxBitrate,
                            smartQueued = restored.smartQueued,
                        )
                    }
            // Pending items entered after active downloads so they don't cut in line.
            val activeIds = addedActive.map { it.id }.toSet()
            val addedPending =
                pending
                    .filter { it.item.id !in known && it.item.id !in activeIds }
                    .map { restored ->
                        Entry(
                            id = restored.item.id,
                            item = restored.item,
                            addedAt = restored.addedAt,
                            state = EntryState.Pending,
                            maxBitrate = restored.maxBitrate,
                            smartQueued = restored.smartQueued,
                        )
                    }
            val added = addedActive + addedPending
            if (added.isEmpty()) return@withLock
            _entries.value = sort(_entries.value + added)
        }
        ensurePump()
    }

    suspend fun enqueueAll(items: List<Item>, maxBitrate: Int? = null) {
        val persistedItems = mutableListOf<Item>()
        // Already queued by Smart Downloads; the user asking for them makes them theirs.
        val claimedIds = mutableSetOf<ItemId>()
        mutex.withLock {
            val existingIds =
                _entries.value
                    .filter { it.state !is EntryState.Failed && it.state !is EntryState.Completed }
                    .map { it.id }
                    .toSet()
            val itemIds = items.map { it.id }.toSet()
            _entries.value.forEach {
                if (it.smartQueued && it.id in existingIds && it.id in itemIds)
                    claimedIds.add(it.id)
            }
            if (claimedIds.isNotEmpty()) {
                _entries.value =
                    _entries.value.map {
                        if (it.id in claimedIds) it.copy(smartQueued = false) else it
                    }
            }
            val now = System.currentTimeMillis()
            val newEntries =
                items
                    .filter { it.id !in existingIds }
                    .mapIndexed { idx, item ->
                        Entry(
                            id = item.id,
                            item = item,
                            addedAt = now + idx, // preserve insertion order
                            state = EntryState.Pending,
                            maxBitrate = maxBitrate,
                        )
                    }
            if (newEntries.isEmpty()) return@withLock
            val newIds = newEntries.map { it.id }.toSet()
            val kept = _entries.value.filter { it.id !in newIds }
            _entries.value = sort(kept + newEntries)
            persistedItems.addAll(newEntries.map { it.item })
        }
        for (item in persistedItems) {
            try {
                downloader.savePendingDownload(item, maxBitrate)
            } catch (e: Exception) {
                Timber.e(e, "Failed to persist pending download ${item.name}")
            }
        }
        claimedIds.forEach { claimSmartQueued(it) }
        ensurePump()
    }

    /**
     * Cancels a download that has not finished (its partial file and rows go too) or drops a
     * completed entry.
     */
    fun remove(id: ItemId) {
        scope.launch {
            var cancelEntry: Entry? = null
            mutex.withLock {
                val target = _entries.value.firstOrNull { it.id == id } ?: return@withLock
                if (target.state !is EntryState.Completed) {
                    cancelEntry = target
                }
                _entries.value = _entries.value.filter { it.id != id }
            }
            try {
                downloader.removePendingDownload(id)
            } catch (e: Exception) {
                Timber.e(e, "Failed to drop persisted pending row for ${id.asString()}")
            }
            cancelEntry?.let { entry ->
                for (dlId in unfinishedDownloadIds(entry)) {
                    try {
                        downloader.cancelDownload(entry.item, dlId)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to cancel download for ${entry.item.name}")
                    }
                }
            }
            ensurePump()
        }
    }

    /**
     * The downloads to cancel for [entry]. Not only a running or paused one: a download waiting for
     * its auto-retry, a failed one and one restored at start have no downloadId on the entry but
     * keep their source row and partial file, which would bring them back at the next start (#426).
     * Their ids are on the source rows.
     */
    private suspend fun unfinishedDownloadIds(entry: Entry): List<Long> {
        val fromRows =
            try {
                downloadSources
                    .sources(entry.id)
                    .filter { DownloadFiles.isPartial(it.path) }
                    .mapNotNull { it.downloadId }
            } catch (e: Exception) {
                Timber.e(e, "Failed to read the source rows of ${entry.item.name}")
                emptyList()
            }
        return (listOfNotNull(entry.downloadId) + fromRows).distinct()
    }

    /** Re-queues a failed entry. */
    fun retry(id: ItemId) {
        scope.launch { requeueFailed { it.id == id } }
    }

    /**
     * CE (#912): the server answers again, so the downloads that failed for want of it go on from
     * their partial files, as if the user had pressed Retry. Others stay failed: a refusal by the
     * server is no different now.
     */
    private suspend fun retryAfterServerReturned() {
        if (requeueFailed { it.retryWhenServerReturns }) {
            Timber.i("Server reachable again: resuming the downloads that failed without it")
        }
    }

    /** Puts the Failed entries that match back to Pending. True when there were any. */
    private suspend fun requeueFailed(matches: (Entry) -> Boolean): Boolean {
        val retried = mutableListOf<Entry>()
        mutex.withLock {
            _entries.value =
                sort(
                    _entries.value.map { entry ->
                        if (entry.state is EntryState.Failed && matches(entry)) {
                            retried.add(entry)
                            entry.copy(
                                state = EntryState.Pending,
                                addedAt = System.currentTimeMillis(),
                                downloadId = null,
                                startedAt = null,
                                progress = 0,
                                retryCount = 0,
                                retryAt = null,
                                retryWhenServerReturns = false,
                            )
                        } else {
                            entry
                        }
                    }
                )
        }
        for (entry in retried) {
            try {
                downloader.savePendingDownload(entry.item, entry.maxBitrate, entry.smartQueued)
            } catch (e: Exception) {
                Timber.e(e, "Failed to persist retried download ${entry.item.name}")
            }
        }
        if (retried.isNotEmpty()) ensurePump()
        return retried.isNotEmpty()
    }

    /** Removes all Completed entries from the queue view. */
    fun clearCompleted() {
        scope.launch {
            mutex.withLock {
                _entries.value = _entries.value.filter { it.state !is EntryState.Completed }
            }
        }
    }

    /**
     * True when the item has a Dolby Vision video stream. Those downloads are transcoded
     * server-side (so they play offline), which makes their final size unknown while in flight.
     */
    private fun Item.hasDolbyVision(): Boolean =
        (this as? Playable)?.sources?.any { source ->
            source.mediaStreams.any { it.videoDoViTitle != null }
        } == true

    private fun sort(entries: List<Entry>): List<Entry> {
        fun priority(state: EntryState): Int =
            when (state) {
                is EntryState.Downloading -> 0
                is EntryState.Paused -> 0
                is EntryState.Pending -> 1
                is EntryState.Failed -> 2
                is EntryState.Completed -> 3
            }
        return entries.sortedWith(
            compareBy({ priority(it.state) }, { it.startedAt ?: it.addedAt }, { it.addedAt })
        )
    }

    private suspend fun ensurePump() {
        mutex.withLock {
            if (pumpJob?.isActive == true) return
            // Keep the app process alive while we have work to pump. The service
            // stops itself once the pump is no longer active.
            _pumpActive.value = true
            DownloadPumpService.start(context)
            pumpJob = scope.launch {
                try {
                    pump()
                } catch (e: Exception) {
                    Timber.e(e, "DownloadQueue pump crashed")
                    // Nothing pumps any more: let the service go. The next ensurePump()
                    // starts both again.
                    _pumpActive.value = false
                }
            }
        }
    }

    private suspend fun pump() {
        while (true) {
            // 1. Poll active downloads and transition completed/failed. Paused entries
            //    keep their engine task active, so poll them too so we can
            //    flip back to Downloading when the engine resumes (e.g. wifi reconnects).
            val active =
                _entries.value.filter {
                    it.state is EntryState.Downloading || it.state is EntryState.Paused
                }
            // Prune speed samples for download ids no longer active (removed,
            // completed, or failed).
            if (lastSamples.isNotEmpty()) {
                val activeDlIds = active.mapNotNull { it.downloadId }.toSet()
                lastSamples.keys.retainAll(activeDlIds)
                lastMediaStreamRetryCheck.keys.retainAll(activeDlIds)
            }
            if (active.isNotEmpty()) {
                val updates = mutableMapOf<ItemId, Entry>()
                // Refused by the server in a way asking again won't change (#383).
                val permanentFailures = mutableSetOf<ItemId>()
                val now = System.currentTimeMillis()
                val dlIds = active.mapNotNull { it.downloadId }
                val polledAt = System.nanoTime()
                val snapshots = downloader.getProgress(dlIds)
                for (entry in active) {
                    val dlId = entry.downloadId ?: continue
                    val snapshot =
                        snapshots[dlId] ?: Downloader.Progress(DownloadStatus.FAILED, 0, -1L, -1L)
                    val newState: EntryState? =
                        when (snapshot.status) {
                            DownloadStatus.PENDING,
                            DownloadStatus.RUNNING ->
                                // If we were Paused and the engine is running again, flip back
                                // to Downloading so the user sees progress resume.
                                if (entry.state is EntryState.Paused) EntryState.Downloading
                                else null
                            DownloadStatus.PAUSED ->
                                if (entry.state is EntryState.Paused) null else EntryState.Paused
                            DownloadStatus.SUCCESSFUL -> EntryState.Completed
                            DownloadStatus.FAILED -> {
                                val httpStatus = snapshot.httpStatus
                                if (httpStatus != null && isPermanentHttpFailure(httpStatus)) {
                                    permanentFailures.add(entry.id)
                                }
                                EntryState.Failed(httpStatus?.let(::serverRefused))
                            }
                            // Unknown/unhandled status (e.g. engine entry disappeared).
                            // Treat as failed rather than silently claiming success.
                            else -> EntryState.Failed(null)
                        }
                    // A server-side transcode (e.g. a Dolby Vision download) streams
                    // output of unknown length, so the engine reports totalBytes=-1.
                    // Fall back to the item's original file size as an estimate, so the
                    // UI can show an approximate %/ETA instead of a frozen 0%.
                    val originalSize =
                        (entry.item as? Playable)?.sources?.maxOfOrNull { it.size } ?: 0L
                    // A quality-capped transcode is far smaller than the original, so
                    // estimate it from the cap instead (never above the original size).
                    val cappedSize =
                        entry.maxBitrate?.let {
                            estimateCappedDownloadSize(entry.item.runtimeTicks, it)
                        } ?: 0L
                    val estimatedSize =
                        when {
                            cappedSize <= 0L -> originalSize
                            originalSize <= 0L -> cappedSize
                            else -> minOf(cappedSize, originalSize)
                        }
                    val estimating =
                        snapshot.totalBytes <= 0L &&
                            estimatedSize > 0L &&
                            snapshot.bytesDownloaded in 0 until estimatedSize
                    val effectiveTotal = if (estimating) estimatedSize else snapshot.totalBytes
                    // External subtitle/media-stream downloads have no retry/backoff of
                    // their own (unlike this entry's video downloadId, tracked above) — a
                    // transient failure otherwise permanently drops that subtitle track
                    // with no recovery. Throttled and fire-and-forget so this network call
                    // never blocks the poll loop; only while genuinely downloading, not
                    // paused/offline.
                    if (entry.state is EntryState.Downloading) {
                        val lastCheck = lastMediaStreamRetryCheck[dlId] ?: 0L
                        if (now - lastCheck >= MEDIA_STREAM_RETRY_INTERVAL_MS) {
                            lastMediaStreamRetryCheck[dlId] = now
                            val item = entry.item
                            scope.launch {
                                try {
                                    downloader.retryFailedMediaStreams(item)
                                } catch (e: Exception) {
                                    Timber.w(e, "Failed to retry media streams for ${item.name}")
                                }
                            }
                        }
                    }
                    val newProgress =
                        if (estimating) {
                            // Cap at 99: the estimate may undershoot, and we only want
                            // to show 100% once the download has genuinely completed.
                            (snapshot.bytesDownloaded * 100 / estimatedSize).toInt().coerceIn(0, 99)
                        } else {
                            snapshot.progress.coerceAtLeast(0).coerceAtMost(100)
                        }
                    val speedSample =
                        nextDownloadSpeed(
                            prevSample = lastSamples[dlId],
                            currentBytes = snapshot.bytesDownloaded,
                            nowMs = now,
                            previousSpeed = entry.bytesPerSecond,
                        )
                    val speed = speedSample.bytesPerSecond
                    if (speedSample.advanced) {
                        lastSamples[dlId] = snapshot.bytesDownloaded to now
                    }
                    val bytesChanged =
                        snapshot.bytesDownloaded != entry.bytesDownloaded ||
                            effectiveTotal != entry.totalBytes ||
                            estimating != entry.totalBytesEstimated
                    val speedChanged = isSignificantSpeedChange(entry.bytesPerSecond, speed)
                    if (
                        newState != null ||
                            newProgress != entry.progress ||
                            bytesChanged ||
                            speedChanged
                    ) {
                        updates[entry.id] =
                            entry.copy(
                                state = newState ?: entry.state,
                                progress =
                                    if (newState == EntryState.Completed) 100 else newProgress,
                                bytesDownloaded = snapshot.bytesDownloaded,
                                totalBytes = effectiveTotal,
                                totalBytesEstimated = estimating,
                                // Clear speed once terminal; stale numbers confuse the UI.
                                bytesPerSecond = if (newState != null) 0L else speed,
                            )
                    }
                }
                if (updates.isNotEmpty()) {
                    // Finalize completed downloads (rename .download → final, update
                    // DB path) BEFORE pushing the new state to observers. The
                    // DownloadsViewModel reacts to busy→idle by reloading the
                    // library — if we push first, that reload races the rename
                    // and the library tab comes up empty.
                    val finished = updates.values.filter { it.state is EntryState.Completed }
                    for (entry in finished) {
                        val dlId = entry.downloadId ?: continue
                        val finalized =
                            try {
                                downloader.finalizeDownload(dlId)
                            } catch (e: Exception) {
                                Timber.e(e, "finalizeDownload threw for ${entry.item.name}")
                                false
                            }
                        if (!finalized) {
                            // CE (#848): the transfer is done but the file is not where the item
                            // is played from, so it is not completed. The downloader keeps the
                            // finished `.download` file and its row: Retry finalizes it again. A
                            // local failure such as a full disk does not go away by itself, so no
                            // auto-retry that could download a transcode again unasked.
                            Timber.e("Failed to finalize ${entry.item.name} (id=$dlId)")
                            updates[entry.id] =
                                entry.copy(state = EntryState.Failed(texts.downloadingError()))
                            permanentFailures.add(entry.id)
                        }
                    }
                    val completedNow = updates.values.filter { it.state is EntryState.Completed }
                    // A failed entry keeps its partial .download file and source row so
                    // the next attempt (auto-retry or manual retry) can resume from where
                    // it left off via Range. We only clear the speed sample — no file/row cleanup.
                    val failedEntries = updates.values.filter { it.state is EntryState.Failed }
                    for (failed in failedEntries) {
                        val dlId = failed.downloadId ?: continue
                        lastSamples.remove(dlId)
                    }
                    // Schedule auto-retry for eligible entries. CE (#560): they go straight to
                    // waiting for the retry, in the same update as everything else. Published as
                    // Failed first, a queue with nothing else in flight looked finished in between.
                    val exhausted = mutableListOf<Entry>()
                    for (failed in failedEntries) {
                        val next =
                            afterFailure(
                                failed = failed,
                                permanent = failed.id in permanentFailures,
                                now = System.currentTimeMillis(),
                            )
                        if (next.state is EntryState.Pending) {
                            updates[failed.id] = next
                            Timber.i(
                                "Auto-retry #${next.retryCount} for ${failed.item.name} in ${retryBackoffMs(failed.retryCount) / 1000}s"
                            )
                            try {
                                downloader.savePendingDownload(
                                    failed.item,
                                    failed.maxBitrate,
                                    failed.smartQueued,
                                )
                            } catch (e: Exception) {
                                Timber.e(
                                    e,
                                    "Failed to re-persist auto-retried download ${failed.item.name}",
                                )
                            }
                        } else {
                            updates[failed.id] = next
                            exhausted.add(failed)
                        }
                    }
                    mutex.withLock {
                        _entries.value = sort(_entries.value.map { updates[it.id] ?: it })
                    }
                    // CE (#912): the server was confirmed while these failures were being decided:
                    // that confirmation found them still running, so it is applied now.
                    if (
                        exhausted.isNotEmpty() &&
                            lastServerConfirmedAt != 0L &&
                            lastServerConfirmedAt - polledAt >= 0
                    ) {
                        retryAfterServerReturned()
                    }
                    // A finished download changes what the screens show (#251).
                    if (completedNow.isNotEmpty()) dataChanges.onChanged()
                    // Notify for the failures that stay failed.
                    exhausted.forEach { notifyFailure(it.item) }
                    val completedEntries =
                        updates.values.filter { it.state is EntryState.Completed }
                    for (entry in completedEntries) {
                        entry.downloadId?.let { lastSamples.remove(it) }
                    }
                }
            }

            // 2. Fill free slots from Pending queue. Without a network nothing can start: the
            //    pending entries wait for it instead of failing (#530).
            val online = connectivity.isOnline()
            markWaitingForNetwork(waiting = !online)
            val maxConcurrent = settings.maxConcurrentDownloads
            val currentlyActive =
                _entries.value.count {
                    it.state is EntryState.Downloading || it.state is EntryState.Paused
                }
            val freeSlots = (maxConcurrent - currentlyActive).coerceAtLeast(0)
            if (online && freeSlots > 0) {
                val now = System.currentTimeMillis()
                val pending =
                    _entries.value
                        .filter {
                            it.state is EntryState.Pending &&
                                it.startAttemptId == null &&
                                (it.retryAt == null || it.retryAt <= now)
                        }
                        .take(freeSlots)
                for (entry in pending) {
                    startDownload(entry)
                }
            }

            // 3. Decide whether to keep pumping (under mutex to avoid starvation race
            //    with enqueue calling ensurePump between our check and our nulling of
            //    pumpJob).
            val shouldExit = mutex.withLock {
                if (!hasDownloadWork(_entries.value)) {
                    pumpJob = null
                    _pumpActive.value = false
                    true
                } else {
                    false
                }
            }
            if (shouldExit) return
            delay(pollIntervalMs(_entries.value.count { it.state is EntryState.Downloading }))
        }
    }

    private suspend fun startDownload(entry: Entry) {
        val attemptId = nextStartAttemptId.incrementAndGet()
        val activeEntry = mutex.withLock {
            val current =
                _entries.value.firstOrNull {
                    it.id == entry.id && it.state is EntryState.Pending && it.startAttemptId == null
                } ?: return
            val marked = current.copy(startAttemptId = attemptId)
            _entries.value = _entries.value.map { if (it === current) marked else it }
            marked
        }
        val storageIndex = settings.downloadStorageIndex?.toIntOrNull() ?: 0
        // A Dolby Vision item (with the setting on) or a quality-capped download may
        // be transcoded server-side, which makes the download's length unknown up
        // front — flag it so the UI can say so.
        val isTranscode =
            activeEntry.maxBitrate != null ||
                (settings.downloadTranscodeDolbyVision && activeEntry.item.hasDolbyVision())
        // Drop the pending row *before* running setup. If the process dies
        // mid-setup, restoreAll() on next launch would otherwise resurrect the
        // pending entry and start a duplicate engine task + duplicate source row
        // on top of whatever partial state setup left behind.
        try {
            downloader.removePendingDownload(activeEntry.id)
        } catch (e: Exception) {
            Timber.e(e, "Failed to drop persisted pending row for ${activeEntry.item.name}")
        }
        val (downloadId, errorText) =
            try {
                downloader.downloadItem(
                    item = activeEntry.item,
                    storageIndex = storageIndex,
                    maxBitrate = activeEntry.maxBitrate,
                    smartQueued = activeEntry.smartQueued,
                )
            } catch (e: Exception) {
                Timber.e(e, "downloadItem threw for ${activeEntry.item.name}")
                // Surface something to the Queue UI instead of a blank error — the
                // raw exception message could leak URLs/stack info, so pick a
                // generic localized string.
                Pair(-1L, texts.downloadingError())
            }

        // A start that failed because the network went away is not a failure of the download: it
        // waits for the network as the queue does and is not counted as a retry (#530). A failure
        // while online stays Failed, with Retry.
        val waitForNetwork = downloadId == -1L && !connectivity.isOnline()
        var orphaned = false
        var persistWaitForNetwork: Entry? = null
        mutex.withLock {
            val current =
                _entries.value.firstOrNull {
                    it.id == activeEntry.id && it.startAttemptId == attemptId
                }
            if (current == null) {
                // Entry was removed (user cancelled) — possibly followed by a new enqueue —
                // while we were starting the download (#1003). The engine task is running but
                // does not belong to any live attempt, so cancel it instead of attaching it to
                // a newer entry.
                orphaned = downloadId != -1L
                return@withLock
            }
            val updated =
                if (downloadId != -1L) {
                    current.copy(
                        state = EntryState.Downloading,
                        downloadId = downloadId,
                        startedAt = System.currentTimeMillis(),
                        isTranscode = isTranscode,
                        waitingForNetwork = false,
                        startAttemptId = null,
                    )
                } else if (waitForNetwork) {
                    current
                        .copy(
                            state = EntryState.Pending,
                            waitingForNetwork = true,
                            startAttemptId = null,
                        )
                        .also { persistWaitForNetwork = it }
                } else {
                    // CE (#912): the start failed while the network is there, most likely
                    // because the server did not answer (also after an app restart, with
                    // the server still away): it goes on when the server is back. A start that
                    // fails for a local reason fails again at once, without a download.
                    current.copy(
                        state = EntryState.Failed(errorText),
                        retryWhenServerReturns = true,
                        startAttemptId = null,
                    )
                }
            _entries.value = sort(_entries.value.map { if (it === current) updated else it })
        }
        persistWaitForNetwork?.let { waitingEntry ->
            try {
                downloader.savePendingDownload(
                    waitingEntry.item,
                    waitingEntry.maxBitrate,
                    waitingEntry.smartQueued,
                )
            } catch (e: Exception) {
                Timber.e(
                    e,
                    "Failed to persist download ${waitingEntry.item.name} waiting for network",
                )
            }
        }
        if (orphaned) {
            try {
                downloader.cancelDownload(activeEntry.item, downloadId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to clean up orphaned download ${activeEntry.item.name}")
            }
        }
    }

    /** Marks the Pending entries as waiting for the network, or clears the mark (#530). */
    private suspend fun markWaitingForNetwork(waiting: Boolean) {
        fun stale(entry: Entry) =
            entry.state is EntryState.Pending && entry.waitingForNetwork != waiting
        if (_entries.value.none(::stale)) return
        mutex.withLock {
            _entries.value =
                _entries.value.map { if (stale(it)) it.copy(waitingForNetwork = waiting) else it }
        }
    }

    /** The reason a download the server answered with an HTTP error shows (#383). */
    private fun serverRefused(httpStatus: Int): UiText =
        UiText.StringResource(DownloadsR.string.ce_download_error_server, httpStatus)

    private fun notifyFailure(item: Item) {
        val nm = context.getSystemService<NotificationManager>() ?: return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                nm.getNotificationChannel(FAILURE_CHANNEL_ID) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(
                    FAILURE_CHANNEL_ID,
                    context.getString(DownloadsR.string.download_failures_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
        val title =
            if (item is Episode) {
                "${item.seriesName} · S%02dE%02d".format(item.parentIndexNumber, item.indexNumber)
            } else {
                item.name
            }
        val notification =
            NotificationCompat.Builder(context, FAILURE_CHANNEL_ID)
                .setSmallIcon(texts.failedNotificationIcon)
                .setContentTitle(context.getString(texts.failedNotificationTitle))
                .setContentText(title)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        // Use item hashCode as id so each failed item gets its own notification.
        nm.notify(item.id.hashCode(), notification)
    }

    /** For a queue built by hand, as the tests do: connectivity is not part of what they test. */
    private object AlwaysOnline : NetworkConnectivity {
        override fun isOnline() = true

        override fun isMetered() = false

        override fun isRoaming() = false
    }

    companion object {
        private const val FAILURE_CHANNEL_ID = "download_failures"
        private const val MAX_AUTO_RETRIES = 3
        /** Backoff delays: 30s, 2m, 10m. */
        private val RETRY_BACKOFF_MS = longArrayOf(30_000L, 120_000L, 600_000L)

        /** Minimum gap between checks for failed external subtitle/media-stream downloads. */
        private const val MEDIA_STREAM_RETRY_INTERVAL_MS = 30_000L

        private fun retryBackoffMs(retryCount: Int): Long =
            RETRY_BACKOFF_MS[retryCount.coerceIn(0, RETRY_BACKOFF_MS.lastIndex)]

        /**
         * CE (#560): what a download the engine [failed] becomes. With auto-retries left it waits
         * for the next one; with none left, or refused by the server for good ([permanent], #383),
         * it stays failed. CE (#912): one that stays failed for want of an answer (the server gave
         * no reason, no HTTP status) goes on when the server is back.
         */
        internal fun afterFailure(failed: Entry, permanent: Boolean, now: Long): Entry =
            if (permanent || failed.retryCount >= MAX_AUTO_RETRIES) {
                val unanswered = (failed.state as? EntryState.Failed)?.error == null
                failed.copy(retryWhenServerReturns = !permanent && unanswered)
            } else {
                failed.copy(
                    state = EntryState.Pending,
                    downloadId = null,
                    startedAt = null,
                    progress = 0,
                    retryCount = failed.retryCount + 1,
                    retryAt = now + retryBackoffMs(failed.retryCount),
                )
            }
    }
}
