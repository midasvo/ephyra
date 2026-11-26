package dev.jdtech.jellyfin.work

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import dev.jdtech.jellyfin.database.RoomPendingUserDataStore
import dev.jdtech.jellyfin.database.ServerDatabase
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.domain.ports.ServerUserData
import dev.jdtech.jellyfin.domain.ports.UserDataSyncPort
import dev.jdtech.jellyfin.domain.ports.UserDataSyncPorts
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.MovieEntity
import dev.jdtech.jellyfin.models.ServerAddressEntity
import dev.jdtech.jellyfin.models.ServerEntity
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.models.ServerType
import dev.jdtech.jellyfin.models.UserDataEntity
import dev.jdtech.jellyfin.models.UserEntity
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toServerAddressId
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.repository.DataChangeEvents
import dev.jdtech.jellyfin.repository.PlaybackEvents
import dev.jdtech.jellyfin.repository.SmartDownloadChecker
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #762: a whole run of [SyncWorker] against a fake [UserDataSyncPort] and an in-memory Room
 * database, one test per outcome for a row written while the server was away. The decisions alone
 * are in [SyncWorkerDecisionsTest] and [LastPlayedDateSyncTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SyncWorkerRunTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val room =
        Room.inMemoryDatabaseBuilder(context, ServerDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val database: ServerDatabaseDao = room.getServerDatabaseDao()
    private val preferences =
        AppPreferences(context.getSharedPreferences("sync-worker-run", Context.MODE_PRIVATE))
    private val port = FakeUserDataSyncPort()
    private val opened = mutableListOf<Triple<String, String?, UserId>>()

    @After fun close() = room.close()

    /** The saved server with its address and one user. */
    private suspend fun storeServer(type: String = ServerType.JELLYFIN.value) {
        database.insertServer(
            ServerEntity(
                id = SERVER.toServerId(),
                name = "Server",
                currentServerAddressId = ADDRESS_ID,
                currentUserId = USER_ID,
                type = type,
            )
        )
        database.insertServerAddress(ServerAddressEntity(ADDRESS_ID, SERVER.toServerId(), ADDRESS))
        database.insertUser(
            UserEntity(
                id = USER_ID,
                name = "User",
                serverId = SERVER.toServerId(),
                accessToken = TOKEN,
            )
        )
    }

    /** A downloaded movie, so its row outlives the sync. */
    private suspend fun storeMovie() {
        database.insertMovie(
            MovieEntity(
                id = ITEM_ID,
                serverId = SERVER.toServerId(),
                name = "Movie",
                originalTitle = null,
                overview = "",
                runtimeTicks = 6_000_000_000L,
                premiereDate = null,
                communityRating = null,
                officialRating = null,
                status = "",
                productionYear = null,
                endDate = null,
                chapters = null,
            )
        )
    }

    /** The row as playback wrote it while the server was away. */
    private suspend fun storeMarkedRow() {
        database.insertUserData(
            UserDataEntity(
                userId = USER_ID,
                itemId = ITEM_ID,
                played = false,
                favorite = false,
                playbackPositionTicks = LOCAL_POSITION,
                toBeSynced = true,
                lastPlayedDate = epochMillisOf(LOCAL_PLAYED),
            )
        )
    }

    private fun onServer(lastPlayed: LocalDateTime) {
        port.userData =
            mapOf(
                ITEM_ID to
                    ServerUserData(
                        itemId = ITEM_ID,
                        played = false,
                        favorite = false,
                        playbackPositionTicks = SERVER_POSITION,
                        lastPlayedDate = lastPlayed,
                    )
            )
    }

    private fun run(): ListenableWorker.Result = runBlocking {
        val ports = UserDataSyncPorts { baseUrl, accessToken, userId ->
            opened += Triple(baseUrl, accessToken, userId)
            port
        }
        val worker =
            TestListenableWorkerBuilder<SyncWorker>(context)
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ) =
                            SyncWorker(
                                appContext,
                                workerParameters,
                                RoomPendingUserDataStore(database),
                                mapOf(ServerType.JELLYFIN to ports),
                                SmartDownloadChecker.None,
                                PlaybackEvents(),
                                DataChangeEvents(),
                            )
                    }
                )
                .build()
        worker.doWork()
    }

    @Test
    fun `a later play on the server replaces the row and nothing of it is sent`() = runBlocking {
        storeServer()
        storeMovie()
        storeMarkedRow()
        onServer(lastPlayed = LOCAL_PLAYED.plusHours(1))

        assertEquals(ListenableWorker.Result.success(), run())

        assertEquals(
            listOf<Triple<String, String?, UserId>>(Triple(ADDRESS, TOKEN, USER_ID)),
            opened,
        )
        assertEquals(listOf("itemLeftPlaying", "userDataOf [${ITEM_ID.asString()}]"), port.calls)
        val row = assertNotNull(database.getUserData(ITEM_ID, USER_ID))
        assertEquals(SERVER_POSITION, row.playbackPositionTicks)
        assertEquals(epochMillisOf(LOCAL_PLAYED.plusHours(1)), row.lastPlayedDate)
        assertFalse(row.toBeSynced)
    }

    @Test
    fun `a later play here is sent and the row is no longer marked`() = runBlocking {
        storeServer()
        storeMovie()
        storeMarkedRow()
        onServer(lastPlayed = LOCAL_PLAYED.minusHours(1))

        assertEquals(ListenableWorker.Result.success(), run())

        assertEquals(
            listOf(
                "itemLeftPlaying",
                "userDataOf [${ITEM_ID.asString()}]",
                "putUserData " +
                    ServerUserData(
                        itemId = ITEM_ID,
                        played = false,
                        favorite = false,
                        playbackPositionTicks = LOCAL_POSITION,
                        lastPlayedDate = LOCAL_PLAYED,
                    ),
            ),
            port.calls,
        )
        val row = assertNotNull(database.getUserData(ITEM_ID, USER_ID))
        assertEquals(LOCAL_POSITION, row.playbackPositionTicks)
        assertFalse(row.toBeSynced)
    }

    @Test
    fun `an item gone from the server drops its row`() = runBlocking {
        // The download was deleted before the sync ran: the row was kept only to be sent.
        storeServer()
        storeMarkedRow()
        port.failPut = ServerException.HttpStatus(404)

        assertEquals(ListenableWorker.Result.success(), run())

        assertEquals(3, port.calls.size)
        assertNull(database.getUserData(ITEM_ID, USER_ID))
    }

    @Test
    fun `a server error retries and keeps the row marked`() = runBlocking {
        storeServer()
        storeMovie()
        storeMarkedRow()
        port.failPut = ServerException.HttpStatus(503)

        assertEquals(ListenableWorker.Result.retry(), run())

        val row = assertNotNull(database.getUserData(ITEM_ID, USER_ID))
        assertTrue(row.toBeSynced)
        assertEquals(LOCAL_POSITION, row.playbackPositionTicks)
    }

    @Test
    fun `a server of a type without an adapter is left as it is, without a retry`() = runBlocking {
        // CE (#770): saved by a build that knows more server types.
        storeServer(type = "emby")
        storeMovie()
        storeMarkedRow()

        assertEquals(ListenableWorker.Result.success(), run())

        assertTrue(opened.isEmpty())
        assertTrue(port.calls.isEmpty())
        val row = assertNotNull(database.getUserData(ITEM_ID, USER_ID))
        assertTrue(row.toBeSynced)
        assertEquals(LOCAL_POSITION, row.playbackPositionTicks)
    }

    @Test
    fun `a server that is not reached ends the run after one request`() = runBlocking {
        storeServer()
        storeMovie()
        storeMarkedRow()
        port.failLeftPlaying = ServerException.Unreachable()

        assertEquals(ListenableWorker.Result.retry(), run())

        assertEquals(listOf("itemLeftPlaying"), port.calls)
        assertTrue(assertNotNull(database.getUserData(ITEM_ID, USER_ID)).toBeSynced)
    }

    /** Answers with [userData] and records each call; a `fail*` is thrown by its call. */
    private class FakeUserDataSyncPort : UserDataSyncPort {
        var userData: Map<ItemId, ServerUserData> = emptyMap()
        var failPut: Exception? = null
        var failLeftPlaying: Exception? = null
        val calls = mutableListOf<String>()

        override suspend fun userDataOf(itemIds: List<ItemId>): Map<ItemId, ServerUserData> {
            calls += "userDataOf ${itemIds.map { it.asString() }}"
            return userData.filterKeys { it in itemIds }
        }

        override suspend fun putUserData(data: ServerUserData) {
            calls += "putUserData $data"
            failPut?.let { throw it }
        }

        override suspend fun setFavorite(itemId: ItemId, favorite: Boolean) {
            calls += "setFavorite ${itemId.asString()} $favorite"
        }

        override suspend fun reportStopped(itemId: ItemId, positionTicks: Long) {
            calls += "reportStopped ${itemId.asString()} $positionTicks"
        }

        override suspend fun itemLeftPlaying(): ItemId? {
            calls += "itemLeftPlaying"
            failLeftPlaying?.let { throw it }
            return null
        }
    }

    private companion object {
        const val SERVER = "server"
        const val ADDRESS = "http://jellyfin.test:8096"
        const val TOKEN = "token"
        val ADDRESS_ID = "00000000-0000-0000-0000-0000000000ad".toServerAddressId()
        val USER_ID: UserId = "00000000-0000-0000-0000-000000000001".toUserId()
        val ITEM_ID: ItemId = "00000000-0000-0000-0000-000000000040".toItemId()
        const val LOCAL_POSITION = 1_800_000_000L
        const val SERVER_POSITION = 3_600_000_000L
        val LOCAL_PLAYED: LocalDateTime = LocalDateTime.of(2026, 11, 20, 20, 15, 0)
    }
}
