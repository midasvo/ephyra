package dev.jdtech.jellyfin.utils

import android.app.Application
import android.content.Context
import android.os.Environment
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.FindroidMediaStreamDto
import dev.jdtech.jellyfin.models.FindroidSourceDto
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.download.DownloadStatus
import dev.jdtech.jellyfin.utils.download.MediaDownloadEngine
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jellyfin.sdk.model.api.MediaStreamType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment

/**
 * CE (#573): the queue finalized a download by its video's id only, so an external subtitle kept
 * its `.download` name and path for good. It is now finalized with its video, or when it finishes
 * after it, and the start-up repair renames the ones older builds left behind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SubtitleFinalizeTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var root: File
    private val itemId = UUID.randomUUID()
    private val slowSubtitle = CountDownLatch(1)

    private val sources = ConcurrentHashMap<String, FindroidSourceDto>()
    private val streams = ConcurrentHashMap<UUID, FindroidMediaStreamDto>()
    private val streamReads = AtomicInteger()
    private val pathUpdates = AtomicInteger()

    private val engine =
        MediaDownloadEngine(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val path = chain.request().url.encodedPath
                    if (path == "/slow-subtitle") slowSubtitle.await(10, TimeUnit.SECONDS)
                    val code = if (path == "/missing-subtitle") 404 else 200
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message(if (code == 200) "OK" else "Not Found")
                        .body(if (code == 200) "1\n00:00:00,000 --> 00:00:01,000\nHallo\n".toResponseBody() else "".toResponseBody())
                        .build()
                }
                .build(),
            Online,
        )

    @Before
    fun setUp() {
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        val external = context.getExternalFilesDirs(null).first()
        ShadowEnvironment.setExternalStorageState(external, Environment.MEDIA_MOUNTED)
        root = File(external, "downloads").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        slowSubtitle.countDown()
        root.deleteRecursively()
    }

    @Test
    fun `finishing the video finalizes its finished subtitle`() {
        val source = downloadingSource()
        val subtitle = subtitleRow(source, downloadId = SUBTITLE_ID)
        start(VIDEO_ID, "/video", source.path)
        start(SUBTITLE_ID, "/subtitle", subtitle.path)
        awaitStatus(VIDEO_ID, DownloadStatus.SUCCESSFUL)
        awaitStatus(SUBTITLE_ID, DownloadStatus.SUCCESSFUL)

        assertTrue(runBlocking { downloader().finalizeDownload(VIDEO_ID) })

        assertFinalized(subtitle)
        assertFalse(sources.getValue(SOURCE_ID).path.endsWith(".download"))
    }

    @Test
    fun `a subtitle still downloading when the video finishes is finalized when it finishes`() {
        val source = downloadingSource()
        val subtitle = subtitleRow(source, downloadId = SUBTITLE_ID)
        start(VIDEO_ID, "/video", source.path)
        start(SUBTITLE_ID, "/slow-subtitle", subtitle.path)
        awaitStatus(VIDEO_ID, DownloadStatus.SUCCESSFUL)

        runBlocking { downloader().finalizeDownload(VIDEO_ID) }
        assertTrue(streams.getValue(subtitle.id).path.endsWith(".download"))

        slowSubtitle.countDown()
        awaitCondition { !streams.getValue(subtitle.id).path.endsWith(".download") }
        assertFinalized(subtitle)
    }

    @Test
    fun `a job that gives up leaves the subtitle for the start-up repair`() {
        val source = downloadingSource()
        val subtitle = subtitleRow(source, downloadId = SUBTITLE_ID)
        start(VIDEO_ID, "/video", source.path)
        start(SUBTITLE_ID, "/slow-subtitle", subtitle.path)
        awaitStatus(VIDEO_ID, DownloadStatus.SUCCESSFUL)
        val downloader = downloader(timeoutMs = 200)

        runBlocking { downloader.finalizeDownload(VIDEO_ID) }
        Thread.sleep(600)
        assertTrue(streams.getValue(subtitle.id).path.endsWith(".download"))

        // While the transfer runs, the repair leaves it alone.
        runBlocking { downloader.finalizeFinishedMediaStreams() }
        assertTrue(streams.getValue(subtitle.id).path.endsWith(".download"))

        // The next start: the engine no longer knows the transfer, the file holds what came.
        slowSubtitle.countDown()
        awaitStatus(SUBTITLE_ID, DownloadStatus.SUCCESSFUL)
        engine.cancel(SUBTITLE_ID)
        runBlocking { downloader.finalizeFinishedMediaStreams() }
        assertFinalized(subtitle)
    }

    @Test
    fun `the job stops when the download is deleted`() {
        val source = downloadingSource()
        val subtitle = subtitleRow(source, downloadId = SUBTITLE_ID)
        start(VIDEO_ID, "/video", source.path)
        start(SUBTITLE_ID, "/slow-subtitle", subtitle.path)
        awaitStatus(VIDEO_ID, DownloadStatus.SUCCESSFUL)

        runBlocking { downloader().finalizeDownload(VIDEO_ID) }
        // What deleteItem does: cancel the transfer, drop the file and the rows.
        engine.cancel(SUBTITLE_ID)
        File(subtitle.path).delete()
        streams.clear()
        sources.clear()
        Thread.sleep(200)
        val reads = streamReads.get()
        Thread.sleep(300)

        assertEquals("the job still polls", reads, streamReads.get())
    }

    @Test
    fun `the repair renames a subtitle an older build left, and a second run changes nothing`() {
        val source = finishedSource()
        val subtitle = subtitleRow(source, downloadId = SUBTITLE_ID)
        writeSubtitle(subtitle.path)
        val downloader = downloader()

        runBlocking { downloader.finalizeFinishedMediaStreams() }
        assertFinalized(subtitle)
        assertEquals(1, pathUpdates.get())

        runBlocking { downloader.finalizeFinishedMediaStreams() }
        assertEquals(1, pathUpdates.get())
        assertFinalized(subtitle)
    }

    @Test
    fun `the repair only updates the row when the file was already renamed`() {
        val source = finishedSource()
        val subtitle = subtitleRow(source, downloadId = SUBTITLE_ID)
        writeSubtitle(subtitle.path.removeSuffix(".download"))

        runBlocking { downloader().finalizeFinishedMediaStreams() }

        assertFinalized(subtitle)
    }

    @Test
    fun `the repair leaves a subtitle whose transfer failed`() {
        val source = finishedSource()
        val subtitle = subtitleRow(source, downloadId = SUBTITLE_ID)
        writeSubtitle(subtitle.path)
        start(SUBTITLE_ID, "/missing-subtitle", File(root, "other.srt.download").absolutePath)
        awaitStatus(SUBTITLE_ID, DownloadStatus.FAILED)

        runBlocking { downloader().finalizeFinishedMediaStreams() }

        assertEquals(subtitle.path, streams.getValue(subtitle.id).path)
        assertTrue(File(subtitle.path).exists())
        assertFalse(File(subtitle.path.removeSuffix(".download")).exists())
    }

    @Test
    fun `the repair leaves a download in progress and a subtitle with no file`() {
        val downloading = downloadingSource()
        val inProgress = subtitleRow(downloading, downloadId = SUBTITLE_ID)
        writeSubtitle(inProgress.path)
        val finished = finishedSource(id = "finished")
        val noFile = subtitleRow(finished, downloadId = SUBTITLE_ID + 1)

        runBlocking { downloader().finalizeFinishedMediaStreams() }

        assertEquals(inProgress.path, streams.getValue(inProgress.id).path)
        assertTrue(File(inProgress.path).exists())
        assertEquals(noFile.path, streams.getValue(noFile.id).path)
        assertEquals(0, pathUpdates.get())
    }

    @Test
    fun `the orphan sweep finalizes an old leftover subtitle and keeps it`() {
        val source = finishedSource()
        val subtitle = subtitleRow(source, downloadId = SUBTITLE_ID)
        writeSubtitle(subtitle.path)
        val old = System.currentTimeMillis() - ORPHAN_MIN_AGE_MS - 60_000
        File(subtitle.path).setLastModified(old)
        File(source.path).setLastModified(old)

        runBlocking { downloader().sweepOrphans() }

        assertFinalized(subtitle)
        assertTrue(File(source.path).exists())
    }

    @Test
    fun `the orphan sweep keeps an old subtitle it may not rename`() {
        val source = finishedSource()
        val subtitle = subtitleRow(source, downloadId = SUBTITLE_ID)
        writeSubtitle(subtitle.path)
        File(subtitle.path).setLastModified(System.currentTimeMillis() - ORPHAN_MIN_AGE_MS - 60_000)
        start(SUBTITLE_ID, "/missing-subtitle", File(root, "other.srt.download").absolutePath)
        awaitStatus(SUBTITLE_ID, DownloadStatus.FAILED)

        runBlocking { downloader().sweepOrphans() }

        assertEquals(subtitle.path, streams.getValue(subtitle.id).path)
        assertTrue(File(subtitle.path).exists())
    }

    private fun assertFinalized(row: FindroidMediaStreamDto) {
        val finalPath = row.path.removeSuffix(".download")
        assertEquals(finalPath, streams.getValue(row.id).path)
        assertTrue(File(finalPath).length() > 0)
        assertFalse(File(row.path).exists())
    }

    private fun downloadingSource() =
        addSource(SOURCE_ID, File(root, "Tracks Movie/Tracks Movie.mkv.download"), VIDEO_ID)

    private fun finishedSource(id: String = SOURCE_ID): FindroidSourceDto {
        val file = File(root, "$id/Tracks Movie.mkv")
        file.parentFile!!.mkdirs()
        file.writeBytes(ByteArray(10))
        return addSource(id, file, VIDEO_ID)
    }

    private fun addSource(id: String, file: File, downloadId: Long): FindroidSourceDto {
        file.parentFile!!.mkdirs()
        val source =
            FindroidSourceDto(
                id = id,
                itemId = itemId,
                name = "Original",
                type = FindroidSourceType.LOCAL,
                path = file.absolutePath,
                downloadId = downloadId,
            )
        sources[id] = source
        return source
    }

    private fun subtitleRow(source: FindroidSourceDto, downloadId: Long): FindroidMediaStreamDto {
        val id = UUID.randomUUID()
        val row =
            FindroidMediaStreamDto(
                id = id,
                sourceId = source.id,
                title = "Deutsch",
                displayTitle = null,
                language = "deu",
                type = MediaStreamType.SUBTITLE,
                codec = "subrip",
                isExternal = true,
                path = File(root, "Tracks Movie.${source.id}.$id.sub.download").absolutePath,
                channelLayout = null,
                videoRangeType = null,
                height = null,
                width = null,
                videoDoViTitle = null,
                downloadId = downloadId,
            )
        streams[id] = row
        return row
    }

    private fun writeSubtitle(path: String) = File(path).writeText("1\n00:00:00,000 --> 00:00:01,000\nHallo\n")

    private fun start(id: Long, path: String, dest: String) =
        engine.start(
            MediaDownloadEngine.Request(
                id = id,
                url = "http://server.test$path",
                destFile = File(dest),
                allowMetered = true,
                allowRoaming = true,
            )
        )

    private fun awaitStatus(id: Long, status: Int) = awaitCondition { engine.snapshot(id)?.status == status }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }

    private fun database() =
        Proxy.newProxyInstance(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getSourceByDownloadId" -> sources.values.firstOrNull { it.downloadId == args[0] }
                "getMediaStreamByDownloadId" -> streams.values.firstOrNull { it.downloadId == args[0] }
                "setSourceDownloaded" -> {
                    val id = args[0] as String
                    sources.computeIfPresent(id) { _, s -> s.copy(path = args[1] as String, downloadedAt = args[2] as Long) }
                    Unit
                }
                "getMediaStreamsBySourceId" -> {
                    streamReads.incrementAndGet()
                    streams.values.filter { it.sourceId == args[0] }
                }
                "setMediaStreamPath" -> {
                    pathUpdates.incrementAndGet()
                    streams.computeIfPresent(args[0] as UUID) { _, s -> s.copy(path = args[1] as String) }
                    Unit
                }
                "deleteMediaStream" -> {
                    streams.remove(args[0] as UUID)
                    Unit
                }
                "getCompletedDownloadSources" -> sources.values.filter { !it.path.endsWith(".download") }
                "getActiveDownloadSources" -> sources.values.filter { it.path.endsWith(".download") }
                "getAllItemIds" -> listOf(itemId)
                else -> throw NotImplementedError("ServerDatabaseDao.${method.name}")
            }
        } as ServerDatabaseDao

    private fun downloader(timeoutMs: Long = 5_000): DownloaderImpl {
        val repository =
            Proxy.newProxyInstance(
                JellyfinRepository::class.java.classLoader,
                arrayOf(JellyfinRepository::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getUserId" -> UUID.randomUUID()
                    // The server is not there: a failed subtitle cannot be started again.
                    else -> throw IllegalStateException("JellyfinRepository.${method.name}")
                }
            } as JellyfinRepository
        val preferences = context.getSharedPreferences("subtitle-finalize-test", Context.MODE_PRIVATE)
        return DownloaderImpl(
            context,
            database(),
            Provider { repository },
            AppPreferences(preferences),
            Provider { error("No WorkManager in this test") },
            engine,
            mediaStreamFinalizeTimeoutMs = timeoutMs,
            mediaStreamFinalizePollMs = 20,
        )
    }

    private object Online : NetworkConnectivity {
        override fun isOnline() = true

        override fun isMetered() = false

        override fun isRoaming() = false
    }

    private companion object {
        const val SOURCE_ID = "source"
        const val VIDEO_ID = 1L
        const val SUBTITLE_ID = 2L
    }
}
