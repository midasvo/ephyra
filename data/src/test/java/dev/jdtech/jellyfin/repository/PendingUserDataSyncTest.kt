package dev.jdtech.jellyfin.repository

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabase
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.FindroidMovieDto
import dev.jdtech.jellyfin.models.FindroidShowDto
import dev.jdtech.jellyfin.models.FindroidUserDataDto
import dev.jdtech.jellyfin.player.DeviceProfileBuilder
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#281): progress that could not reach the server asks for a sync when it is marked, not at
 * the next app start, and it survives its download being deleted until the sync has sent it. The
 * server here always fails: the API client has no address.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PendingUserDataSyncTest {
    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val movieId = UUID.fromString("00000000-0000-0000-0000-000000000040")
    private val showId = UUID.fromString("00000000-0000-0000-0000-000000000020")
    private val notStoredId = UUID.fromString("00000000-0000-0000-0000-000000000099")

    private val context: Context = RuntimeEnvironment.getApplication()
    private val room =
        Room.inMemoryDatabaseBuilder(context, ServerDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val database: ServerDatabaseDao = room.getServerDatabaseDao()

    private var syncRequests = 0

    private val repository: JellyfinRepository by lazy {
        // The SDK names the device after it; Robolectric has none.
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "test")
        val api = JellyfinApi(context).apply { userId = this@PendingUserDataSyncTest.userId }
        JellyfinRepositoryImpl(
            context,
            api,
            database,
            AppPreferences(context.getSharedPreferences("test", Context.MODE_PRIVATE)),
            DeviceProfileBuilder(context),
            UserDataSyncRequester { syncRequests++ },
        )
    }

    @After fun close() = room.close()

    private suspend fun storeMovie(positionTicks: Long = 0L, toBeSynced: Boolean = false) {
        database.insertMovie(
            FindroidMovieDto(
                id = movieId,
                serverId = SERVER,
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
        database.insertUserData(userData(movieId, positionTicks, toBeSynced))
    }

    private fun userData(itemId: UUID, positionTicks: Long, toBeSynced: Boolean) =
        FindroidUserDataDto(
            userId = userId,
            itemId = itemId,
            played = false,
            favorite = false,
            playbackPositionTicks = positionTicks,
            toBeSynced = toBeSynced,
        )

    private suspend fun pending() = database.getAllUserDataToBeSynced(SERVER, userId)

    @Test
    fun `a failed write on a downloaded item asks for a sync`() = runBlocking {
        storeMovie()

        repository.postPlaybackProgress(movieId, 600_000_000L, isPaused = false, transcoded = false)

        assertEquals(1, syncRequests)
        assertEquals(600_000_000L, pending().single().playbackPositionTicks)
    }

    @Test
    fun `a run of failed progress reports asks once`() = runBlocking {
        storeMovie()

        repository.postPlaybackProgress(movieId, 600_000_000L, isPaused = false, transcoded = false)
        repository.postPlaybackProgress(movieId, 700_000_000L, isPaused = false, transcoded = false)
        repository.postPlaybackStop(movieId, 800_000_000L, playedPercentage = 50)

        assertEquals(1, syncRequests)
        assertEquals(800_000_000L, pending().single().playbackPositionTicks)
    }

    @Test
    fun `a failed write on an item that is not stored asks for nothing`() = runBlocking {
        repository.markAsPlayed(notStoredId)

        assertEquals(0, syncRequests)
        assertTrue(pending().isEmpty())
    }

    @Test
    fun `deleting a download keeps progress that has not been synced`() = runBlocking {
        storeMovie(positionTicks = 600_000_000L, toBeSynced = true)

        database.deleteMovie(movieId)
        database.deleteSyncedUserData(movieId)

        assertEquals(600_000_000L, pending().single().playbackPositionTicks)
        assertTrue(database.hasUserDataToBeSynced())
    }

    @Test
    fun `deleting a download removes progress that is in sync`() = runBlocking {
        storeMovie(positionTicks = 600_000_000L)

        database.deleteMovie(movieId)
        database.deleteSyncedUserData(movieId)

        assertNull(database.getUserData(movieId, userId))
    }

    @Test
    fun `the kept row of a deleted download goes once it is synced`() = runBlocking {
        storeMovie(positionTicks = 600_000_000L, toBeSynced = true)
        database.deleteMovie(movieId)
        database.deleteSyncedUserData(movieId)

        // Still marked: not removed.
        database.deleteUserDataIfOrphaned(userId, movieId)
        assertNotNull(database.getUserData(movieId, userId))

        database.clearUserDataToBeSynced(userId, movieId, false, false, 600_000_000L)
        database.deleteUserDataIfOrphaned(userId, movieId)

        assertNull(database.getUserData(movieId, userId))
    }

    @Test
    fun `a synced row of a stored download stays`() = runBlocking {
        storeMovie(positionTicks = 600_000_000L)

        database.deleteUserDataIfOrphaned(userId, movieId)

        assertNotNull(database.getUserData(movieId, userId))
    }

    @Test
    fun `a row that changed during the sync stays marked`() = runBlocking {
        storeMovie(positionTicks = 700_000_000L, toBeSynced = true)

        // The sync sent 600, a failed write has since moved the row to 700.
        database.clearUserDataToBeSynced(userId, movieId, false, false, 600_000_000L)

        assertEquals(700_000_000L, pending().single().playbackPositionTicks)
    }

    @Test
    fun `a show's row is not synced`() = runBlocking {
        database.insertShow(
            FindroidShowDto(
                id = showId,
                serverId = SERVER,
                name = "Show",
                originalTitle = null,
                overview = "",
                runtimeTicks = 0L,
                communityRating = null,
                officialRating = null,
                status = "",
                productionYear = null,
                endDate = null,
            )
        )
        database.insertUserData(userData(showId, 0L, toBeSynced = true))

        assertTrue(pending().isEmpty())
        assertFalse(database.hasUserDataToBeSynced())
    }

    private companion object {
        const val SERVER = "server"
    }
}
