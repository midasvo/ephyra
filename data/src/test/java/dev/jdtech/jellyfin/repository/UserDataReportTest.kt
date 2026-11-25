package dev.jdtech.jellyfin.repository

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.jdtech.jellyfin.database.RoomDownloadStore
import dev.jdtech.jellyfin.database.ServerDatabase
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.MovieEntity
import dev.jdtech.jellyfin.models.UserDataEntity
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.models.toUserId
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#281): a change to a download's user data is written and marked to be synced before the
 * server is asked, and the mark only comes off when the server got exactly that change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UserDataReportTest {
    private val userId = "00000000-0000-0000-0000-000000000001".toUserId()
    private val movieId = "00000000-0000-0000-0000-000000000040".toItemId()
    private val notStoredId = "00000000-0000-0000-0000-000000000099".toItemId()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val room =
        Room.inMemoryDatabaseBuilder(context, ServerDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val database: ServerDatabaseDao = room.getServerDatabaseDao()

    private var syncRequests = 0
    private val syncRequester = UserDataSyncRequester { syncRequests++ }

    @After fun close() = room.close()

    private suspend fun storeMovie(positionTicks: Long = 0L, toBeSynced: Boolean = false) {
        database.insertMovie(
            MovieEntity(
                id = movieId,
                serverId = SERVER.toServerId(),
                name = "Movie",
                originalTitle = null,
                overview = "",
                runtimeTicks = 0L,
                premiereDate = null,
                communityRating = null,
                officialRating = null,
                status = "",
                productionYear = null,
                endDate = null,
                chapters = null,
            )
        )
        database.insertUserData(
            UserDataEntity(
                userId = userId,
                itemId = movieId,
                played = false,
                favorite = false,
                playbackPositionTicks = positionTicks,
                toBeSynced = toBeSynced,
            )
        )
    }

    private suspend fun row() = database.getUserData(movieId, userId)!!

    private suspend fun report(
        positionTicks: Long,
        itemId: ItemId = movieId,
        request: suspend () -> Unit = {},
    ): Boolean =
        writeAndReport(
            RoomDownloadStore(database),
            syncRequester,
            userId,
            itemId,
            change = { it.copy(positionTicks = positionTicks) },
            report = request,
        )

    @Test
    fun `the change is written and marked before the server is asked`() = runBlocking {
        storeMovie()
        var seen: UserDataEntity? = null

        report(600_000_000L) { seen = row() }

        assertEquals(600_000_000L, seen?.playbackPositionTicks, message = "written before asking")
        assertTrue(seen?.toBeSynced == true, message = "marked before asking")
    }

    @Test
    fun `the mark comes off when the server got the change`() = runBlocking {
        storeMovie()

        val reached = report(600_000_000L)

        assertTrue(reached, message = "the request succeeded")
        assertFalse(row().toBeSynced, message = "the server has it, nothing to sync")
        assertEquals(600_000_000L, row().playbackPositionTicks)
        assertEquals(0, syncRequests, message = "no sync for a change the server has")
    }

    @Test
    fun `the mark stays when the server was not reached, and a sync is asked for`() = runBlocking {
        storeMovie()

        val reached = report(600_000_000L) { throw IOException("no server") }

        assertFalse(reached, message = "the request failed")
        assertTrue(row().toBeSynced, message = "the change waits for the sync")
        assertEquals(1, syncRequests)
    }

    @Test
    fun `a run of failures asks for one sync`() = runBlocking {
        storeMovie()

        report(600_000_000L) { throw IOException("no server") }
        report(700_000_000L) { throw IOException("no server") }

        assertTrue(row().toBeSynced)
        assertEquals(700_000_000L, row().playbackPositionTicks)
        assertEquals(1, syncRequests, message = "asked once for the run")
    }

    @Test
    fun `a newer change made while the request waits keeps its mark`() = runBlocking {
        storeMovie()

        report(600_000_000L) {
            // A later progress report lands while this one is under way, and fails.
            report(700_000_000L) { throw IOException("no server") }
        }

        assertEquals(700_000_000L, row().playbackPositionTicks)
        assertTrue(row().toBeSynced, message = "the newer change still has to reach the server")
    }

    @Test
    fun `a row marked before keeps its mark when a later request succeeds`() = runBlocking {
        // An earlier change (say a favourite) is still waiting for the sync.
        storeMovie(toBeSynced = true)

        report(600_000_000L)

        assertTrue(row().toBeSynced, message = "the earlier change was not sent by this request")
    }

    @Test
    fun `a refresh from the server while the request waits leaves the change alone`() =
        runBlocking {
            storeMovie()

            report(600_000_000L) {
                val changed =
                    database.refreshUserDataFromServer(
                        userId = userId,
                        itemId = movieId,
                        played = true,
                        favorite = false,
                        playbackPositionTicks = 0L,
                        lastPlayedDate = 1_000L,
                    )
                assertEquals(0, changed, message = "the marked row is not refreshed")
                throw IOException("no server")
            }

            assertEquals(600_000_000L, row().playbackPositionTicks)
            assertFalse(row().played)
            assertTrue(row().toBeSynced)
        }

    @Test
    fun `an item that is not stored is only reported`() = runBlocking {
        val reached = report(600_000_000L, itemId = notStoredId) { throw IOException("no server") }

        assertFalse(reached)
        assertEquals(0, syncRequests, message = "nothing stored, nothing to sync")
        assertEquals(null, database.getUserData(notStoredId, userId))
    }

    private companion object {
        const val SERVER = "server"
    }
}
