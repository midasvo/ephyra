package dev.jdtech.jellyfin.core.presentation.downloader

import android.app.Application
import android.content.Context
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidImages
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.Downloader
import dev.jdtech.jellyfin.utils.download.DownloadStatus
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#560): a download that fails and will be retried goes straight to waiting for its retry. The
 * queue used to publish it as Failed first; with nothing else in flight the download service saw an
 * empty queue in between and stopped, while the retry still had to run. The service now follows the
 * pump, which is active for as long as the queue has work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadQueueRetryTransitionTest {
    private val first = episode(1)
    private val second = episode(2)
    private val downloader = FakeDownloader()
    private val observer = CoroutineScope(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        observer.cancel()
    }

    @Test
    fun `downloads that fail into a retry are never published as failed`() {
        val queue = queue(maxConcurrent = 2)
        val published = CopyOnWriteArrayList<List<DownloadQueue.Entry>>()
        queue.entries.onEach { published.add(it) }.launchIn(observer)

        runBlocking { queue.enqueueAll(listOf(first, second)) }
        await(queue) { entries ->
            entries.size == 2 && entries.all { it.state is DownloadQueue.EntryState.Downloading }
        }
        // The connection drops under both transfers: the engine fails them without a status.
        downloader.progress = Downloader.Progress(DownloadStatus.FAILED, -1, 0L, -1L)
        await(queue) { entries ->
            entries.all { it.state is DownloadQueue.EntryState.Pending && it.retryCount == 1 }
        }

        val failed = published.filter { entries ->
            entries.any { it.state is DownloadQueue.EntryState.Failed }
        }
        assertEquals("Published as failed before the retry: $failed", 0, failed.size)
        assertTrue(queue.entries.value.all { it.retryAt != null })
    }

    @Test
    fun `the pump stays active while downloads wait for their retry`() {
        val queue = queue(maxConcurrent = 2)
        val idle = CopyOnWriteArrayList<List<DownloadQueue.Entry>>()
        runBlocking { queue.enqueueAll(listOf(first, second)) }
        assertTrue(queue.pumpActive.value)
        // From here on the pump is active: record every moment it is not.
        queue.pumpActive.onEach { if (!it) idle.add(queue.entries.value) }.launchIn(observer)
        await(queue) { entries ->
            entries.size == 2 && entries.all { it.state is DownloadQueue.EntryState.Downloading }
        }

        downloader.progress = Downloader.Progress(DownloadStatus.FAILED, -1, 0L, -1L)
        await(queue) { entries ->
            entries.all { it.state is DownloadQueue.EntryState.Pending && it.retryCount == 1 }
        }
        Thread.sleep(300)

        assertTrue(hasDownloadWork(queue.entries.value))
        assertTrue(queue.pumpActive.value)
        assertEquals("The pump went idle with retries waiting: $idle", 0, idle.size)
    }

    @Test
    fun `the pump goes idle once every download has failed for good or finished`() {
        val queue = queue(maxConcurrent = 2)
        runBlocking { queue.enqueueAll(listOf(first, second)) }
        await(queue) { entries ->
            entries.size == 2 && entries.all { it.state is DownloadQueue.EntryState.Downloading }
        }

        // The server refuses one for good (#383) and the other finishes.
        downloader.progressOf[downloader.idOf.getValue(first.id)] =
            Downloader.Progress(DownloadStatus.FAILED, -1, 0L, -1L, 404)
        downloader.progressOf[downloader.idOf.getValue(second.id)] =
            Downloader.Progress(DownloadStatus.SUCCESSFUL, 100, 10L, 10L)

        awaitIdle(queue)
        val states = queue.entries.value.associate { it.id to it.state }
        assertTrue(states.getValue(first.id) is DownloadQueue.EntryState.Failed)
        assertEquals(DownloadQueue.EntryState.Completed, states.getValue(second.id))
        assertFalse(hasDownloadWork(queue.entries.value))
    }

    @Test
    fun `a download queued as the last one finishes finds the pump active`() {
        // The pump decides to stop and a new download arrives at the same moment: whichever comes
        // first, the pump is active once enqueue returns and stays so until that download is done.
        repeat(6) { round ->
            val queue = queue(maxConcurrent = 1)
            val earlier = episode(100 + round)
            val later = episode(200 + round)
            val idle = CopyOnWriteArrayList<List<DownloadQueue.Entry>>()
            runBlocking { queue.enqueue(earlier) }
            await(queue) { it.single().state is DownloadQueue.EntryState.Downloading }

            downloader.progressOf[downloader.idOf.getValue(earlier.id)] =
                Downloader.Progress(DownloadStatus.SUCCESSFUL, 100, 10L, 10L)
            // Spread the enqueue around the moment the pump sees the completion and exits.
            await(queue) { it.single().state is DownloadQueue.EntryState.Completed }
            Thread.sleep(round % 3L)
            runBlocking { queue.enqueue(later) }
            assertTrue("Round $round: no pump for the new download", queue.pumpActive.value)
            val watch =
                queue.pumpActive
                    .onEach { if (!it) idle.add(queue.entries.value) }
                    .launchIn(observer)
            await(queue) { entries ->
                entries.any {
                    it.id == later.id && it.state is DownloadQueue.EntryState.Downloading
                }
            }
            assertEquals("Round $round: the pump went idle with work: $idle", 0, idle.size)
            watch.cancel()

            downloader.progressOf[downloader.idOf.getValue(later.id)] =
                Downloader.Progress(DownloadStatus.SUCCESSFUL, 100, 10L, 10L)
            awaitIdle(queue)
        }
    }

    @Test
    fun `work is anything running, paused or waiting`() {
        fun entry(
            state: DownloadQueue.EntryState,
            retryAt: Long? = null,
            offline: Boolean = false,
        ) =
            DownloadQueue.Entry(
                id = first.id,
                item = first,
                addedAt = 0L,
                state = state,
                retryAt = retryAt,
                waitingForNetwork = offline,
            )

        assertFalse(hasDownloadWork(emptyList()))
        assertFalse(hasDownloadWork(listOf(entry(DownloadQueue.EntryState.Failed(null)))))
        assertFalse(hasDownloadWork(listOf(entry(DownloadQueue.EntryState.Completed))))
        assertTrue(hasDownloadWork(listOf(entry(DownloadQueue.EntryState.Downloading))))
        assertTrue(hasDownloadWork(listOf(entry(DownloadQueue.EntryState.Paused))))
        assertTrue(hasDownloadWork(listOf(entry(DownloadQueue.EntryState.Pending))))
        // Waiting for a retry and waiting for the network are work too: this is #560.
        assertTrue(
            hasDownloadWork(listOf(entry(DownloadQueue.EntryState.Pending, retryAt = 30_000L)))
        )
        assertTrue(hasDownloadWork(listOf(entry(DownloadQueue.EntryState.Pending, offline = true))))
        assertTrue(
            hasDownloadWork(
                listOf(
                    entry(DownloadQueue.EntryState.Failed(null)),
                    entry(DownloadQueue.EntryState.Completed),
                    entry(DownloadQueue.EntryState.Pending, retryAt = 30_000L),
                )
            )
        )
    }

    @Test
    fun `a failed download waits for a retry until its retries are used up`() {
        val failed =
            DownloadQueue.Entry(
                id = first.id,
                item = first,
                addedAt = 0L,
                state = DownloadQueue.EntryState.Failed(null),
                downloadId = 7L,
                startedAt = 5L,
                progress = 40,
            )

        val firstRetry = DownloadQueue.afterFailure(failed, permanent = false, now = 1_000L)
        assertEquals(DownloadQueue.EntryState.Pending, firstRetry.state)
        assertEquals(1, firstRetry.retryCount)
        assertEquals(31_000L, firstRetry.retryAt)
        assertNull(firstRetry.downloadId)

        val thirdRetry =
            DownloadQueue.afterFailure(failed.copy(retryCount = 2), permanent = false, now = 1_000L)
        assertEquals(DownloadQueue.EntryState.Pending, thirdRetry.state)
        assertEquals(3, thirdRetry.retryCount)
        assertEquals(601_000L, thirdRetry.retryAt)

        // No retries left: it ends as failed, as it was.
        val usedUp = failed.copy(retryCount = 3)
        assertEquals(usedUp, DownloadQueue.afterFailure(usedUp, permanent = false, now = 1_000L))
        // Refused by the server for good (#383): failed at once, whatever retries are left.
        assertEquals(failed, DownloadQueue.afterFailure(failed, permanent = true, now = 1_000L))
    }

    @Test
    fun `the notification says what the queue is doing`() {
        fun entry(
            state: DownloadQueue.EntryState,
            retryAt: Long? = null,
            offline: Boolean = false,
        ) =
            DownloadQueue.Entry(
                id = UUID.randomUUID(),
                item = first,
                addedAt = 0L,
                state = state,
                retryAt = retryAt,
                waitingForNetwork = offline,
            )
        val downloading = entry(DownloadQueue.EntryState.Downloading)
        val queued = entry(DownloadQueue.EntryState.Pending)
        val retrying = entry(DownloadQueue.EntryState.Pending, retryAt = 30_000L)
        val offline = entry(DownloadQueue.EntryState.Pending, offline = true)
        val paused = entry(DownloadQueue.EntryState.Paused)
        val failed = entry(DownloadQueue.EntryState.Failed(null))
        val done = entry(DownloadQueue.EntryState.Completed)

        // Nothing to do: the notification keeps what it says until the service stops.
        assertNull(downloadPumpNotice(emptyList()))
        assertNull(downloadPumpNotice(listOf(failed, done)))
        assertEquals(
            DownloadPumpNotice.Downloading(2),
            downloadPumpNotice(listOf(downloading, queued, failed, done)),
        )
        // A download about to start counts as downloading, so a new one does not say "Waiting".
        assertEquals(DownloadPumpNotice.Downloading(1), downloadPumpNotice(listOf(queued)))
        assertEquals(
            DownloadPumpNotice.Downloading(3),
            downloadPumpNotice(listOf(downloading, retrying, offline)),
        )
        assertEquals(DownloadPumpNotice.Waiting, downloadPumpNotice(listOf(retrying, retrying)))
        assertEquals(DownloadPumpNotice.WaitingForNetwork, downloadPumpNotice(listOf(offline)))
        assertEquals(DownloadPumpNotice.WaitingForNetwork, downloadPumpNotice(listOf(paused)))
        assertEquals(
            DownloadPumpNotice.WaitingForNetwork,
            downloadPumpNotice(listOf(retrying, offline)),
        )
        // A retry that has started again is a download.
        assertEquals(
            DownloadPumpNotice.Downloading(1),
            downloadPumpNotice(listOf(retrying.copy(state = DownloadQueue.EntryState.Downloading))),
        )
    }

    private fun awaitIdle(queue: DownloadQueue) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!queue.pumpActive.value) return
            Thread.sleep(10)
        }
        fail("The pump never went idle: ${queue.entries.value}")
    }

    private fun await(queue: DownloadQueue, matches: (List<DownloadQueue.Entry>) -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (matches(queue.entries.value)) return
            Thread.sleep(10)
        }
        fail("The queue never reached the state: ${queue.entries.value}")
    }

    private fun queue(maxConcurrent: Int): DownloadQueue {
        val context = RuntimeEnvironment.getApplication()
        val preferences =
            context.getSharedPreferences("download-queue-retry-test", Context.MODE_PRIVATE).apply {
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
        ) { _, method, _ ->
            throw NotImplementedError("ServerDatabaseDao.${method.name}")
        } as ServerDatabaseDao

    /** Every item gets its own download id; [progress] is what the engine reports for all. */
    private class FakeDownloader : Downloader {
        @Volatile var progress = Downloader.Progress(DownloadStatus.RUNNING, 0, 0L, -1L)
        val progressOf = ConcurrentHashMap<Long, Downloader.Progress>()
        val idOf = ConcurrentHashMap<UUID, Long>()

        private fun start(item: FindroidItem): Pair<Long, UiText?> =
            idOf.getOrPut(item.id) { idOf.size + 1L } to null

        override suspend fun downloadItem(
            item: FindroidItem,
            sourceId: String,
            storageIndex: Int,
            maxBitrate: Int?,
            smartQueued: Boolean,
        ) = start(item)

        override suspend fun downloadItem(
            item: FindroidItem,
            storageIndex: Int,
            maxBitrate: Int?,
            smartQueued: Boolean,
        ) = start(item)

        override suspend fun cancelDownload(item: FindroidItem, downloadId: Long) = Unit

        override suspend fun retryFailedMediaStreams(item: FindroidItem) = Unit

        override suspend fun deleteItem(item: FindroidItem, source: FindroidSource) = Unit

        override suspend fun getProgress(downloadId: Long?) =
            downloadId?.let { progressOf[it] } ?: progress

        override suspend fun getProgress(downloadIds: List<Long>) = downloadIds.associateWith {
            progressOf[it] ?: progress
        }

        override suspend fun getActiveDownloads() = emptyList<Downloader.ActiveDownload>()

        override suspend fun savePendingDownload(
            item: FindroidItem,
            maxBitrate: Int?,
            smartQueued: Boolean,
        ) = Unit

        override suspend fun removePendingDownload(itemId: UUID) = Unit

        override suspend fun getPendingDownloads() = emptyList<Downloader.PendingDownload>()

        override suspend fun sweepOrphans() = Unit

        override suspend fun finalizeFinishedMediaStreams() = Unit

        override suspend fun finalizeDownload(downloadId: Long) = true
    }

    private fun episode(number: Int) =
        FindroidEpisode(
            id = UUID.randomUUID(),
            name = "Episode $number",
            originalTitle = null,
            overview = "",
            indexNumber = number,
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
        const val TIMEOUT_MS = 8_000L
    }
}
