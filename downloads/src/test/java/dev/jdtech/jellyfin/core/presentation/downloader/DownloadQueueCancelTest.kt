package dev.jdtech.jellyfin.core.presentation.downloader

import android.app.Application
import dev.jdtech.jellyfin.database.RoomDownloadStore
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemImages
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.ItemSourceEntity
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.MediaSourceId
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toMediaSourceId
import dev.jdtech.jellyfin.testing.asItemIdOrNull
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import dev.jdtech.jellyfin.utils.Downloader
import dev.jdtech.jellyfin.utils.NetworkConnectivity
import dev.jdtech.jellyfin.utils.download.DownloadStatus
import dev.jdtech.jellyfin.utils.download.TestDownloadSettings
import dev.jdtech.jellyfin.utils.download.TestDownloadTexts
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.fail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        ItemSourceEntity(
            id = "source".toMediaSourceId(),
            itemId = item.id,
            name = "Original",
            type = ItemSourceType.LOCAL,
            path = "/downloads/Show/S01/S01E01 - Episode.ts.download",
            downloadId = ROW_DOWNLOAD_ID,
        )

    private var storedSources = listOf(partialRow)
    private var online = true
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

    @Test
    fun `cancelling and re-enqueueing during a start cancels old task instead of attaching to new entry`() {
        storedSources = emptyList()
        val firstStartEntered = CompletableDeferred<Unit>()
        val releaseFirstStart = CompletableDeferred<Unit>()
        val secondStartEntered = CompletableDeferred<Unit>()
        val releaseSecondStart = CompletableDeferred<Unit>()
        val calls = AtomicInteger(0)

        downloader.onDownloadItem = { _ ->
            when (calls.incrementAndGet()) {
                1 -> {
                    firstStartEntered.complete(Unit)
                    releaseFirstStart.await()
                    OLD_START_DOWNLOAD_ID to null
                }
                else -> {
                    secondStartEntered.complete(Unit)
                    releaseSecondStart.await()
                    NEW_START_DOWNLOAD_ID to null
                }
            }
        }

        val queue = queue(maxConcurrent = 1)
        runBlocking {
            queue.enqueue(item, maxBitrate = 1_000_000)
            withTimeout(TIMEOUT_MS) { firstStartEntered.await() }

            queue.remove(item.id)
            awaitAbsent(queue)

            queue.enqueue(item, maxBitrate = 2_000_000)
            releaseFirstStart.complete(Unit)

            withTimeout(TIMEOUT_MS) { secondStartEntered.await() }
            awaitCancelled(OLD_START_DOWNLOAD_ID)

            val pendingNewEntry = queue.entries.value.single { it.id == item.id }
            assertEquals(DownloadQueue.EntryState.Pending, pendingNewEntry.state)
            assertNull(pendingNewEntry.downloadId)
            assertEquals(2_000_000, pendingNewEntry.maxBitrate)

            releaseSecondStart.complete(Unit)
            awaitState(queue) { it is DownloadQueue.EntryState.Downloading }

            val finalEntry = queue.entries.value.single { it.id == item.id }
            assertEquals(NEW_START_DOWNLOAD_ID, finalEntry.downloadId)
            assertEquals(2_000_000, finalEntry.maxBitrate)
            assertEquals(listOf(item.id to OLD_START_DOWNLOAD_ID), downloader.cancelled.toList())
        }
    }

    @Test
    fun `cancelling and re-enqueueing during a failing start does not fail the new entry`() {
        storedSources = emptyList()
        val firstStartEntered = CompletableDeferred<Unit>()
        val releaseFirstStart = CompletableDeferred<Unit>()
        val secondStartEntered = CompletableDeferred<Unit>()
        val releaseSecondStart = CompletableDeferred<Unit>()
        val calls = AtomicInteger(0)

        downloader.onDownloadItem = { _ ->
            when (calls.incrementAndGet()) {
                1 -> {
                    firstStartEntered.complete(Unit)
                    releaseFirstStart.await()
                    -1L to UiText.DynamicString("stale start error")
                }
                else -> {
                    secondStartEntered.complete(Unit)
                    releaseSecondStart.await()
                    NEW_START_DOWNLOAD_ID to null
                }
            }
        }

        val queue = queue(maxConcurrent = 1)
        runBlocking {
            queue.enqueue(item, maxBitrate = 1_000_000)
            withTimeout(TIMEOUT_MS) { firstStartEntered.await() }

            queue.remove(item.id)
            awaitAbsent(queue)

            queue.enqueue(item, maxBitrate = 2_000_000)
            releaseFirstStart.complete(Unit)

            withTimeout(TIMEOUT_MS) { secondStartEntered.await() }
            val pendingNewEntry = queue.entries.value.single { it.id == item.id }
            assertEquals(DownloadQueue.EntryState.Pending, pendingNewEntry.state)
            assertEquals(2_000_000, pendingNewEntry.maxBitrate)

            releaseSecondStart.complete(Unit)
            awaitState(queue) { it is DownloadQueue.EntryState.Downloading }

            val finalEntry = queue.entries.value.single { it.id == item.id }
            assertEquals(NEW_START_DOWNLOAD_ID, finalEntry.downloadId)
            assertEquals(emptyList(), downloader.cancelled.toList())
        }
    }

    @Test
    fun `cancelling during a start that fails offline does not re-persist the pending download`() {
        storedSources = emptyList()
        val startEntered = CompletableDeferred<Unit>()
        val releaseStart = CompletableDeferred<Unit>()

        downloader.onDownloadItem = { _ ->
            startEntered.complete(Unit)
            releaseStart.await()
            -1L to UiText.DynamicString("network lost")
        }

        val queue = queue(maxConcurrent = 1)
        runBlocking {
            queue.enqueue(item, maxBitrate = 1_000_000)
            withTimeout(TIMEOUT_MS) { startEntered.await() }

            queue.remove(item.id)
            awaitAbsent(queue)
            val savedBeforeFinish = downloader.savedPending.size

            online = false
            releaseStart.complete(Unit)

            awaitPumpIdle(queue)
            assertEquals(emptyList(), queue.entries.value)
            assertEquals(savedBeforeFinish, downloader.savedPending.size)
        }
    }

    @Test
    fun `cancelling while persisting a wait-for-network retry drops the recreated pending row`() {
        storedSources = emptyList()
        val startEntered = CompletableDeferred<Unit>()
        val releaseStart = CompletableDeferred<Unit>()
        val saveEntered = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        val saveCalls = AtomicInteger(0)

        downloader.onDownloadItem = { _ ->
            startEntered.complete(Unit)
            releaseStart.await()
            -1L to UiText.DynamicString("network lost")
        }
        downloader.onSavePendingDownload = {
            if (saveCalls.incrementAndGet() == 2) {
                saveEntered.complete(Unit)
                releaseSave.await()
            }
        }

        val queue = queue(maxConcurrent = 1)
        runBlocking {
            queue.enqueue(item, maxBitrate = 1_000_000)
            withTimeout(TIMEOUT_MS) { startEntered.await() }

            online = false
            releaseStart.complete(Unit)
            withTimeout(TIMEOUT_MS) { saveEntered.await() }

            queue.remove(item.id)
            awaitAbsent(queue)
            releaseSave.complete(Unit)

            awaitPumpIdle(queue)
            assertEquals(emptyList(), queue.entries.value)
            assertFalse(downloader.persistedPending.containsKey(item.id))
        }
    }

    private fun retried(queue: DownloadQueue) =
        queue.entries.value.firstOrNull { it.id == item.id }?.retryCount == 1

    private fun awaitAbsent(queue: DownloadQueue) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (queue.entries.value.none { it.id == item.id }) return
            Thread.sleep(20)
        }
        fail("Entry was never removed: ${queue.entries.value}")
    }

    private fun awaitPumpIdle(queue: DownloadQueue) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!queue.pumpActive.value) return
            Thread.sleep(20)
        }
        fail("Pump never went idle: ${queue.entries.value}")
    }

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
        return DownloadQueue(
            downloader = downloader,
            settings = TestDownloadSettings(maxConcurrentDownloads = maxConcurrent),
            downloadSources = RoomDownloadStore(database()),
            context = context,
            texts = TestDownloadTexts,
            connectivity =
                object : NetworkConnectivity {
                    override fun isOnline() = online

                    override fun isMetered() = false

                    override fun isRoaming() = false
                },
        )
    }

    private fun database() =
        fakeProxy(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, args ->
            when (method.kotlinName) {
                "getSources" ->
                    storedSources.filter { it.itemId == args?.firstOrNull().asItemIdOrNull() }
                else -> throw NotImplementedError("ServerDatabaseDao.${method.kotlinName}")
            }
        }
            as ServerDatabaseDao

    private class FakeDownloader : Downloader {
        var active = emptyList<Downloader.ActiveDownload>()
        var startResult: Pair<Long, UiText?> = -1L to null
        var onDownloadItem: (suspend (maxBitrate: Int?) -> Pair<Long, UiText?>)? = null
        var onSavePendingDownload: (suspend () -> Unit)? = null
        var progressStatus = DownloadStatus.RUNNING
        val cancelled = CopyOnWriteArrayList<Pair<ItemId, Long>>()
        val savedPending = CopyOnWriteArrayList<Pair<ItemId, Int?>>()
        val persistedPending: MutableMap<ItemId, Int?> = Collections.synchronizedMap(mutableMapOf())

        override suspend fun downloadItem(
            item: Item,
            sourceId: MediaSourceId,
            storageIndex: Int,
            maxBitrate: Int?,
            smartQueued: Boolean,
        ) = onDownloadItem?.invoke(maxBitrate) ?: startResult

        override suspend fun downloadItem(
            item: Item,
            storageIndex: Int,
            maxBitrate: Int?,
            smartQueued: Boolean,
        ) = onDownloadItem?.invoke(maxBitrate) ?: startResult

        override suspend fun cancelDownload(item: Item, downloadId: Long) {
            cancelled.add(item.id to downloadId)
        }

        override suspend fun retryFailedMediaStreams(item: Item) = Unit

        override suspend fun deleteItem(item: Item, source: ItemSource) = Unit

        override suspend fun getProgress(downloadId: Long?) =
            Downloader.Progress(progressStatus, 0, 0L, -1L)

        override suspend fun getProgress(downloadIds: List<Long>) = downloadIds.associateWith {
            Downloader.Progress(progressStatus, 0, 0L, -1L)
        }

        override suspend fun getActiveDownloads() = active

        override suspend fun savePendingDownload(
            item: Item,
            maxBitrate: Int?,
            smartQueued: Boolean,
        ) {
            onSavePendingDownload?.invoke()
            savedPending.add(item.id to maxBitrate)
            persistedPending[item.id] = maxBitrate
        }

        override suspend fun removePendingDownload(itemId: ItemId) {
            persistedPending.remove(itemId)
        }

        override suspend fun getPendingDownloads() = emptyList<Downloader.PendingDownload>()

        override suspend fun sweepOrphans() = Unit

        override suspend fun finalizeFinishedMediaStreams() = Unit

        override suspend fun finalizeDownload(downloadId: Long) = false
    }

    private fun episode() =
        Episode(
            id = UUID.randomUUID().toString().toItemId(),
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
            seriesId = UUID.randomUUID().toString().toItemId(),
            seriesName = "Show",
            seasonId = UUID.randomUUID().toString().toItemId(),
            seasonName = "Season 1",
            communityRating = null,
            people = emptyList(),
            missing = false,
            images = ItemImages(),
            chapters = emptyList(),
            trickplayInfo = null,
        )

    private companion object {
        const val ROW_DOWNLOAD_ID = 7L
        const val OLD_START_DOWNLOAD_ID = 11L
        const val NEW_START_DOWNLOAD_ID = 22L
        const val TIMEOUT_MS = 5_000L
    }
}
