package dev.jdtech.jellyfin.utils

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.jdtech.jellyfin.database.RoomDownloadStore
import dev.jdtech.jellyfin.database.ServerDatabase
import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.models.ItemSourceEntity
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.ItemTrickplayInfo
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toMediaSourceId
import dev.jdtech.jellyfin.repository.CurrentAccount
import dev.jdtech.jellyfin.repository.ItemCatalog
import dev.jdtech.jellyfin.repository.PlaybackMedia
import dev.jdtech.jellyfin.repository.roomMediaRepository
import dev.jdtech.jellyfin.testing.FakeResumeThresholdsStore
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
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#849): the trickplay sheets of a download. The downloader asked the server for one sheet more
 * than there are, and stored the sheets it got under their place in the list of those that came:
 * one failed sheet moved every later one down, and the offline preview showed the wrong moment.
 * Each sheet is now stored under its own index, and a failed one is missing on its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TrickplayDownloadTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val room =
        Room.inMemoryDatabaseBuilder(context, ServerDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val itemId = UUID.randomUUID().toString().toItemId()

    /** 10 x 10 thumbnails to a sheet, 250 thumbnails: sheets 0 and 1 full, sheet 2 half. */
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

    /** The sheets the server was asked for. */
    private val requested = CopyOnWriteArrayList<Int>()

    /** #860: the version each sheet was asked for. */
    private val requestedVersions = CopyOnWriteArrayList<String?>()

    @Before
    fun setUp() {
        // The source row the trickplay row belongs to, inserted by the download before it.
        runBlocking {
            room
                .getServerDatabaseDao()
                .insertSource(
                    ItemSourceEntity(
                        id = SOURCE_ID.toMediaSourceId(),
                        itemId = itemId,
                        name = "Original",
                        type = ItemSourceType.LOCAL,
                        path = "/downloads/Movie.mkv.download",
                    )
                )
        }
    }

    @After
    fun tearDown() {
        room.close()
        File(context.filesDir, "trickplay").deleteRecursively()
    }

    @Test
    fun `only the sheets there are are asked for, and each is stored under its index`() {
        download(failing = emptySet())

        assertEquals(listOf(0, 1, 2), requested)
        assertEquals(listOf("0", "1", "2"), storedSheets())
        for (sheet in 0..2) assertEquals(sheet(sheet), readOffline(sheet))
    }

    @Test
    fun `the sheets are asked for the version that is downloaded`() {
        download(failing = emptySet())

        assertEquals(listOf<String?>(SOURCE_ID, SOURCE_ID, SOURCE_ID), requestedVersions.toList())
    }

    @Test
    fun `the offline preview of a version shows that version's sheets`() {
        // #860: two versions of one item with sheets on the device. The folder listing used to
        // decide whose sheets came back, whichever version was played.
        for (version in listOf("version-a", "version-b")) {
            File(context.filesDir, "trickplay/$itemId/$version").mkdirs()
            File(context.filesDir, "trickplay/$itemId/$version/0").writeText("$version sheet 0")
        }

        assertEquals("version-a sheet 0", readOffline(0, "version-a"))
        assertEquals("version-b sheet 0", readOffline(0, "version-b"))
        // A version that is not downloaded has no sheets on the device: none of another's.
        assertNull(readOffline(0, "version-c"))
    }

    @Test
    fun `an exact multiple of a sheet asks for no empty sheet`() {
        download(failing = emptySet(), info = info.copy(thumbnailCount = 200))

        assertEquals(listOf(0, 1), requested)
        assertEquals(listOf("0", "1"), storedSheets())
    }

    @Test
    fun `a failed first, middle or last sheet leaves the others under their index`() {
        for (failed in 0..2) {
            requested.clear()
            File(context.filesDir, "trickplay").deleteRecursively()

            download(failing = setOf(failed))

            assertEquals(listOf(0, 1, 2), requested, message = "sheet $failed failed")
            assertEquals(
                (0..2).filter { it != failed }.map { "$it" },
                storedSheets(),
                message = "sheet $failed failed",
            )
            for (sheet in (0..2).filter { it != failed }) {
                assertEquals(sheet(sheet), readOffline(sheet), message = "sheet $failed failed")
            }
            // The server is away: no preview for the one sheet that is missing.
            assertNull(readOffline(failed), message = "sheet $failed failed")
        }
    }

    @Test
    fun `the preview of a position after a failed sheet shows that position's sheet`() {
        download(failing = setOf(0))

        // Thumbnail 150 (25 minutes in) is in sheet 1, cell 50, as the player works it out.
        val positionMs = 150L * info.interval
        val thumbnail = (positionMs / info.interval).toInt()
        val sheet = thumbnail / (info.tileWidth * info.tileHeight)

        assertEquals(1, sheet)
        assertEquals(sheet(1), readOffline(sheet))
    }

    private fun download(failing: Set<Int>, info: ItemTrickplayInfo = this.info) {
        val tiles = info.tileWidth * info.tileHeight
        val sheetsOnServer = (info.thumbnailCount + tiles - 1) / tiles
        val server =
            FakeRoles(ItemCatalog::class, PlaybackMedia::class, CurrentAccount::class) {
                _,
                method,
                args ->
                when (method.kotlinName) {
                    "getTrickplayData" -> {
                        val index = args[2] as Int
                        requested.add(index)
                        requestedVersions.add(args[3] as String?)
                        // A sheet that fails, or does not exist (the server answers 404), is null.
                        if (index in failing || index >= sheetsOnServer) null
                        else sheet(index).toByteArray()
                    }
                    else ->
                        throw IllegalStateException(
                            "${method.declaringClass.simpleName}.${method.kotlinName}"
                        )
                }
            }
        val store = RoomDownloadStore(room.getServerDatabaseDao())
        val downloader =
            DownloaderImpl(
                context,
                store,
                store,
                store,
                store,
                store,
                store,
                store,
                Provider { server.role() },
                Provider { server.role() },
                Provider { server.role() },
                TestDownloadSettings(),
                TestCurrentServerId(),
                Provider { error("No WorkManager in this test") },
                MediaDownloadEngine(OkHttpClient(), Online),
                TestDownloadTexts,
            )
        runBlocking { downloader.downloadTrickplayData(itemId, SOURCE_ID.toMediaSourceId(), info) }
    }

    private fun storedSheets(): List<String> =
        File(context.filesDir, "trickplay/$itemId/$SOURCE_ID").list().orEmpty().sortedBy {
            it.toInt()
        }

    /** What the player gets for [index] of [version] of the downloaded item, server away. */
    private fun readOffline(index: Int, version: String? = null): String? {
        val away =
            fakeProxy(MediaServer::class.java.classLoader, arrayOf(MediaServer::class.java)) {
                _,
                method,
                _ ->
                throw IllegalStateException("MediaServer.${method.kotlinName}")
            }
                as MediaServer
        val repository =
            roomMediaRepository(
                context,
                away,
                room.getServerDatabaseDao(),
                currentServer = { null },
                nextUpMaxDays = { 0 },
                resumeThresholds = FakeResumeThresholdsStore(),
            )
        return runBlocking {
                repository.getTrickplayData(itemId, info.width, index, version?.toMediaSourceId())
            }
            ?.decodeToString()
    }

    private fun sheet(index: Int) = "sheet $index"

    private object Online : NetworkConnectivity {
        override fun isOnline() = true

        override fun isMetered() = false

        override fun isRoaming() = false
    }

    private companion object {
        const val SOURCE_ID = "source"
    }
}
