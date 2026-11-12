package dev.jdtech.jellyfin.core.presentation.downloader

import android.app.Application
import android.content.Context
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidImages
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.Downloader
import dev.jdtech.jellyfin.utils.NetworkConnectivity
import dev.jdtech.jellyfin.utils.download.DownloadStatus
import java.lang.reflect.Proxy
import java.util.UUID
import javax.inject.Provider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#530): a download queued or started without a network waits for it ("Waiting for network")
 * and starts by itself when it returns, instead of failing with "Unknown error".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadQueueNetworkTest {
    private val item = episode()
    private val connectivity = FakeConnectivity()
    private val downloader = FakeDownloader()

    @Test
    fun `a download queued offline waits for the network and starts when it returns`() {
        connectivity.online = false
        val queue = queue()
        runBlocking { queue.enqueue(item) }

        awaitEntry(queue) { it.waitingForNetwork }
        Thread.sleep(500)
        val waiting = entry(queue)
        assertTrue(waiting.state is DownloadQueue.EntryState.Pending)
        assertEquals(0, downloader.starts)

        downloader.startResult = DOWNLOAD_ID to null
        connectivity.online = true

        awaitEntry(queue) { it.state is DownloadQueue.EntryState.Downloading }
        assertFalse(entry(queue).waitingForNetwork)
        assertEquals(1, downloader.starts)
    }

    @Test
    fun `a start that fails because the network went away waits instead of failing`() {
        val unreachable = UiText.StringResource(CoreR.string.download_error_server_unreachable)
        downloader.onStart = { connectivity.online = false }
        downloader.startResult = -1L to unreachable
        val queue = queue()
        runBlocking { queue.enqueue(item) }

        awaitEntry(queue) { it.waitingForNetwork }
        val waiting = entry(queue)
        assertTrue(waiting.state is DownloadQueue.EntryState.Pending)
        assertEquals(0, waiting.retryCount)

        downloader.onStart = {}
        downloader.startResult = DOWNLOAD_ID to null
        connectivity.online = true

        awaitEntry(queue) { it.state is DownloadQueue.EntryState.Downloading }
        assertEquals(2, downloader.starts)
    }

    @Test
    fun `a start that fails while online still fails with its reason`() {
        val unreachable = UiText.StringResource(CoreR.string.download_error_server_unreachable)
        downloader.startResult = -1L to unreachable
        val queue = queue()
        runBlocking { queue.enqueue(item) }

        awaitEntry(queue) { it.state is DownloadQueue.EntryState.Failed }
        assertEquals(unreachable, (entry(queue).state as DownloadQueue.EntryState.Failed).error)
        assertFalse(entry(queue).waitingForNetwork)
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

    private fun queue(): DownloadQueue {
        val context = RuntimeEnvironment.getApplication()
        val preferences =
            context.getSharedPreferences("download-queue-network-test", Context.MODE_PRIVATE).apply {
                edit().clear().putInt("pref_downloads_max_concurrent", 1).commit()
            }
        return DownloadQueue(
            downloader = downloader,
            appPreferences = AppPreferences(preferences),
            repositoryProvider = Provider { error("No repository in this test") },
            database = database(),
            context = context,
            connectivity = connectivity,
        )
    }

    private fun database() =
        Proxy.newProxyInstance(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, _ ->
            throw NotImplementedError("ServerDatabaseDao.${method.name}")
        } as ServerDatabaseDao

    private class FakeConnectivity : NetworkConnectivity {
        @Volatile var online = true

        override fun isOnline() = online

        override fun isMetered() = false

        override fun isRoaming() = false
    }

    private class FakeDownloader : Downloader {
        @Volatile var startResult: Pair<Long, UiText?> = -1L to null
        @Volatile var onStart: () -> Unit = {}
        @Volatile var starts = 0

        private fun start(): Pair<Long, UiText?> {
            starts++
            onStart()
            return startResult
        }

        override suspend fun downloadItem(
            item: FindroidItem,
            sourceId: String,
            storageIndex: Int,
            maxBitrate: Int?,
            smartQueued: Boolean,
        ) = start()

        override suspend fun downloadItem(
            item: FindroidItem,
            storageIndex: Int,
            maxBitrate: Int?,
            smartQueued: Boolean,
        ) = start()

        override suspend fun cancelDownload(item: FindroidItem, downloadId: Long) = Unit

        override suspend fun retryFailedMediaStreams(item: FindroidItem) = Unit

        override suspend fun deleteItem(item: FindroidItem, source: FindroidSource) = Unit

        override suspend fun getProgress(downloadId: Long?) = running

        override suspend fun getProgress(downloadIds: List<Long>) =
            downloadIds.associateWith { running }

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

        override suspend fun finalizeDownload(downloadId: Long) = false

        private val running = Downloader.Progress(DownloadStatus.RUNNING, 0, 0L, -1L)
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
        const val DOWNLOAD_ID = 7L
        const val TIMEOUT_MS = 8_000L
    }
}
