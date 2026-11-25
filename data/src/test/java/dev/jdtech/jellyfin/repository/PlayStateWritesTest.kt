package dev.jdtech.jellyfin.repository

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.jdtech.jellyfin.database.ServerDatabase
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.domain.ports.PlaybackPort
import dev.jdtech.jellyfin.domain.ports.PlaybackReport
import dev.jdtech.jellyfin.domain.ports.UserDataPort
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.MovieEntity
import dev.jdtech.jellyfin.models.UserDataEntity
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.testing.FakeResumeThresholdsStore
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #898: the rows the marks and a stop write when the server answers, as `PlayState` decides them.
 * `PendingUserDataSyncTest` covers the same calls with a server that does not answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayStateWritesTest {
    private val userId = "00000000-0000-0000-0000-000000000001".toUserId()
    private val movieId = "00000000-0000-0000-0000-000000000040".toItemId()
    private val otherUserId = "00000000-0000-0000-0000-000000000002".toUserId()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val room =
        Room.inMemoryDatabaseBuilder(context, ServerDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val database: ServerDatabaseDao = room.getServerDatabaseDao()

    private val sent = mutableListOf<String>()

    /** Runs while the server takes a played or unplayed mark, before it answers. */
    private var whileMarking: () -> Unit = {}

    private val unanswered = UnansweredServer(userId)

    /** Answers the marks and the stop; anything else fails as [UnansweredServer] does. */
    private val server: MediaServer =
        object : MediaServer by unanswered {
            override val userData =
                object : UserDataPort {
                    override suspend fun setFavorite(itemId: ItemId, favorite: Boolean) {
                        sent += "favorite $favorite"
                    }

                    override suspend fun setPlayed(itemId: ItemId, played: Boolean) {
                        whileMarking()
                        sent += "played $played"
                    }
                }

            override val playback =
                object : PlaybackPort by unanswered.playback {
                    override suspend fun reportStopped(report: PlaybackReport) {
                        sent += "stopped ${report.positionTicks}"
                    }
                }
        }

    private val repository: MediaRepositoryImpl by lazy {
        roomMediaRepository(
            context,
            server,
            database,
            currentServer = { null },
            nextUpMaxDays = { 0 },
            resumeThresholds = FakeResumeThresholdsStore(),
            syncRequester = UserDataSyncRequester {},
        )
    }

    @After fun close() = room.close()

    private suspend fun storeMovie(row: UserDataEntity) {
        database.insertMovie(
            MovieEntity(
                id = movieId,
                serverId = "server".toServerId(),
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
        database.insertUserData(row)
    }

    private val played =
        UserDataEntity(
            userId = userId,
            itemId = movieId,
            played = true,
            favorite = false,
            playbackPositionTicks = 600_000_000L,
            lastPlayedDate = 1_000L,
        )

    private suspend fun row() = assertNotNull(database.getUserData(movieId, userId))

    @Test
    fun `an unplayed mark the server got clears the last played time`() = runBlocking {
        storeMovie(played)

        repository.markAsUnplayed(movieId)

        assertEquals(listOf("played false"), sent)
        assertEquals(played.copy(played = false, lastPlayedDate = null), row())
    }

    @Test
    fun `an unplayed mark during a profile switch clears the date of the user who marked it`() =
        runBlocking {
            // #907: the other user's profile is opened while the unplayed request waits for the
            // server. The date to clear is the marking user's, not the other user's.
            storeMovie(played)
            val other = played.copy(userId = otherUserId, lastPlayedDate = 2_000L)
            database.insertUserData(other)
            whileMarking = { unanswered.userId = otherUserId }

            repository.markAsUnplayed(movieId)

            assertEquals(played.copy(played = false, lastPlayedDate = null), row())
            assertEquals(other, database.getUserData(movieId, otherUserId))
        }

    @Test
    fun `a favourite the server got changes only the favourite`() = runBlocking {
        storeMovie(played)

        repository.markAsFavorite(movieId)
        assertEquals(played.copy(favorite = true), row())

        repository.unmarkAsFavorite(movieId)
        assertEquals(played, row())
        assertEquals(listOf("favorite true", "favorite false"), sent)
    }

    @Test
    fun `a played mark the server got keeps the known time`() = runBlocking {
        storeMovie(played.copy(played = false))

        repository.markAsPlayed(movieId)

        assertEquals(played, row())
    }

    @Test
    fun `a stop near the end marks the item played, without a position`() = runBlocking {
        storeMovie(played.copy(played = false))
        val before = System.currentTimeMillis()

        repository.postPlaybackStop(movieId, 600_000_000L, playedPercentage = 95)

        assertEquals(listOf("stopped 600000000"), sent)
        val row = row()
        assertEquals(
            played.copy(playbackPositionTicks = 0L, lastPlayedDate = row.lastPlayedDate),
            row,
        )
        assertTrue(row.lastPlayedDate!! in before..System.currentTimeMillis())
    }
}
