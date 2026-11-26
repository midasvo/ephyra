package dev.jdtech.jellyfin.utils

import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadQueue
import dev.jdtech.jellyfin.data.files.DownloadedFilesOnDisk
import dev.jdtech.jellyfin.database.RoomDownloadStore
import dev.jdtech.jellyfin.database.ServerDatabase
import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.EpisodeEntity
import dev.jdtech.jellyfin.models.ItemImages
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.ItemSourceEntity
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.ItemTrickplayInfo
import dev.jdtech.jellyfin.models.Season
import dev.jdtech.jellyfin.models.SeasonEntity
import dev.jdtech.jellyfin.models.Show
import dev.jdtech.jellyfin.models.ShowEntity
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toItemTrickplayInfo
import dev.jdtech.jellyfin.models.toMediaSourceId
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.repository.CurrentAccount
import dev.jdtech.jellyfin.repository.ItemCatalog
import dev.jdtech.jellyfin.repository.PlaybackMedia
import dev.jdtech.jellyfin.testing.FakeRepositoryStores
import dev.jdtech.jellyfin.testing.FakeRoles
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import dev.jdtech.jellyfin.utils.download.MediaDownloadEngine
import dev.jdtech.jellyfin.utils.download.TestCurrentServerId
import dev.jdtech.jellyfin.utils.download.TestDownloadSettings
import dev.jdtech.jellyfin.utils.download.TestDownloadTexts
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Provider
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.shadows.ShadowStatFs

