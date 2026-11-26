package dev.jdtech.jellyfin.core.presentation.downloader

import android.app.Application
import dev.jdtech.jellyfin.database.RoomDownloadStore
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemImages
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.MediaSourceId
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import dev.jdtech.jellyfin.utils.Downloader
import dev.jdtech.jellyfin.utils.download.DownloadStatus
import dev.jdtech.jellyfin.utils.download.TestDownloadSettings
import dev.jdtech.jellyfin.utils.download.TestDownloadTexts
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
 * CE (#1002): a queued download whose start fails while online (e.g. server unreachable) has no
 * source row yet, so it must keep its pending row until the downloader writes a source row. That
 * way process death does not lose the queued download, and restoreAll() brings it back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadQueuePendingPersistenceTest {
    private val item = episode()
    private val downloader = FakeDownloader()

    @Test
    fun `start failing online keeps pending row so restoreAll recovers it after process death`() {
        val unreachable = UiText.StringResource(TestDownloadTexts.START_ERROR)
        downloader.startResult = -1L to unreachable

        val firstQueue = queue()
        runBlocking { firstQueue.enqueue(item, maxBitrate = 2_000_000, smartQueued = true) }

        awaitEntry(firstQueue) { it.state is DownloadQueue.EntryState.Failed }
        awaitPumpIdle(firstQueue)
        val failed = entry(firstQueue)
        assertTrue(failed.retryWhenServerReturns)
        assertTrue(
            downloader.persistedPending.containsKey(item.id),
            "Pending row should stay persisted when start fails before writing a source row",
        )

        // Simulate process death and restart once the server is reachable again.
        downloader.startResult = DOWNLOAD_ID to null
        val restoredQueue = queue()
        runBlocking { restoredQueue.restoreAll() }

        awaitEntry(restoredQueue) { it.state is DownloadQueue.EntryState.Downloading }
        val active = entry(restoredQueue)
        assertEquals(DOWNLOAD_ID, active.downloadId)
        assertEquals(2_000_000, active.maxBitrate)
        assertTrue(active.smartQueued)
        awaitPendingRemoved()
    }

    @Test
    fun `start throwing online keeps pending row so restoreAll recovers it after process death`() {
        downloader.onDownloadItem = { throw IllegalStateException("server unreachable") }

        val firstQueue = queue()
        runBlocking { firstQueue.enqueue(item) }

        awaitEntry(firstQueue) { it.state is DownloadQueue.EntryState.Failed }
        awaitPumpIdle(firstQueue)
        assertTrue(downloader.persistedPending.containsKey(item.id))

        downloader.onDownloadItem = null
        downloader.startResult = DOWNLOAD_ID to null
        val restoredQueue = queue()
        runBlocking { restoredQueue.restoreAll() }

        awaitEntry(restoredQueue) { it.state is DownloadQueue.EntryState.Downloading }
        awaitPendingRemoved()
    }

    @Test
    fun `removing a download whose start failed online drops its pending row`() {
        downloader.startResult = -1L to UiText.StringResource(TestDownloadTexts.START_ERROR)
        val queue = queue()
        runBlocking { queue.enqueue(item) }
        awaitEntry(queue) { it.state is DownloadQueue.EntryState.Failed }
        assertTrue(downloader.persistedPending.containsKey(item.id))

        queue.remove(item.id)
        awaitAbsent(queue)
        awaitPendingRemoved()

        val restoredQueue = queue()
        runBlocking { restoredQueue.restoreAll() }
        assertEquals(emptyList(), restoredQueue.entries.value)
    }

    @Test
    fun `restoreAll skips leftover pending row when active source exists and drops it on resume`() {
        val now = System.currentTimeMillis()
        downloader.persistedPending[item.id] =
            Downloader.PendingDownload(
                item = item,
                addedAt = now - 10_000L,
                maxBitrate = 3_000_000,
                smartQueued = true,
            )
        downloader.active =
            listOf(
                Downloader.ActiveDownload(
                    item = item,
                    downloadId = OLD_DOWNLOAD_ID,
                    maxBitrate = 3_000_000,
                    smartQueued = true,
                )
            )
        downloader.startResult = DOWNLOAD_ID to null

        val queue = queue()
        runBlocking { queue.restoreAll() }

        awaitEntry(queue) { it.state is DownloadQueue.EntryState.Downloading }
        assertEquals(1, queue.entries.value.size)
        assertEquals(DOWNLOAD_ID, queue.entries.value.single().downloadId)
        awaitPendingRemoved()
    }

    @Test
    fun `cancelling and re-enqueueing during a succeeding start preserves the new pending row`() {
        val firstStartEntered = CompletableDeferred<Unit>()
        val releaseFirstStart = CompletableDeferred<Unit>()
        val secondStartEntered = CompletableDeferred<Unit>()
        val releaseSecondStart = CompletableDeferred<Unit>()
        val calls = AtomicInteger(0)

        downloader.onDownloadItem = {
            when (calls.incrementAndGet()) {
                1 -> {
                    firstStartEntered.complete(Unit)
                    releaseFirstStart.await()
                    OLD_DOWNLOAD_ID to null
                }
                else -> {
                    secondStartEntered.complete(Unit)
                    releaseSecondStart.await()
                    DOWNLOAD_ID to null
                }
            }
        }

        val queue = queue()
        runBlocking {
            queue.enqueue(item, maxBitrate = 1_000_000)
            withTimeout(TIMEOUT_MS) { firstStartEntered.await() }

            queue.remove(item.id)
            awaitAbsent(queue)

            queue.enqueue(item, maxBitrate = 2_000_000)
            releaseFirstStart.complete(Unit)

            withTimeout(TIMEOUT_MS) { secondStartEntered.await() }
            awaitCancelled(OLD_DOWNLOAD_ID)
            assertEquals(2_000_000, downloader.persistedPending[item.id]?.maxBitrate)

            releaseSecondStart.complete(Unit)
            awaitEntry(queue) { it.state is DownloadQueue.EntryState.Downloading }
            awaitPendingRemoved()
        }
    }

    private fun entry(queue: DownloadQueue) = queue.entries.value.first { it.id == item.id }

    private fun awaitEntry(queue: DownloadQueue, matches: (DownloadQueue.Entry) -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val entry = queue.entries.value.firstOrNull { it.id == item.id }
            if (entry != null && matches(entry)) return
            Thread.sleep(20)
        }
        fail("Entry never reached the state: ${queue.entries.value}")
    }

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

    private fun awaitPendingRemoved() {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!downloader.persistedPending.containsKey(item.id)) return
            Thread.sleep(20)
        }
        assertFalse(downloader.persistedPending.containsKey(item.id))
    }

    private fun awaitCancelled(downloadId: Long) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (downloader.cancelled.isNotEmpty()) break
            Thread.sleep(20)
        }
        assertEquals(listOf(item.id to downloadId), downloader.cancelled.toList())
    }

    private fun queue(): DownloadQueue {
        val context = RuntimeEnvironment.getApplication()
        return DownloadQueue(
            downloader = downloader,
            settings = TestDownloadSettings(maxConcurrentDownloads = 1),
            downloadSources = RoomDownloadStore(database()),
            context = context,
            texts = TestDownloadTexts,
        )
    }

    private fun database() =
        fakeProxy(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, _ ->
            when (method.kotlinName) {
                "getSources" -> emptyList<Any>()
                else -> throw NotImplementedError("ServerDatabaseDao.${method.kotlinName}")
            }
        }
            as ServerDatabaseDao

    private class FakeDownloader : Downloader {
        @Volatile var active = emptyList<Downloader.ActiveDownload>()
        @Volatile var startResult: Pair<Long, UiText?> = -1L to null
        @Volatile var onDownloadItem: (suspend () -> Pair<Long, UiText?>)? = null
        val persistedPending = ConcurrentHashMap<ItemId, Downloader.PendingDownload>()
        val cancelled = CopyOnWriteArrayList<Pair<ItemId, Long>>()

        private suspend fun start(): Pair<Long, UiText?> = onDownloadItem?.invoke() ?: startResult

        override suspend fun downloadItem(
            item: Item,
            sourceId: MediaSourceId,
            storageIndex: Int,
            maxBitrate: Int?,
            smartQueued: Boolean,
        ) = start()

        override suspend fun downloadItem(
            item: Item,
            storageIndex: Int,
            maxBitrate: Int?,
            smartQueued: Boolean,
        ) = start()

        override suspend fun cancelDownload(item: Item, downloadId: Long) {
            cancelled.add(item.id to downloadId)
        }

        override suspend fun retryFailedMediaStreams(item: Item) = Unit

        override suspend fun deleteItem(item: Item, source: ItemSource) = Unit

        override suspend fun getProgress(downloadId: Long?) =
            Downloader.Progress(DownloadStatus.RUNNING, 0, 0L, -1L)

        override suspend fun getProgress(downloadIds: List<Long>) = downloadIds.associateWith {
            Downloader.Progress(DownloadStatus.RUNNING, 0, 0L, -1L)
        }

        override suspend fun getActiveDownloads() = active

        override suspend fun savePendingDownload(
            item: Item,
            maxBitrate: Int?,
            smartQueued: Boolean,
        ) {
            persistedPending[item.id] =
                Downloader.PendingDownload(
                    item = item,
                    addedAt = System.currentTimeMillis(),
                    maxBitrate = maxBitrate,
                    smartQueued = smartQueued,
                )
        }

        override suspend fun removePendingDownload(itemId: ItemId) {
            persistedPending.remove(itemId)
        }

        override suspend fun getPendingDownloads() = persistedPending.values.toList()

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
        const val OLD_DOWNLOAD_ID = 7L
        const val DOWNLOAD_ID = 11L
        const val TIMEOUT_MS = 5_000L
    }
}
