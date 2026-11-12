package dev.jdtech.jellyfin.core.presentation.downloader

import android.app.Application
import android.content.Context
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidImages
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSourceDto
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.Downloader
import dev.jdtech.jellyfin.utils.download.DownloadStatus
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Provider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#426): cancelling a download that is not running removes it for good. Its source row (with
 * the partial file) is what brought it back at the next start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadQueueCancelTest {
    private val item = episode()

    /** The row an earlier attempt left: a partial file and the id of that attempt. */
    private val partialRow =
        FindroidSourceDto(
            id = "source",
            itemId = item.id,
            name = "Original",
            type = FindroidSourceType.LOCAL,
            path = "/downloads/Show/S01/S01E01 - Episode.ts.download",
            downloadId = ROW_DOWNLOAD_ID,
        )

    private val downloader = FakeDownloader()

    @Test
    fun `cancelling a download restored at start removes its partial download`() {
        downloader.active = listOf(Downloader.ActiveDownload(item, ROW_DOWNLOAD_ID, null, false))
        // No free slot, so the restored entry stays Pending as it does behind another download.
        val queue = queue(maxConcurrent = 0)
        runBlocking { queue.restoreAll() }
        awaitState(queue) { it is DownloadQueue.EntryState.Pending }

        queue.remove(item.id)

        awaitCancelled(ROW_DOWNLOAD_ID)
    }

    @Test
    fun `cancelling a download that waits for its retry removes its partial download`() {
        downloader.startResult = ROW_DOWNLOAD_ID to null
        downloader.progressStatus = DownloadStatus.FAILED
        val queue = queue(maxConcurrent = 1)
        runBlocking { queue.enqueue(item) }
        awaitState(queue) { it is DownloadQueue.EntryState.Pending && retried(queue) }

        queue.remove(item.id)

        awaitCancelled(ROW_DOWNLOAD_ID)
    }

    @Test
    fun `cancelling a failed download removes its partial download`() {
        downloader.startResult = -1L to null
        val queue = queue(maxConcurrent = 1)
        runBlocking { queue.enqueue(item) }
        awaitState(queue) { it is DownloadQueue.EntryState.Failed }

        queue.remove(item.id)

        awaitCancelled(ROW_DOWNLOAD_ID)
    }

    private fun retried(queue: DownloadQueue) =
        queue.entries.value.firstOrNull { it.id == item.id }?.retryCount == 1

    private fun awaitState(queue: DownloadQueue, matches: (DownloadQueue.EntryState) -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val state = queue.entries.value.firstOrNull { it.id == item.id }?.state
            if (state != null && matches(state)) return
            Thread.sleep(20)
        }
        fail("Entry never reached the state: ${queue.entries.value}")
    }

    private fun awaitCancelled(downloadId: Long) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (downloader.cancelled.isNotEmpty()) break
            Thread.sleep(20)
        }
        assertEquals(listOf(item.id to downloadId), downloader.cancelled.toList())
    }

    private fun queue(maxConcurrent: Int): DownloadQueue {
        val context = RuntimeEnvironment.getApplication()
        val preferences =
            context.getSharedPreferences("download-queue-test", Context.MODE_PRIVATE).apply {
                edit().clear().putInt("pref_downloads_max_concurrent", maxConcurrent).commit()
            }
        return DownloadQueue(
            downloader = downloader,
            appPreferences = AppPreferences(preferences),
            repositoryProvider = Provider { error("No repository in this test") },
            database = database(),
            context = context,
        )
    }

    private fun database() =
        Proxy.newProxyInstance(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getSources" -> listOf(partialRow).filter { it.itemId == args?.firstOrNull() }
                else -> throw NotImplementedError("ServerDatabaseDao.${method.name}")
            }
        } as ServerDatabaseDao

    private class FakeDownloader : Downloader {
        var active = emptyList<Downloader.ActiveDownload>()
        var startResult: Pair<Long, UiText?> = -1L to null
        var progressStatus = DownloadStatus.RUNNING
        val cancelled = CopyOnWriteArrayList<Pair<UUID, Long>>()

        override suspend fun downloadItem(
            item: FindroidItem,
            sourceId: String,
            storageIndex: Int,
            maxBitrate: Int?,
            smartQueued: Boolean,
        ) = startResult

        override suspend fun downloadItem(
            item: FindroidItem,
            storageIndex: Int,
            maxBitrate: Int?,
            smartQueued: Boolean,
        ) = startResult

        override suspend fun cancelDownload(item: FindroidItem, downloadId: Long) {
            cancelled.add(item.id to downloadId)
        }

        override suspend fun retryFailedMediaStreams(item: FindroidItem) = Unit

        override suspend fun deleteItem(item: FindroidItem, source: FindroidSource) = Unit

        override suspend fun getProgress(downloadId: Long?) =
            Downloader.Progress(progressStatus, 0, 0L, -1L)

        override suspend fun getProgress(downloadIds: List<Long>) =
            downloadIds.associateWith { Downloader.Progress(progressStatus, 0, 0L, -1L) }

        override suspend fun getActiveDownloads() = active

        override suspend fun savePendingDownload(
            item: FindroidItem,
            maxBitrate: Int?,
            smartQueued: Boolean,
        ) = Unit

        override suspend fun removePendingDownload(itemId: UUID) = Unit

        override suspend fun getPendingDownloads() = emptyList<Downloader.PendingDownload>()

        override suspend fun sweepOrphans() = Unit

        override suspend fun finalizeFinishedMediaStreams() = Unit

        override suspend fun finalizeDownload(downloadId: Long) = false
    }

    private fun episode() =
        FindroidEpisode(
            id = UUID.randomUUID(),
            name = "Episode 1",
            originalTitle = null,
            overview = "",
            indexNumber = 1,
            indexNumberEnd = null,
            parentIndexNumber = 1,
            sources = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = true,
            runtimeTicks = 0L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            seriesId = UUID.randomUUID(),
            seriesName = "Show",
            seasonId = UUID.randomUUID(),
            seasonName = "Season 1",
            communityRating = null,
            people = emptyList(),
            missing = false,
            images = FindroidImages(),
            chapters = emptyList(),
            trickplayInfo = null,
        )

    private companion object {
        const val ROW_DOWNLOAD_ID = 7L
        const val TIMEOUT_MS = 5_000L
    }
}