/**
 * CE (#1015): a download still running across an app update or process death used to finish without
 * its `trickplayInfos` row and sheets when resumed or finalized by the new process, or when started
 * from a list item whose `trickplayInfo` had not been requested.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadTrickplayRestartTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val room =
        Room.inMemoryDatabaseBuilder(context, ServerDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val dao = room.getServerDatabaseDao()
    private val store = RoomDownloadStore(dao)
    private lateinit var root: File

    private val showId = UUID.randomUUID().toString().toItemId()
    private val seasonId = UUID.randomUUID().toString().toItemId()
    private val episodeId = UUID.randomUUID().toString().toItemId()
    private val sourceId = "source-1".toMediaSourceId()
    private val serverId = "server-1".toServerId()
    private val userId = UUID.randomUUID().toString().toUserId()

    private val info =
        ItemTrickplayInfo(
            width = 320,
            height = 180,
            tileWidth = 10,
            tileHeight = 10,
            thumbnailCount = 250,
            interval = 10_000,
            bandwidth = 0,
        )

    private val requestedSheets = CopyOnWriteArrayList<Int>()
    @Volatile private var serverAvailable = true

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        val external = context.getExternalFilesDirs(null).first()
        ShadowEnvironment.setExternalStorageState(external, Environment.MEDIA_MOUNTED)
        ShadowStatFs.registerStats(external.path, 1024, 1024, 1024)
        root =
            File(external, "downloads").apply {
                deleteRecursively()
                mkdirs()
            }
        File(context.filesDir, "trickplay").deleteRecursively()
    }

    @After
    fun tearDown() {
        room.close()
        root.deleteRecursively()
        File(context.filesDir, "trickplay").deleteRecursively()
    }

    @Test
    fun `resuming and finalizing an active download after process death stores its trickplay layout and sheets`() {
        val partialFile =
            File(root, "Show/S01/S01E01 - Ep.mkv.download").apply {
                parentFile!!.mkdirs()
                writeBytes(VIDEO.copyOf(16))
            }
        seedActiveDownloadInRoom(partialFile)

        // New process: fresh engine, downloader, and queue over the persisted Room DB.
        val downloader = newProcessDownloader()
        val queue =
            DownloadQueue(
                downloader = downloader,
                settings = TestDownloadSettings(maxConcurrentDownloads = 1),
                downloadSources = store,
                context = context,
                texts = TestDownloadTexts,
            )
        runBlocking { queue.restoreAll() }

        val deadline = System.currentTimeMillis() + 8_000L
        while (
            queue.entries.value.firstOrNull()?.state != DownloadQueue.EntryState.Completed &&
                System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(20)
        }
        assertEquals(
            DownloadQueue.EntryState.Completed,
            queue.entries.value.firstOrNull()?.state,
            "Restored download never completed: ${queue.entries.value}",
        )

        val storedInfo = runBlocking { dao.getTrickplayInfo(sourceId) }?.toItemTrickplayInfo()
        assertEquals(info, storedInfo)
        assertEquals(listOf("0", "1", "2"), storedSheets())
        for (sheet in 0..2) {
            assertEquals("sheet $sheet", readOffline(sheet))
        }
    }

    @Test
    fun `finalizing a download renamed before process death stores its missing trickplay layout and sheets`() {
        val partialPath = File(root, "Show/S01/S01E01 - Ep.mkv.download")
        val finalFile =
            File(root, "Show/S01/S01E01 - Ep.mkv").apply {
                parentFile!!.mkdirs()
                writeBytes(VIDEO)
            }
        seedActiveDownloadInRoom(partialPath)

        val active = runBlocking { newProcessDownloader().getActiveDownloads() }

        assertEquals(emptyList(), active)
        assertEquals(
            finalFile.absolutePath,
            runBlocking { dao.getSources(episodeId).single().path },
        )
        assertEquals(info, runBlocking { dao.getTrickplayInfo(sourceId) }?.toItemTrickplayInfo())
        assertEquals(listOf("0", "1", "2"), storedSheets())
    }

    @Test
    fun `resuming after process death mid-trickplay keeps already written sheets and downloads the rest`() {
        val partialFile =
            File(root, "Show/S01/S01E01 - Ep.mkv.download").apply {
                parentFile!!.mkdirs()
                writeBytes(VIDEO.copyOf(16))
            }
        seedActiveDownloadInRoom(partialFile)
        val trickplayDir =
            File(context.filesDir, "trickplay/$episodeId/$sourceId").apply { mkdirs() }
        File(trickplayDir, "0").writeText("sheet 0")

        val downloader = newProcessDownloader()
        val (resumedId, error) =
            runBlocking {
                val active = downloader.getActiveDownloads().single()
                downloader.downloadItem(active.item, maxBitrate = active.maxBitrate)
            }
        assertTrue(resumedId > 0L, "Expected resume download id, got error=$error")
        val deadline = System.currentTimeMillis() + 5_000L
        while (
            runBlocking { downloader.getProgress(resumedId).status } !=
                dev.jdtech.jellyfin.utils.download.DownloadStatus.SUCCESSFUL &&
                System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(20)
        }
        assertTrue(runBlocking { downloader.finalizeDownload(resumedId) })

        assertEquals(listOf(1, 2), requestedSheets.toList())
        assertEquals(listOf("0", "1", "2"), storedSheets())
        assertEquals(info, runBlocking { dao.getTrickplayInfo(sourceId) }?.toItemTrickplayInfo())
    }

    @Test
    fun `starting a download with an episode missing trickplayInfo resolves it from the server`() {
        val downloader = newProcessDownloader()
        val episodeWithoutTrickplay = serverEpisode(trickplayInfo = null)

        val (downloadId, error) =
            runBlocking { downloader.downloadItem(episodeWithoutTrickplay, sourceId) }
        assertTrue(downloadId > 0L, "Expected valid download id, got error=$error")

        val storedInfo = runBlocking { dao.getTrickplayInfo(sourceId) }?.toItemTrickplayInfo()
        assertEquals(info, storedInfo)
        assertEquals(listOf("0", "1", "2"), storedSheets())
    }

    @Test
    fun `finalizing succeeds without trickplay when the server is unreachable`() {
        val partialFile =
            File(root, "Show/S01/S01E01 - Ep.mkv.download").apply {
                parentFile!!.mkdirs()
                writeBytes(VIDEO)
            }
        seedActiveDownloadInRoom(partialFile)
        val engine = newEngine()
        engine.start(
            MediaDownloadEngine.Request(
                id = OLD_DOWNLOAD_ID,
                url = "http://server.test/video",
                destFile = partialFile,
                allowMetered = true,
                allowRoaming = true,
            )
        )
        val deadline = System.currentTimeMillis() + 5_000L
        while (
            engine.snapshot(OLD_DOWNLOAD_ID)?.status !=
                dev.jdtech.jellyfin.utils.download.DownloadStatus.SUCCESSFUL &&
                System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(20)
        }
        serverAvailable = false

        val finalized = runBlocking {
            newProcessDownloader(engine).finalizeDownload(OLD_DOWNLOAD_ID)
        }

        assertTrue(finalized)
        assertEquals(
            partialFile.absolutePath.removeSuffix(".download"),
            runBlocking { dao.getSources(episodeId).single().path },
        )
    }

    private fun seedActiveDownloadInRoom(sourcePathFile: File) = runBlocking {
        dao.insertShow(
            ShowEntity(
                id = showId,
                serverId = serverId,
                name = "Show",
                originalTitle = null,
                overview = "",
                runtimeTicks = 0L,
                communityRating = null,
                officialRating = null,
                status = "Continuing",
                productionYear = 2024,
                endDate = null,
            )
        )
        dao.insertSeason(
            SeasonEntity(
                id = seasonId,
                seriesId = showId,
                name = "Season 1",
                seriesName = "Show",
                overview = "",
                indexNumber = 1,
            )
        )
        dao.insertEpisode(
            EpisodeEntity(
                id = episodeId,
                serverId = serverId,
                seasonId = seasonId,
                seriesId = showId,
                name = "Ep",
                seriesName = "Show",
                overview = "",
                indexNumber = 1,
                indexNumberEnd = null,
                parentIndexNumber = 1,
                runtimeTicks = 0L,
                premiereDate = null,
                communityRating = null,
                chapters = null,
            )
        )
        dao.insertSource(
            ItemSourceEntity(
                id = sourceId,
                itemId = episodeId,
                name = "Original",
                type = ItemSourceType.LOCAL,
                path = sourcePathFile.absolutePath,
                downloadId = OLD_DOWNLOAD_ID,
            )
        )
    }

    private fun newEngine(): MediaDownloadEngine =
        MediaDownloadEngine(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val range = chain.request().header("Range")
                    val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
                    val builder =
                        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    if (from >= VIDEO.size) {
                        builder.code(416).message("Range Not Satisfiable").body("".toResponseBody())
                    } else if (from > 0) {
                        val slice = VIDEO.copyOfRange(from, VIDEO.size)
                        builder
                            .code(206)
                            .message("Partial Content")
                            .header("Content-Range", "bytes $from-${VIDEO.size - 1}/${VIDEO.size}")
                            .body(slice.toResponseBody())
                    } else {
                        builder.code(200).message("OK").body(VIDEO.toResponseBody())
                    }
                    builder.build()
                }
                .build(),
            Online,
        )

    private fun newProcessDownloader(engine: MediaDownloadEngine = newEngine()): DownloaderImpl {
        val repository =
            FakeRoles(ItemCatalog::class, PlaybackMedia::class, CurrentAccount::class) {
                _,
                method,
                args ->
                when (method.kotlinName) {
                    "getUserId" -> userId
                    "getItem",
                    "getEpisode" -> {
                        if (!serverAvailable) throw IllegalStateException("server unreachable")
                        serverEpisode(trickplayInfo = mapOf(sourceId to info))
                    }
                    "getShow" -> {
                        if (!serverAvailable) throw IllegalStateException("server unreachable")
                        Show(
                            id = showId,
                            name = "Show",
                            originalTitle = null,
                            overview = "",
                            sources = emptyList(),
                            seasons = emptyList(),
                            genres = emptyList(),
                            people = emptyList(),
                            runtimeTicks = 0L,
                            unplayedItemCount = null,
                            communityRating = null,
                            officialRating = null,
                            status = "Continuing",
                            productionYear = 2024,
                            endDate = null,
                            trailer = null,
                            canDownload = true,
                            canPlay = true,
                            played = false,
                            favorite = false,
                            images = ItemImages(),
                        )
                    }
                    "getSeason" -> {
                        if (!serverAvailable) throw IllegalStateException("server unreachable")
                        Season(
                            id = seasonId,
                            name = "Season 1",
                            seriesId = showId,
                            seriesName = "Show",
                            originalTitle = null,
                            overview = "",
                            sources = emptyList(),
                            indexNumber = 1,
                            episodes = emptyList(),
                            unplayedItemCount = null,
                            played = false,
                            favorite = false,
                            canPlay = true,
                            canDownload = true,
                            images = ItemImages(),
                        )
                    }
                    "getMediaSources" -> {
                        if (!serverAvailable) throw IllegalStateException("server unreachable")
                        listOf(
                            ItemSource(
                                id = sourceId,
                                name = "Original",
                                type = ItemSourceType.REMOTE,
                                path = "http://server.test/video",
                                size = VIDEO.size.toLong(),
                                mediaStreams = emptyList(),
                            )
                        )
                    }
                    "getServerSegments" -> emptyList<Any>()
                    "getTrickplayData" -> {
                        if (!serverAvailable) throw IllegalStateException("server unreachable")
                        val index = args[2] as Int
                        requestedSheets.add(index)
                        "sheet $index".toByteArray()
                    }
                    else ->
                        fail(
                            "Unexpected call ${method.declaringClass.simpleName}.${method.kotlinName}"
                        )
                }
            }
        return DownloaderImpl(
            context = context,
            downloadList = store,
            itemRows = store,
            downloadSources = store,
            downloadStreams = store,
            segmentsAndTrickplay = store,
            downloadUserData = store,
            queuedDownloads = store,
            catalogProvider = Provider { repository.role() },
            mediaProvider = Provider { repository.role() },
            accountProvider = Provider { repository.role() },
            settings = TestDownloadSettings(),
            currentServerId = TestCurrentServerId(serverId),
            workManagerProvider = Provider { WorkManager.getInstance(context) },
            engine = engine,
            texts = TestDownloadTexts,
        )
    }

    private fun serverEpisode(
        trickplayInfo: Map<dev.jdtech.jellyfin.models.MediaSourceId, ItemTrickplayInfo>?
    ) =
        Episode(
            id = episodeId,
            name = "Ep",
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
            seriesId = showId,
            seriesName = "Show",
            seasonId = seasonId,
            seasonName = "Season 1",
            communityRating = null,
            people = emptyList(),
            missing = false,
            images = ItemImages(),
            chapters = emptyList(),
            trickplayInfo = trickplayInfo,
        )

    private fun storedSheets(): List<String> =
        File(context.filesDir, "trickplay/$episodeId/$sourceId").list().orEmpty().sortedBy {
            it.toInt()
        }

    private fun readOffline(index: Int): String? {
        val away =
            fakeProxy(MediaServer::class.java.classLoader, arrayOf(MediaServer::class.java)) {
                _,
                method,
                _ ->
                throw IllegalStateException("MediaServer.${method.kotlinName}")
            }
                as MediaServer
        val repository =
            FakeRepositoryStores()
                .repository(away, downloadedFiles = DownloadedFilesOnDisk(context))
        return runBlocking { repository.getTrickplayData(episodeId, info.width, index, sourceId) }
            ?.decodeToString()
    }

    private object Online : NetworkConnectivity {
        override fun isOnline() = true

        override fun isMetered() = false

        override fun isRoaming() = false
    }

    private companion object {
        const val OLD_DOWNLOAD_ID = 42L
        val VIDEO = ByteArray(64) { it.toByte() }
    }
}
