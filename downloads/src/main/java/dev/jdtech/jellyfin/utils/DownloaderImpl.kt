package dev.jdtech.jellyfin.utils

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.jdtech.jellyfin.downloads.R as DownloadsR
import dev.jdtech.jellyfin.models.DownloadFiles
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.ItemTrickplayInfo
import dev.jdtech.jellyfin.models.MediaSourceId
import dev.jdtech.jellyfin.models.MediaStreamId
import dev.jdtech.jellyfin.models.MediaStreamType
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.Playable
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.models.looksLikeJellyfinId
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.repository.CurrentAccount
import dev.jdtech.jellyfin.repository.CurrentServerId
import dev.jdtech.jellyfin.repository.DataChangeEvents
import dev.jdtech.jellyfin.repository.DownloadList
import dev.jdtech.jellyfin.repository.DownloadMediaStreams
import dev.jdtech.jellyfin.repository.DownloadSegmentsAndTrickplay
import dev.jdtech.jellyfin.repository.DownloadSettings
import dev.jdtech.jellyfin.repository.DownloadSources
import dev.jdtech.jellyfin.repository.DownloadUserData
import dev.jdtech.jellyfin.repository.DownloadedItemRows
import dev.jdtech.jellyfin.repository.ItemCatalog
import dev.jdtech.jellyfin.repository.PlaybackMedia
import dev.jdtech.jellyfin.repository.QueuedDownloads
import dev.jdtech.jellyfin.repository.StoredMediaStream
import dev.jdtech.jellyfin.repository.StoredPendingDownload
import dev.jdtech.jellyfin.repository.StoredSource
import dev.jdtech.jellyfin.utils.download.DownloadStatus
import dev.jdtech.jellyfin.utils.download.DownloadTexts
import dev.jdtech.jellyfin.utils.download.MediaDownloadEngine
import dev.jdtech.jellyfin.utils.download.estimateCappedDownloadSize
import dev.jdtech.jellyfin.work.ImagesDownloaderWorker
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Provider
import kotlin.Exception
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * CE (#379): how old an untracked finished file, or an artwork folder's newest file, must be before
 * the sweep removes it. The sweep runs daily, so the space comes back within two days.
 */
internal const val ORPHAN_MIN_AGE_MS = 24L * 60 * 60 * 1000

/**
 * Returns true if the file at [path] under one of the [downloadRoots] should be deleted by the
 * orphan sweep.
 * - A `.download` partial goes when no source row ([knownPaths]) and no running engine task
 *   ([livePaths]) writes to it.
 * - A finished file (CE, #379) goes only when nothing can still claim it: no row has its path, in
 *   any case or with a trailing separator, with or without `.download` ([knownFinalPaths] covers a
 *   download being renamed while its row still has the `.download` path), no running task ends in
 *   it, and it was last written at least [minAgeMs] before [now]. An unknown or future modification
 *   time keeps it.
 */
internal fun shouldDeleteOrphanFile(
    path: String,
    lastModified: Long,
    now: Long,
    downloadRoots: List<String>,
    knownPaths: Set<String>,
    knownFinalPaths: Set<String>,
    livePaths: Set<String>,
    minAgeMs: Long = ORPHAN_MIN_AGE_MS,
): Boolean {
    if (!isUnderMountedRoot(path, downloadRoots)) return false
    if (DownloadFiles.isPartial(path)) {
        if (path in knownPaths) return false
        if (path in livePaths) return false
        return true
    }
    if (knownPaths.any { sameSweepPath(it, path) }) return false
    if (knownFinalPaths.any { sameSweepPath(it, path) }) return false
    if (livePaths.any { sameSweepPath(DownloadFiles.finalPath(it), path) }) return false
    return isOldEnough(lastModified, now, minAgeMs)
}

/**
 * Returns true if the folder `files/images/<dirName>` or `files/trickplay/<dirName>` belongs to no
 * movie, show, season or episode row ([knownItemIds], every server and user) and its newest file is
 * at least [minAgeMs] old. Only the download code writes there, always for an item it has a row
 * for. A name that is not an item id is never deleted.
 */
internal fun shouldDeleteOrphanArtwork(
    dirName: String,
    newestModified: Long,
    now: Long,
    knownItemIds: Set<ItemId>,
    minAgeMs: Long = ORPHAN_MIN_AGE_MS,
): Boolean {
    val name = dirName.lowercase()
    if (!name.looksLikeJellyfinId()) return false
    val id = name.toItemId()
    if (id in knownItemIds) return false
    return isOldEnough(newestModified, now, minAgeMs)
}

/**
 * Returns true if the empty folder at [path] under a download root may be removed: never the root
 * itself, and only once it is [minAgeMs] old, so a folder a new download has just made stays.
 */
internal fun shouldPruneEmptyDir(
    path: String,
    isEmpty: Boolean,
    lastModified: Long,
    now: Long,
    downloadRoots: List<String>,
    minAgeMs: Long = ORPHAN_MIN_AGE_MS,
): Boolean =
    isEmpty && isUnderMountedRoot(path, downloadRoots) && isOldEnough(lastModified, now, minAgeMs)

private fun isOldEnough(lastModified: Long, now: Long, minAgeMs: Long): Boolean =
    lastModified in 1..now && now - lastModified >= minAgeMs

private fun sameSweepPath(a: String, b: String): Boolean =
    a.trimEnd('/').equals(b.trimEnd('/'), ignoreCase = true)

/**
 * Returns true if [path] starts with one of the [mountedRoots] paths, meaning it is on an
 * accessible volume. Paths on unmounted/ejected volumes cannot be judged for existence — we must
 * not make sweep decisions about them. ([File.separator] is `/` on Android; it lets the unit tests
 * run on Windows too.)
 */
internal fun isUnderMountedRoot(path: String, mountedRoots: List<String>): Boolean =
    mountedRoots.any {
        path.startsWith("$it/") || path.startsWith("$it${File.separator}")
    }

/**
 * CE (#849): how many trickplay sheets [info] has: [ItemTrickplayInfo.thumbnailCount] thumbnails,
 * [ItemTrickplayInfo.tileWidth] x [ItemTrickplayInfo.tileHeight] to a sheet, the last one partly
 * filled. The same count as the player's `TrickplayTiles.sheetCount`.
 */
internal fun trickplaySheetCount(info: ItemTrickplayInfo): Int {
    if (info.thumbnailCount <= 0) return 0
    val tilesPerSheet = (info.tileWidth * info.tileHeight).coerceAtLeast(1)
    return (info.thumbnailCount - 1) / tilesPerSheet + 1
}

class DownloaderImpl(
    private val context: Context,
    private val downloadList: DownloadList,
    private val itemRows: DownloadedItemRows,
    private val downloadSources: DownloadSources,
    private val downloadStreams: DownloadMediaStreams,
    private val segmentsAndTrickplay: DownloadSegmentsAndTrickplay,
    private val downloadUserData: DownloadUserData,
    private val queuedDownloads: QueuedDownloads,
    private val catalogProvider: Provider<ItemCatalog>,
    private val mediaProvider: Provider<PlaybackMedia>,
    private val accountProvider: Provider<CurrentAccount>,
    private val settings: DownloadSettings,
    private val currentServerId: CurrentServerId,
    // A Provider, so building this @Singleton (during Application.onCreate's field injection) does
    // not set up WorkManager; it is only needed once an image download is started (#250).
    private val workManagerProvider: Provider<WorkManager>,
    private val engine: MediaDownloadEngine,
    // CE (#901): the texts of core's resources.
    private val texts: DownloadTexts,
    private val dataChanges: DataChangeEvents = DataChangeEvents(),
    // CE (#573): the subtitles still downloading when their video finished. Tests shorten both.
    private val mediaStreamFinalizeTimeoutMs: Long = MEDIA_STREAM_FINALIZE_TIMEOUT_MS,
    private val mediaStreamFinalizePollMs: Long = MEDIA_STREAM_FINALIZE_POLL_MS,
) : Downloader {

    companion object {
        private const val PENDING_DOWNLOAD_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        private const val MEDIA_STREAM_FINALIZE_TIMEOUT_MS = 5L * 60 * 1000
        private const val MEDIA_STREAM_FINALIZE_POLL_MS = 1_000L
    }

    private val mediaStreamFinalizeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One job per source id: its subtitles that were not finished with the video (#573). */
    private val mediaStreamFinalizeJobs = ConcurrentHashMap<MediaSourceId, Job>()

    // Resolved per use, never captured: which repository is active (online vs offline) can
    // change at runtime without this @Singleton being recreated. Capturing a fixed instance
    // here goes stale after an offline -> online switch. #967: the roles of the repository it
    // uses.
    private val catalog: ItemCatalog
        get() = catalogProvider.get()

    private val media: PlaybackMedia
        get() = mediaProvider.get()

    private val account: CurrentAccount
        get() = accountProvider.get()

    /**
     * Monotonically-increasing download id counter. Seeded at System.currentTimeMillis() so that
     * fresh ids never collide with ids from a previous app session (time is monotone across
     * restarts). Each new download calls incrementAndGet() so concurrent starts in the same
     * millisecond each get a distinct id.
     */
    private val idCounter = AtomicLong(System.currentTimeMillis())

    // TODO: We should probably move most (if not all) code to a worker.
    //  At this moment it is possible that some things are not downloaded due to the user leaving
    //  the current screen
    override suspend fun downloadItem(
        item: Item,
        sourceId: MediaSourceId,
        storageIndex: Int,
        maxBitrate: Int?,
        smartQueued: Boolean,
    ): Pair<Long, UiText?> = coroutineScope {
        val transcodeDolbyVision = settings.downloadTranscodeDolbyVision
        val allowMetered = settings.downloadOverMobileData
        val allowRoaming = settings.downloadWhenRoaming
        // CE (#718): read once, so a server switch while this runs cannot store the download, or
        // decide which copy gives way, under another server than the one it started for.
        val serverId = currentServerId.get()

        // ---- RESUME BRANCH -------------------------------------------------------
        // Match the first in-progress .download source row for this item, regardless of
        // the requested sourceId. The queue dedups by item.id so there is at most one
        // active source per item; constraining on sourceId here would miss the resume for
        // multi-source items (the 2-arg overload derives sourceId from the server's first
        // source, which may differ from the one originally downloaded) and would start a
        // duplicate fresh download. CE (#718): only this server's partial file; another
        // server's copy of the item gives way in the fresh branch.
        val existingSource =
            try {
                partialSourceOfServer(item, serverId)
            } catch (e: Exception) {
                Timber.w(e, "Failed to query existing source for resume check")
                null
            }

        val existingDownloadId = existingSource?.downloadId
        if (existingSource != null && existingDownloadId != null) {
            return@coroutineScope try {
                // Re-resolve the URL from the server (not persisted). Prefer the existing
                // row's own source id so we resume the same source that was started; fall
                // back to the first available source if it is no longer offered.
                // The quality cap comes from the row, NOT the caller: the partial on
                // disk was written at the row's quality, and mixing URL kinds would
                // append mismatched bytes.
                val sources =
                    media.getMediaSources(
                        item.id,
                        true,
                        transcodeDolbyVision,
                        existingSource.downloadMaxBitrate,
                    )
                val source =
                    sources.firstOrNull { it.id == existingSource.id }
                        ?: sources.firstOrNull()
                        ?: throw IllegalStateException(
                            "No media sources for ${item.name} on resume"
                        )
                engine.start(
                    MediaDownloadEngine.Request(
                        id = existingDownloadId,
                        url = source.path,
                        destFile = File(existingSource.path),
                        allowMetered = allowMetered,
                        allowRoaming = allowRoaming,
                        resumable = !source.transcoded,
                    )
                )
                // Resume external streams: restart any still-in-progress subtitle transfers.
                val resolvedSource = downloadSources.itemSource(existingSource)
                resumeExternalMediaStreams(item, resolvedSource, source, allowMetered, allowRoaming)
                ensureTrickplayData(item.id, existingSource.id)
                Pair(existingDownloadId, null)
            } catch (e: Exception) {
                // On ANY exception in the RESUME branch: do NOT delete the item.
                // Keep the partial so the next attempt can resume from where it left off.
                Timber.e(e, "Failed to resume download for ${item.name}")
                Pair(-1L, mapDownloadError(e))
            }
        }

        // ---- FRESH BRANCH --------------------------------------------------------
        // Track the minted id so we can cancel the engine task if anything after start() throws
        // (e.g. DB insert fails). Without this the engine task would keep running with no
        // DB row, writing to disk and consuming bandwidth indefinitely.
        var mintedDownloadId: Long? = null
        try {
            val source =
                media.getMediaSources(item.id, true, transcodeDolbyVision, maxBitrate).first {
                    it.id == sourceId
                }
            val segments = media.getServerSegments(item.id)
            // CE (#718): what the rows below need from the server, asked before another server's
            // copy gives way, so a server that stops answering here leaves that copy alone.
            val showAndSeason =
                (item as? Episode)?.let {
                    catalog.getShow(it.seriesId) to catalog.getSeason(it.seasonId)
                }
            val dirs = context.getExternalFilesDirs(null)
            val storageLocation =
                run {
                    // Try requested index first; silently fall back to index 0 if unavailable.
                    val requested = dirs.getOrNull(storageIndex)
                    if (
                        requested != null &&
                            Environment.getExternalStorageState(requested) ==
                                Environment.MEDIA_MOUNTED
                    ) {
                        requested
                    } else {
                        dirs.getOrNull(0)?.takeIf {
                            Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED
                        }
                    }
                } ?: return@coroutineScope Pair(-1, texts.storageUnavailable())
            val extension = guessExtension(source.path)
            val relativePath = buildDownloadPath(item, source.id, extension)
            val destFile =
                File(storageLocation, DownloadFiles.partialPath("downloads/$relativePath"))
            // source.size is always the original file size, even when a quality cap
            // makes the server transcode to a smaller file — estimate the capped
            // download's size from the bitrate cap instead of flagging false positives.
            val expectedSize =
                if (source.transcoded && maxBitrate != null) {
                    estimateCappedDownloadSize(item.runtimeTicks, maxBitrate)
                } else {
                    source.size
                }
            val stats = StatFs(storageLocation.path)
            if (stats.availableBytes < expectedSize) {
                return@coroutineScope Pair(
                    -1,
                    texts.notEnoughStorage(
                        Formatter.formatFileSize(context, expectedSize),
                        Formatter.formatFileSize(context, stats.availableBytes),
                    ),
                )
            }

            // CE (#718): the download can no longer be refused, and nothing is written yet: another
            // server's copy of the item gives way now (it may have the same path). What can still
            // fail after this is local (the engine start, the Room inserts), and the catch below
            // removes this download then.
            releaseOtherServersCopy(item, serverId)

            destFile.parentFile?.mkdirs()
            // A leftover .download file from a previous failed/cancelled attempt with no DB
            // row is an orphan. Drop it before starting so the engine starts at byte 0.
            if (destFile.exists()) destFile.delete()

            // Mint a new id and start the engine transfer.
            val downloadId = idCounter.incrementAndGet().also { mintedDownloadId = it }
            engine.start(
                MediaDownloadEngine.Request(
                    id = downloadId,
                    url = source.path,
                    destFile = destFile,
                    allowMetered = allowMetered,
                    allowRoaming = allowRoaming,
                    resumable = !source.transcoded,
                )
            )

            when (item) {
                is Movie -> {
                    itemRows.insertMovie(item, serverId)
                }
                is Episode -> {
                    val (show, season) = showAndSeason!!
                    itemRows.insertEpisode(item, show, season, serverId)

                    startImagesDownloader(show)
                    startImagesDownloader(season)
                }
            }

            // Before the source row: once that exists, a restart resumes the download and never
            // gets here again (#693).
            segmentsAndTrickplay.replaceSegments(item.id, segments)

            downloadSources.insertSource(
                itemId = item.id,
                source = source,
                path = destFile.absolutePath,
                downloadId = downloadId,
                downloadMaxBitrate = maxBitrate,
                smartQueued = smartQueued,
            )
            // Progress from an earlier download of this item that has not reached the server yet
            // is newer than what the server sent with the item: keep it.
            val userId = account.getUserId()
            downloadUserData.storeUserDataOfDownload(userId, item)

            val resolvedStorageIndex = dirs.indexOf(storageLocation)
            downloadExternalMediaStreams(
                item,
                source,
                resolvedStorageIndex,
                allowMetered,
                allowRoaming,
            )

            ensureTrickplayData(item.id, sourceId, item)

            startImagesDownloader(item)
            return@coroutineScope Pair(downloadId, null)
        } catch (e: Exception) {
            // Cancel the engine task first — deleteItem() only removes rows/files via the
            // source DB row, which may not have been inserted yet.
            mintedDownloadId?.let {
                try {
                    engine.cancel(it)
                } catch (cancelError: Exception) {
                    Timber.e(cancelError, "Failed to cancel leaked engine task $it")
                }
            }
            try {
                // CE (#718): a download that failed before another server's copy gave way has
                // nothing of its own in Room; deleting by the item's id would take that copy.
                if (!isStoredForAnotherServer(item, serverId)) {
                    val source = media.getMediaSources(item.id).first { it.id == sourceId }
                    deleteItem(item, source)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to clean up failed download")
            }
            Timber.e(e)
            return@coroutineScope Pair(-1, mapDownloadError(e))
        }
    }

    override suspend fun downloadItem(
        item: Item,
        storageIndex: Int,
        maxBitrate: Int?,
        smartQueued: Boolean,
    ): Pair<Long, UiText?> {
        val sources =
            try {
                media.getMediaSources(
                    item.id,
                    includePath = true,
                    transcodeDolbyVision = settings.downloadTranscodeDolbyVision,
                    maxBitrate = maxBitrate,
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to resolve media sources for ${item.name}")
                return Pair(-1, mapDownloadError(e))
            }
        val sourceId =
            sources.firstOrNull()?.id
                ?: return Pair(
                    -1,
                    UiText.StringResource(DownloadsR.string.download_error_no_sources),
                )
        return downloadItem(
            item = item,
            sourceId = sourceId,
            storageIndex = storageIndex,
            maxBitrate = maxBitrate,
            smartQueued = smartQueued,
        )
    }

    override suspend fun cancelDownload(item: Item, downloadId: Long) {
        // First, and even when the row is already gone: a resume started while the queue entry
        // was being removed (#426) must not keep writing.
        engine.cancel(downloadId)
        val source =
            downloadSources.sourceByDownloadId(downloadId)?.let { downloadSources.itemSource(it) }
                ?: return
        deleteItem(item, source)
    }

    /**
     * The item's download in progress, when it is [serverId]'s (#718): another server's partial
     * file is not resumed or completed with this server's URLs.
     */
    private suspend fun partialSourceOfServer(item: Item, serverId: ServerId?) =
        serverId
            ?.let { downloadSources.sourcesOfServer(item.id, it) }
            .orEmpty()
            .firstOrNull { source ->
                DownloadFiles.isPartial(source.path) && source.downloadId != null
            }

    override suspend fun deleteItem(item: Item, source: ItemSource) {
        deleteItemRows(item, source)
        dataChanges.onChanged()
    }

    /**
     * CE (#718): Room keeps one copy per item id, and another server can have the same ids (a
     * server reinstalled over the same media paths and added again). When [item] is stored for
     * another server, that copy goes, files included, so this server's download replaces it and
     * nothing is left behind under the other server's name: the item belongs to the server that
     * downloaded it last, and the other server shows it as not downloaded. Its progress still to be
     * sent stays, and the sync sends it to that server.
     */
    internal suspend fun releaseOtherServersCopy(item: Item, serverId: ServerId?) {
        if (!isStoredForAnotherServer(item, serverId)) return
        Timber.i("Replacing the copy of ${item.id.asString()} stored for another server")
        val sources = downloadSources.sources(item.id)
        for (source in sources) {
            source.downloadId?.let { engine.cancel(it) }
            deleteItemRows(item, downloadSources.itemSource(source))
        }
        if (sources.isEmpty()) deleteItemRows(item, source = null)
        dataChanges.onChanged()
    }

    /** CE (#718): whether Room holds [item] for a server other than [serverId]. */
    private suspend fun isStoredForAnotherServer(item: Item, serverId: ServerId?): Boolean {
        if (serverId == null) return false
        // A row from before rows carried a server ("") belongs to no server: another one.
        val storedFor =
            when (item) {
                is Movie ->
                    downloadList.storedServerOfMovie(item.id)?.let {
                        it.serverId?.asString().orEmpty()
                    }
                is Episode ->
                    downloadList.storedServerOfEpisode(item.id)?.let {
                        it.serverId?.asString().orEmpty()
                    }
                else -> null
            }
        return storedFor != null && storedFor != serverId.asString()
    }

    private suspend fun deleteItemRows(item: Item, source: ItemSource?) {
        when (item) {
            is Movie -> {
                itemRows.deleteMovie(item.id)
            }
            is Episode -> {
                itemRows.deleteEpisode(item.id)
                val remainingEpisodes = itemRows.episodeIdsOfSeason(item.seasonId)
                if (remainingEpisodes.isEmpty()) {
                    itemRows.deleteSeasonAndUserData(item.seasonId)
                    File(context.filesDir, "trickplay/${item.seasonId.asString()}")
                        .deleteRecursively()
                    File(context.filesDir, "images/${item.seasonId.asString()}").deleteRecursively()
                    val remainingSeasons = itemRows.seasonIdsOfShow(item.seriesId)
                    if (remainingSeasons.isEmpty()) {
                        itemRows.deleteShowAndUserData(item.seriesId)
                        File(context.filesDir, "trickplay/${item.seriesId.asString()}")
                            .deleteRecursively()
                        File(context.filesDir, "images/${item.seriesId.asString()}")
                            .deleteRecursively()
                    }
                }
            }
        }

        if (source != null) {
            downloadSources.deleteSource(source.id)
            val sourceFile = File(source.path)
            sourceFile.delete()
            pruneEmptyParents(sourceFile)

            val mediaStreams = downloadStreams.mediaStreamsOfSource(source.id)
            for (mediaStream in mediaStreams) {
                // Cancel any still-running engine task for this external stream before
                // removing the DB row, otherwise we leak in-flight downloads.
                mediaStream.downloadId?.let { engine.cancel(it) }
                File(mediaStream.path).delete()
            }
            downloadStreams.deleteMediaStreamsOfSource(source.id)
        }

        // Progress watched offline that has not reached the server yet outlives the download:
        // the sync sends it and then removes the row (#281).
        downloadUserData.deleteSyncedUserData(item.id)
        segmentsAndTrickplay.deleteSegments(item.id)

        File(context.filesDir, "trickplay/${item.id.asString()}").deleteRecursively()
        File(context.filesDir, "images/${item.id.asString()}").deleteRecursively()
    }

    /**
     * Walks up the directory tree and removes empty directories until we hit the `downloads/` root
     * or a non-empty directory.
     */
    private fun pruneEmptyParents(file: File) {
        var dir = file.parentFile ?: return
        while (dir.name != "downloads" && dir.listFiles()?.isEmpty() == true) {
            dir.delete()
            dir = dir.parentFile ?: return
        }
    }

    override suspend fun getProgress(downloadId: Long?): Downloader.Progress {
        if (downloadId == null) {
            return Downloader.Progress(DownloadStatus.FAILED, 0, -1L, -1L)
        }
        val snapshot =
            engine.snapshot(downloadId)
                ?: return Downloader.Progress(DownloadStatus.FAILED, 0, -1L, -1L)
        val progress =
            when (snapshot.status) {
                DownloadStatus.SUCCESSFUL -> 100
                DownloadStatus.RUNNING ->
                    if (snapshot.totalBytes > 0) {
                        snapshot.bytesDownloaded.times(100).div(snapshot.totalBytes).toInt()
                    } else {
                        -1
                    }
                else -> -1
            }
        return Downloader.Progress(
            snapshot.status,
            progress,
            snapshot.bytesDownloaded,
            snapshot.totalBytes,
            snapshot.httpStatus,
        )
    }

    override suspend fun getProgress(downloadIds: List<Long>): Map<Long, Downloader.Progress> {
        if (downloadIds.isEmpty()) return emptyMap()
        val snapshots = engine.snapshots(downloadIds)
        val result = mutableMapOf<Long, Downloader.Progress>()
        for (id in downloadIds) {
            val snapshot = snapshots[id]
            if (snapshot == null) {
                result[id] = Downloader.Progress(DownloadStatus.FAILED, 0, -1L, -1L)
            } else {
                val progress =
                    when (snapshot.status) {
                        DownloadStatus.SUCCESSFUL -> 100
                        DownloadStatus.RUNNING ->
                            if (snapshot.totalBytes > 0) {
                                snapshot.bytesDownloaded.times(100).div(snapshot.totalBytes).toInt()
                            } else {
                                -1
                            }
                        else -> -1
                    }
                result[id] =
                    Downloader.Progress(
                        snapshot.status,
                        progress,
                        snapshot.bytesDownloaded,
                        snapshot.totalBytes,
                        snapshot.httpStatus,
                    )
            }
        }
        return result
    }

    private suspend fun downloadExternalMediaStreams(
        item: Item,
        source: ItemSource,
        storageIndex: Int = 0,
        allowMetered: Boolean = false,
        allowRoaming: Boolean = false,
    ) {
        val dirs = context.getExternalFilesDirs(null)
        val storageLocation =
            dirs.getOrNull(storageIndex)?.takeIf {
                Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED
            }
                ?: dirs.getOrNull(0)?.takeIf {
                    Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED
                }
                ?: return
        val downloadsDir = File(storageLocation, "downloads")
        downloadsDir.mkdirs()

        // Fresh pass only: this is reached when no in-progress source row exists for the
        // item, so no external-stream rows exist yet. Resume of in-progress streams is
        // handled separately by resumeExternalMediaStreams().
        //
        // isExternal alone misses the common case: an *embedded* subtitle track that the
        // server negotiated as an external delivery for THIS request (path already points
        // at a working deliveryUrl). That negotiation always happens when the video is
        // transcoded — the produced progressive stream carries no muxed subtitle tracks —
        // so embedded subtitles must be fetched as sidecars whenever source.transcoded.
        for (mediaStream in
            source.mediaStreams.filter {
                it.isExternal || (source.transcoded && it.type == MediaStreamType.SUBTITLE)
            }) {
            val id = MediaStreamId.random()
            try {
                val mediaStreamPath = mediaStream.path ?: continue
                val streamExt =
                    mediaStreamPath.substringAfterLast('.', "").takeIf { it.length in 1..5 }
                        ?: "sub"
                val streamPath =
                    File(
                            downloadsDir,
                            DownloadFiles.partialPath(
                                "${sanitize(item.name)}.${source.id.asString()}.${id.asString()}.$streamExt"
                            ),
                        )
                        .absolutePath
                downloadStreams.insertMediaStream(id, source.id, mediaStream, streamPath)
                val downloadId = idCounter.incrementAndGet()
                engine.start(
                    MediaDownloadEngine.Request(
                        id = downloadId,
                        url = mediaStreamPath,
                        destFile = File(streamPath),
                        allowMetered = allowMetered,
                        allowRoaming = allowRoaming,
                    )
                )
                downloadStreams.setMediaStreamDownloadId(id, downloadId)
            } catch (e: Exception) {
                // One bad external stream (malformed URL, engine rejects request)
                // shouldn't kill the whole download. Drop the orphan DB row and
                // keep going with the remaining streams.
                Timber.e(e, "Failed to start external stream ${mediaStream.title} for ${item.name}")
                try {
                    downloadStreams.deleteMediaStream(id)
                } catch (_: Exception) {
                    // swallow — nothing more we can do
                }
            }
        }
    }

    /**
     * Resumes external subtitle/media-stream downloads when an item is being resumed after process
     * death. The DB rows already exist (created on the original fresh pass), so this does NOT
     * create rows — it only (re)starts the engine for rows still in progress (`.download`),
     * recovering the remote URL by matching the row back to the server's external stream list.
     * Finalized rows (no `.download` suffix) are left untouched.
     *
     * No stable id links a stored row to a server stream, so we match on type/language/codec/title
     * — unique enough for real content. Subtitle files are tiny; a row whose server match cannot be
     * found is simply dropped (its partial discarded).
     */
    private suspend fun resumeExternalMediaStreams(
        item: Item,
        dbSource: ItemSource,
        serverSource: ItemSource,
        allowMetered: Boolean,
        allowRoaming: Boolean,
    ) {
        val rows =
            try {
                downloadStreams.mediaStreamsOfSource(dbSource.id)
            } catch (e: Exception) {
                Timber.w(e, "Failed to load media streams for resume of ${item.name}")
                return
            }
        restartExternalMediaStreamRows(
            // Process just died — every engine task is gone, so every still-incomplete
            // row needs restarting, regardless of what state it was last left in.
            rows = rows.filter { DownloadFiles.isPartial(it.path) },
            serverSource = serverSource,
            allowMetered = allowMetered,
            allowRoaming = allowRoaming,
        )
    }

    /**
     * Restarts any still-incomplete external subtitle/media-stream downloads for [item]'s
     * in-progress source whose engine task has failed or gone missing. Unlike
     * [resumeExternalMediaStreams] (unconditional — used right after process death when no engine
     * task exists at all), this runs while the app is alive and must not touch rows that are still
     * legitimately downloading/paused, or it would restart an in-flight transfer from zero for
     * nothing.
     *
     * Finds the source the same way the resume branch of [downloadItem] does: the queue dedups by
     * item id, so there is at most one in-progress (`.download`-suffixed) source per item.
     */
    override suspend fun retryFailedMediaStreams(item: Item) {
        val dbSourceDto =
            try {
                partialSourceOfServer(item, currentServerId.get())
            } catch (e: Exception) {
                Timber.w(e, "Failed to load source for media-stream retry check on ${item.name}")
                return
            } ?: return
        val rows =
            try {
                    downloadStreams.mediaStreamsOfSource(dbSourceDto.id)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to load media streams for retry check on ${item.name}")
                    return
                }
                .filter { DownloadFiles.isPartial(it.path) }
        if (rows.isEmpty()) return
        val failedRows =
            engine.snapshots(rows.mapNotNull { it.downloadId }).let { snapshots ->
                rows.filter { row ->
                    val downloadId = row.downloadId ?: return@filter false
                    // Missing from the map means the engine has no record of this id
                    // (task died some other way) — treat the same as an explicit FAILED.
                    snapshots[downloadId]?.status?.let { it == DownloadStatus.FAILED } ?: true
                }
            }
        if (failedRows.isEmpty()) return
        val serverSource =
            try {
                media
                    .getMediaSources(item.id, true, settings.downloadTranscodeDolbyVision)
                    .firstOrNull { it.id == dbSourceDto.id }
            } catch (e: Exception) {
                Timber.w(e, "Failed to re-resolve source for media-stream retry on ${item.name}")
                return
            } ?: return
        restartExternalMediaStreamRows(
            rows = failedRows,
            serverSource = serverSource,
            allowMetered = settings.downloadOverMobileData,
            allowRoaming = settings.downloadWhenRoaming,
        )
    }

    /**
     * Shared restart logic for both [resumeExternalMediaStreams] (unconditional, process death) and
     * [retryFailedMediaStreams] (only actually-failed rows, live retry). No stable id links a
     * stored row to a server stream, so we match on type/language/codec/title — unique enough for
     * real content. Subtitle files are tiny; a row whose server match cannot be found is simply
     * dropped (its partial discarded).
     */
    private suspend fun restartExternalMediaStreamRows(
        rows: List<StoredMediaStream>,
        serverSource: ItemSource,
        allowMetered: Boolean,
        allowRoaming: Boolean,
    ) {
        val serverExternal =
            serverSource.mediaStreams.filter {
                it.isExternal || (serverSource.transcoded && it.type == MediaStreamType.SUBTITLE)
            }
        for (row in rows) {
            val downloadId = row.downloadId ?: continue
            val url =
                serverExternal
                    .firstOrNull { s ->
                        s.type == row.type &&
                            s.language == row.language &&
                            s.codec == row.codec &&
                            s.title == row.title
                    }
                    ?.path
            if (url == null) {
                Timber.w(
                    "No server match for in-progress subtitle '${row.title}'; dropping stale row"
                )
                engine.cancel(downloadId)
                File(row.path).delete()
                try {
                    downloadStreams.deleteMediaStream(row.id)
                } catch (_: Exception) {}
                continue
            }
            engine.start(
                MediaDownloadEngine.Request(
                    id = downloadId,
                    url = url,
                    destFile = File(row.path),
                    allowMetered = allowMetered,
                    allowRoaming = allowRoaming,
                )
            )
        }
    }

    /**
     * CE (#1015): ensures [sourceId] has its trickplay layout and sheets stored. Called on fresh
     * starts, on resume after process death, and when finalizing, so a download interrupted before
     * trickplay finished (or started from an item without trickplay metadata) still gets its
     * scrubbing preview. Never fails the video transfer or finalization if the server cannot be
     * reached.
     */
    private suspend fun ensureTrickplayData(
        itemId: ItemId,
        sourceId: MediaSourceId,
        item: Item? = null,
    ) {
        try {
            if (segmentsAndTrickplay.trickplayInfo(sourceId) != null) return
            val trickplayInfo = resolveTrickplayInfo(itemId, sourceId, item) ?: return
            downloadTrickplayData(itemId, sourceId, trickplayInfo)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Failed to ensure trickplay data for ${itemId.asString()}")
        }
    }

    private suspend fun resolveTrickplayInfo(
        itemId: ItemId,
        sourceId: MediaSourceId,
        item: Item?,
    ): ItemTrickplayInfo? {
        val callerTrickplay = (item as? Playable)?.trickplayInfo
        if (callerTrickplay != null) {
            return callerTrickplay[sourceId]
        }
        val serverItem =
            when (item) {
                is Movie -> catalog.getMovie(itemId)
                is Episode -> catalog.getEpisode(itemId)
                else -> catalog.getItem(itemId)
            }
        return (serverItem as? Playable)?.trickplayInfo?.get(sourceId)
    }

    /**
     * Downloads the trickplay sheets of [sourceId]. CE (#849): each sheet is stored under its own
     * index, the name the player asks for. A sheet the server did not give is left out, so the
     * sheets after it keep their index; the player gets no preview for that one sheet offline.
     * Internal for its tests.
     *
     * CE (#860): the sheets are asked for [sourceId], the version downloaded, whose layout
     * [trickplayInfo] is. CE (#1015): each sheet is written to disk before the layout row is
     * inserted, so a restart mid-trickplay keeps finished sheets and resumes the rest.
     */
    internal suspend fun downloadTrickplayData(
        itemId: ItemId,
        sourceId: MediaSourceId,
        trickplayInfo: ItemTrickplayInfo,
    ) {
        val baseDir =
            File(context.filesDir, "trickplay/${itemId.asString()}/${sourceId.asString()}")
        baseDir.mkdirs()
        for (index in 0 until trickplaySheetCount(trickplayInfo)) {
            val sheetFile = File(baseDir, "$index")
            if (sheetFile.isFile && sheetFile.length() > 0) continue
            val bytes =
                media.getTrickplayData(itemId, trickplayInfo.width, index, sourceId) ?: continue
            writeTrickplaySheet(baseDir, sheetFile, bytes)
        }
        segmentsAndTrickplay.insertTrickplayInfo(sourceId, trickplayInfo)
    }

    private fun writeTrickplaySheet(baseDir: File, sheetFile: File, bytes: ByteArray) {
        val tempFile = File.createTempFile("sheet-${sheetFile.name}.", ".tmp", baseDir)
        try {
            tempFile.writeBytes(bytes)
            if (!tempFile.renameTo(sheetFile)) {
                sheetFile.writeBytes(bytes)
            }
        } finally {
            tempFile.delete()
        }
    }

    override suspend fun savePendingDownload(item: Item, maxBitrate: Int?, smartQueued: Boolean) =
        withContext(Dispatchers.IO) {
            val kind =
                when (item) {
                    is Movie -> "MOVIE"
                    is Episode -> "EPISODE"
                    else -> return@withContext
                }
            queuedDownloads.insertPendingDownload(
                StoredPendingDownload(
                    itemId = item.id,
                    itemKind = kind,
                    addedAt = System.currentTimeMillis(),
                    maxBitrate = maxBitrate,
                    smartQueued = smartQueued,
                )
            )
        }

    override suspend fun removePendingDownload(itemId: ItemId) =
        withContext(Dispatchers.IO) { queuedDownloads.deletePendingDownload(itemId) }

    override suspend fun getPendingDownloads(): List<Downloader.PendingDownload> =
        withContext(Dispatchers.IO) {
            val pending = queuedDownloads.pendingDownloads()
            val result = mutableListOf<Downloader.PendingDownload>()
            val cutoff = System.currentTimeMillis() - PENDING_DOWNLOAD_MAX_AGE_MS
            for (row in pending) {
                if (row.itemKind != "MOVIE" && row.itemKind != "EPISODE") {
                    queuedDownloads.deletePendingDownload(row.itemId)
                    continue
                }
                val resolved: Item? =
                    try {
                        when (row.itemKind) {
                            "MOVIE" -> catalog.getMovie(row.itemId)
                            else -> catalog.getEpisode(row.itemId)
                        }
                    } catch (e: Exception) {
                        // Transient (offline, server down) or permanent (item deleted) —
                        // we can't tell which. Keep the row and retry next launch, unless
                        // it has been failing for so long it's clearly dead.
                        Timber.w(
                            e,
                            "Failed to resolve pending download ${row.itemId.asString()}; keeping for retry",
                        )
                        null
                    }
                when {
                    resolved != null ->
                        result.add(
                            Downloader.PendingDownload(
                                resolved,
                                row.addedAt,
                                row.maxBitrate,
                                row.smartQueued,
                            )
                        )
                    row.addedAt < cutoff -> {
                        Timber.i(
                            "Dropping stale pending download ${row.itemId.asString()} (>30 days unresolvable)"
                        )
                        queuedDownloads.deletePendingDownload(row.itemId)
                    }
                // else: keep the row; it will be retried on the next restoreAll().
                }
            }
            result
        }

    override suspend fun getActiveDownloads(): List<Downloader.ActiveDownload> =
        withContext(Dispatchers.IO) {
            val userId = account.getUserId()
            val sources = downloadSources.activeDownloadSources()
            val result = mutableListOf<Downloader.ActiveDownload>()
            for (source in sources) {
                val downloadId = source.downloadId ?: continue
                if (renamedBeforeStop(source, downloadId)) {
                    // CE (#848): the app stopped between the rename of the finished file and the
                    // Room write. The file is complete: finish the finalize instead of resuming,
                    // which would download it all again. Should the finalize fail, the row is kept
                    // for the next start and never resumed: the `.download` file is gone.
                    try {
                        finalizeSource(source)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to finalize the download at ${source.path}")
                    }
                    continue
                }
                val item: Item? =
                    try {
                        downloadList.movie(source.itemId, userId)
                    } catch (_: Exception) {
                        try {
                            downloadList.episode(source.itemId, userId)
                        } catch (_: Exception) {
                            null
                        }
                    }
                if (item != null) {
                    result.add(
                        Downloader.ActiveDownload(
                            item,
                            downloadId,
                            source.downloadMaxBitrate,
                            source.smartQueued,
                        )
                    )
                }
            }
            result
        }

    /**
     * A `.download` row whose file has its final name already: only the rename of a finished
     * transfer makes that file. A task the engine knows is still this session's, and left alone.
     */
    private fun renamedBeforeStop(source: StoredSource, downloadId: Long): Boolean {
        if (engine.snapshot(downloadId) != null) return false
        val finalFile = File(DownloadFiles.finalPath(source.path))
        return !File(source.path).exists() && finalFile.isFile && finalFile.length() > 0
    }

    private fun mapDownloadError(e: Throwable): UiText = texts.startError(e)

    override suspend fun sweepOrphans() =
        withContext(Dispatchers.IO) {
            val userId = account.getUserId()

            // CE (#573): first, so the rows below have the subtitles' final names. Either name is
            // safe: the sweep keeps a file a row names with or without `.download`.
            try {
                finalizeFinishedMediaStreams()
            } catch (e: Exception) {
                Timber.w(e, "Failed to finalize the subtitles of finished downloads")
            }

            // Live engine tasks: ids (to decide whether an active source is dead) and
            // destination paths (so we never delete a file the engine is still writing).
            // At startup these are empty — but partial files are protected by knownPaths
            // from the DB rows, so that is fine.
            val liveTaskIds = engine.liveTaskIds()
            val liveTaskPaths = engine.liveTaskPaths()

            // Download roots that are actually reachable right now. A source whose path
            // is NOT under one of these is on an ejected/unmounted volume — we cannot
            // tell whether its file exists, so we must not judge it.
            val mountedRoots =
                context
                    .getExternalFilesDirs(null)
                    .filterNotNull()
                    .filter { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
                    .map { File(it, "downloads").absolutePath }

            val knownPaths = mutableSetOf<String>()
            suspend fun remember(source: StoredSource) {
                knownPaths.add(source.path)
                for (stream in downloadStreams.mediaStreamsOfSource(source.id)) {
                    knownPaths.add(stream.path)
                }
            }

            for (source in downloadSources.completedDownloadSources()) {
                remember(source)
                if (!isUnderMountedRoot(source.path, mountedRoots)) continue
                if (File(source.path).exists()) continue
                Timber.i("Sweeping completed source with missing file: ${source.path}")
                cleanupOrphanSource(source.itemId, userId)
            }

            for (source in downloadSources.activeDownloadSources()) {
                remember(source)
                if (!isUnderMountedRoot(source.path, mountedRoots)) continue
                val engineAlive = source.downloadId != null && source.downloadId in liveTaskIds
                // CE (#848): renamed when the app stopped, before its row was updated. The file is
                // complete, so the sweep finishes the finalize, as the start does, if it comes
                // first. Should that fail, the row stays for the next start.
                if (source.downloadId?.let { renamedBeforeStop(source, it) } == true) {
                    try {
                        finalizeSource(source)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to finalize the download at ${source.path}")
                    }
                    continue
                }
                if (!engineAlive && !File(source.path).exists()) {
                    Timber.i("Sweeping dead active source: ${source.path}")
                    cleanupOrphanSource(source.itemId, userId)
                }
            }

            // CE (#379): read before anything below is deleted, so a database that cannot be read
            // deletes nothing.
            val knownItemIds = downloadList.allItemIds().toSet()
            val knownFinalPaths = knownPaths.map { DownloadFiles.finalPath(it) }.toSet()
            val now = System.currentTimeMillis()
            var deletedFiles = 0
            var deletedBytes = 0L

            // Untracked files anywhere under a mounted downloads root: partials, and finished files
            // whose rows are gone (a database reset, #379).
            for (root in mountedRoots) {
                val downloadsDir = File(root)
                if (!downloadsDir.isDirectory) continue
                val orphans =
                    downloadsDir
                        .walkTopDown()
                        .filter { it.isFile }
                        .filter {
                            shouldDeleteOrphanFile(
                                path = it.absolutePath,
                                lastModified = it.lastModified(),
                                now = now,
                                downloadRoots = mountedRoots,
                                knownPaths = knownPaths,
                                knownFinalPaths = knownFinalPaths,
                                livePaths = liveTaskPaths,
                            )
                        }
                        .toList()
                for (file in orphans) {
                    try {
                        val bytes = file.length()
                        if (file.delete()) {
                            deletedFiles++
                            deletedBytes += bytes
                            Timber.i(
                                "Deleted orphan download file: ${file.relativeTo(downloadsDir)}"
                            )
                        }
                        pruneEmptyParents(file)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to delete orphan ${file.relativeTo(downloadsDir)}")
                    }
                }
                // Folders left empty earlier, by a deletion outside the sweep.
                downloadsDir
                    .walkBottomUp()
                    .filter {
                        it.isDirectory &&
                            shouldPruneEmptyDir(
                                path = it.absolutePath,
                                isEmpty = it.listFiles()?.isEmpty() == true,
                                lastModified = it.lastModified(),
                                now = now,
                                downloadRoots = mountedRoots,
                            )
                    }
                    .forEach { dir ->
                        if (dir.delete()) {
                            Timber.i(
                                "Deleted empty download folder: ${dir.relativeTo(downloadsDir)}"
                            )
                            pruneEmptyParents(dir)
                        }
                    }
            }

            // Artwork of downloads whose rows are gone.
            for (kind in listOf("images", "trickplay")) {
                val base = File(context.filesDir, kind)
                for (dir in base.listFiles().orEmpty()) {
                    if (!dir.isDirectory) continue
                    val newest = dir.walkTopDown().maxOfOrNull { it.lastModified() } ?: 0L
                    if (!shouldDeleteOrphanArtwork(dir.name, newest, now, knownItemIds)) continue
                    val files = dir.walkTopDown().filter { it.isFile }.toList()
                    val bytes = files.sumOf { it.length() }
                    if (dir.deleteRecursively()) {
                        deletedFiles += files.size
                        deletedBytes += bytes
                        Timber.i("Deleted orphan artwork folder: $kind/${dir.name}")
                    }
                }
            }

            Timber.i("Orphan sweep deleted $deletedFiles files, $deletedBytes bytes")
        }

    override suspend fun finalizeDownload(downloadId: Long): Boolean =
        withContext(Dispatchers.IO) {
            val source = downloadSources.sourceByDownloadId(downloadId)
            if (source != null) {
                return@withContext finalizeSource(source)
            }
            val mediaStream = downloadStreams.mediaStreamByDownloadId(downloadId)
            if (mediaStream != null) {
                return@withContext finalizeMediaStream(mediaStream)
            }
            // Neither source nor mediaStream found — the engine entry may have outlived the DB row
            // (deleted item, sweep). Nothing to do.
            false
        }

    private suspend fun finalizeSource(source: StoredSource): Boolean {
        if (!DownloadFiles.isPartial(source.path)) return true
        val finalPath = DownloadFiles.finalPath(source.path)
        val downloadFile = File(source.path)
        val finalFile = File(finalPath)

        // If the rename already happened on disk (earlier finalize, manual recovery),
        // just sync the DB and exit.
        if (finalFile.exists() && finalFile.length() > 0 && !downloadFile.exists()) {
            downloadSources.setSourceDownloaded(source.id, finalPath, System.currentTimeMillis())
            finalizeMediaStreamsOf(source)
            ensureTrickplayData(source.itemId, source.id)
            return true
        }

        if (!isDownloadSuccessful(source.downloadId)) return false

        // CE (#848): a failed finalize keeps the finished `.download` file and its row, as a failed
        // transfer keeps its partial file. The queue shows the download as failed; Retry resumes
        // it, the server answers that the file is complete, and it is finalized again. Removing
        // the download deletes both.
        if (!renameDownloadFile(source.path, finalPath)) {
            Timber.e("Failed to rename download. path=${source.path}")
            return false
        }
        try {
            downloadSources.setSourceDownloaded(source.id, finalPath, System.currentTimeMillis())
        } catch (e: Exception) {
            // The row still says `.download`: the file goes back under that name, so the row and
            // the disk agree for the retry, the removal and the storage sweep.
            Timber.e(e, "Failed to store the finished download. path=$finalPath")
            if (!renameDownloadFile(finalPath, source.path)) {
                Timber.e("Failed to rename the download back. path=$finalPath")
            }
            return false
        }
        // Drop the terminal task from the engine registry after successful finalize.
        source.downloadId?.let { engine.cancel(it) }
        Timber.d("Finalized download at: $finalPath")
        finalizeMediaStreamsOf(source)
        ensureTrickplayData(source.itemId, source.id)
        return true
    }

    /**
     * CE (#573): the queue finalizes a download by its video's id only, so its external subtitles,
     * each with a download id of its own, kept their `.download` name. Finalizes the ones that are
     * done, and hands the rest to one background job for [source].
     */
    private suspend fun finalizeMediaStreamsOf(source: StoredSource) {
        val rows =
            try {
                unfinishedMediaStreams(source.id)
            } catch (e: Exception) {
                Timber.w(e, "Failed to read the subtitles of ${source.path}")
                return
            }
        if (rows.isEmpty()) return
        val snapshots = engine.snapshots(rows.mapNotNull { it.downloadId })
        var left = false
        for (row in rows) {
            val status = row.downloadId?.let { snapshots[it]?.status }
            if (status == DownloadStatus.SUCCESSFUL) {
                // CE (#848): the video is final by now. A subtitle whose rename or row fails is
                // left to the job below and the start-up repair, and does not fail the download.
                val finalized =
                    try {
                        finalizeMediaStream(row)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to finalize the subtitle at ${row.path}")
                        false
                    }
                if (!finalized) left = true
            } else {
                left = true
            }
        }
        if (left) finalizeMediaStreamsLater(source)
    }

    private suspend fun unfinishedMediaStreams(sourceId: MediaSourceId): List<StoredMediaStream> =
        downloadStreams.mediaStreamsOfSource(sourceId).filter { DownloadFiles.isPartial(it.path) }

    /**
     * Waits, at most [mediaStreamFinalizeTimeoutMs], for the subtitles of [source] that were not
     * done with its video, and finalizes each one that finishes. A failed one is started once more,
     * the way [retryFailedMediaStreams] does while the video downloads. The job stops when the rows
     * are gone (the download was deleted). What it leaves behind keeps its `.download` name for
     * [finalizeFinishedMediaStreams] at the next start.
     */
    private fun finalizeMediaStreamsLater(source: StoredSource) {
        mediaStreamFinalizeJobs.compute(source.id) { _, running ->
            running?.takeIf { it.isActive }
                ?: mediaStreamFinalizeScope.launch {
                    try {
                        restartFailedMediaStreams(source)
                        val done =
                            withTimeoutOrNull(mediaStreamFinalizeTimeoutMs) {
                                while (!finalizeFinishedOrIdle(source.id)) {
                                    delay(mediaStreamFinalizePollMs)
                                }
                            }
                        if (done == null) {
                            Timber.w(
                                "Subtitles of ${source.path} not done; left for the next start"
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to finalize the subtitles of ${source.path}")
                    } finally {
                        mediaStreamFinalizeJobs.remove(source.id, coroutineContext[Job])
                    }
                }
        }
    }

    /**
     * Finalizes the subtitles of [sourceId] the engine reports done. True when none is left
     * running: all finalized, the rows gone, or only failed or unknown ones left.
     */
    private suspend fun finalizeFinishedOrIdle(sourceId: MediaSourceId): Boolean {
        val rows = unfinishedMediaStreams(sourceId)
        if (rows.isEmpty()) return true
        val snapshots = engine.snapshots(rows.mapNotNull { it.downloadId })
        var running = false
        for (row in rows) {
            when (row.downloadId?.let { snapshots[it]?.status }) {
                DownloadStatus.SUCCESSFUL -> finalizeMediaStream(row)
                DownloadStatus.PENDING,
                DownloadStatus.RUNNING,
                DownloadStatus.PAUSED -> running = true
                else -> Unit
            }
        }
        return !running
    }

    /** Starts the failed or lost subtitle transfers of a finished [source] once more. */
    private suspend fun restartFailedMediaStreams(source: StoredSource) {
        val rows = unfinishedMediaStreams(source.id)
        val snapshots = engine.snapshots(rows.mapNotNull { it.downloadId })
        val failed = rows.filter { row ->
            val status = row.downloadId?.let { snapshots[it]?.status }
            status == null || status == DownloadStatus.FAILED
        }
        if (failed.isEmpty()) return
        val serverSource =
            try {
                media
                    .getMediaSources(
                        source.itemId,
                        true,
                        settings.downloadTranscodeDolbyVision,
                        source.downloadMaxBitrate,
                    )
                    .firstOrNull { it.id == source.id }
            } catch (e: Exception) {
                Timber.w(e, "Failed to re-resolve ${source.path} to restart its subtitles")
                null
            } ?: return
        restartExternalMediaStreamRows(
            rows = failed,
            serverSource = serverSource,
            allowMetered = settings.downloadOverMobileData,
            allowRoaming = settings.downloadWhenRoaming,
        )
    }

    override suspend fun finalizeFinishedMediaStreams() =
        withContext(Dispatchers.IO) {
            for (source in downloadSources.completedDownloadSources()) {
                if (mediaStreamFinalizeJobs[source.id]?.isActive == true) continue
                for (row in unfinishedMediaStreams(source.id)) {
                    val status = row.downloadId?.let { engine.snapshot(it)?.status }
                    // Running, or failed: the file may be incomplete, so it keeps its name. A task
                    // the engine does not know is from an earlier start: an older build that
                    // never finalized subtitles, or a job that gave up.
                    if (status != null && status != DownloadStatus.SUCCESSFUL) continue
                    if (finalizeMediaStream(row, taskGone = status == null)) {
                        Timber.i("Finalized the subtitle at ${DownloadFiles.finalPath(row.path)}")
                    }
                }
            }
        }

    /**
     * Renames a finished subtitle from `<path>.download` to `<path>` and updates its row. With
     * [taskGone], the engine no longer knows the transfer (#573): the file is taken as it is, and a
     * failed rename keeps the row, which still points at a readable file.
     */
    private suspend fun finalizeMediaStream(
        mediaStream: StoredMediaStream,
        taskGone: Boolean = false,
    ): Boolean {
        if (!DownloadFiles.isPartial(mediaStream.path)) return true
        val finalPath = DownloadFiles.finalPath(mediaStream.path)
        val downloadFile = File(mediaStream.path)
        val finalFile = File(finalPath)

        if (finalFile.exists() && finalFile.length() > 0 && !downloadFile.exists()) {
            downloadStreams.setMediaStreamPath(mediaStream.id, finalPath)
            return true
        }

        if (taskGone) {
            if (!downloadFile.exists() || downloadFile.length() == 0L) return false
        } else if (!isDownloadSuccessful(mediaStream.downloadId)) {
            return false
        }

        if (!renameDownloadFile(mediaStream.path, finalPath)) {
            Timber.e("Failed to rename media stream download. path=${mediaStream.path}")
            if (!taskGone) downloadStreams.deleteMediaStream(mediaStream.id)
            return false
        }
        downloadStreams.setMediaStreamPath(mediaStream.id, finalPath)
        // Drop the terminal task from the engine registry after successful finalize.
        mediaStream.downloadId?.let { engine.cancel(it) }
        return true
    }

    private fun isDownloadSuccessful(downloadId: Long?): Boolean {
        if (downloadId == null) return false
        return engine.snapshot(downloadId)?.status == DownloadStatus.SUCCESSFUL
    }

    /**
     * Renames `<fromPath>` to `<toPath>`. Falls back to copy+delete because renameTo can fail
     * across some external storage filesystems (FAT32/exFAT).
     */
    private fun renameDownloadFile(fromPath: String, toPath: String): Boolean {
        val src = File(fromPath)
        val dst = File(toPath)
        if (src.renameTo(dst)) return true
        return try {
            src.copyTo(dst, overwrite = true)
            src.delete()
            true
        } catch (e: Exception) {
            Timber.e(e, "copyTo fallback also failed: $fromPath -> $toPath")
            // CE (#848): a copy that ran out of space leaves part of the file: only the source
            // is complete.
            if (src.exists() && dst.isFile) dst.delete()
            false
        }
    }

    private suspend fun cleanupOrphanSource(itemId: ItemId, userId: UserId) {
        val item: Item? =
            try {
                downloadList.movieOrNull(itemId, userId)
            } catch (_: Exception) {
                null
            }
                ?: try {
                    downloadList.episodeOrNull(itemId, userId)
                } catch (_: Exception) {
                    null
                }
        if (item == null) {
            // Item record is already gone — drop lingering source rows directly.
            for (source in downloadSources.sources(itemId)) {
                downloadStreams.deleteMediaStreamsOfSource(source.id)
                downloadSources.deleteSource(source.id)
            }
            return
        }
        for (source in downloadSources.sources(itemId).map { downloadSources.itemSource(it) }) {
            try {
                deleteItem(item, source)
            } catch (e: Exception) {
                Timber.w(e, "Failed to delete orphan item ${item.name}")
            }
        }
    }

    /**
     * Builds a Plex-style relative path for a download.
     *
     * Movies: `Movie Name (2024)/Movie Name (2024).mkv` Episodes: `Series Name/S01/S01E05 - Episode
     * Name.mkv`
     */
    private fun buildDownloadPath(item: Item, sourceId: MediaSourceId, extension: String): String {
        return when (item) {
            is Episode -> {
                val series = sanitize(item.seriesName.ifBlank { "Unknown Series" })
                val sNum = "S%02d".format(item.parentIndexNumber)
                val eNum = "E%02d".format(item.indexNumber)
                val epName = sanitize(item.name)
                "$series/$sNum/$sNum$eNum - $epName.$extension"
            }
            is Movie -> {
                val year = item.productionYear
                val title = sanitize(item.name)
                val folder = if (year != null) "$title ($year)" else title
                "$folder/$folder.$extension"
            }
            else -> "${item.id.asString()}.${sourceId.asString()}.$extension"
        }
    }

    /** Strips characters that are illegal in FAT32/exFAT/NTFS filenames. */
    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "").trim().ifEmpty { "Unknown" }

    /** Extracts the file extension from a stream URL, falling back to "mkv". */
    private fun guessExtension(url: String): String {
        // Jellyfin URLs look like .../stream.mkv?static=true&...
        val path = url.substringBefore('?')
        val ext = path.substringAfterLast('.', "").lowercase()
        return if (ext.length in 1..5 && ext.all { it.isLetterOrDigit() }) ext else "mkv"
    }

    private fun startImagesDownloader(item: Item) {
        val downloadImagesRequest =
            OneTimeWorkRequestBuilder<ImagesDownloaderWorker>()
                .setInputData(workDataOf(ImagesDownloaderWorker.KEY_ITEM_ID to item.id.asString()))
                .build()

        // Episode downloads trigger image fetches for episode + season + show,
        // and a whole season download would otherwise enqueue the same show worker
        // N times. Dedupe on itemId so we only hit the image endpoint once per item.
        workManagerProvider
            .get()
            .enqueueUniqueWork(
                "image-download-${item.id.asString()}",
                ExistingWorkPolicy.KEEP,
                downloadImagesRequest,
            )
    }
}
