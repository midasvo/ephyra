package dev.jdtech.jellyfin.repository

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.jdtech.jellyfin.database.ServerDatabase
import dev.jdtech.jellyfin.models.SeriesTrackPreference
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.testing.FakeResumeThresholdsStore
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#580, #750): the player saves and reads the series' track choice through the repository,
 * which keeps it per user.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SeriesTrackPreferenceTest {
    private val userId = "00000000-0000-0000-0000-000000000001".toUserId()
    private val otherUserId = "00000000-0000-0000-0000-000000000002".toUserId()
    private val seriesId = "00000000-0000-0000-0000-000000000020".toItemId()
    private val otherSeriesId = "00000000-0000-0000-0000-000000000021".toItemId()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val room =
        Room.inMemoryDatabaseBuilder(context, ServerDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val database = room.getServerDatabaseDao()

    private val server = UnansweredServer(userId)

    private val repository: MediaRepositoryImpl by lazy {
        roomMediaRepository(
            context,
            server,
            database,
            currentServer = { null },
            nextUpMaxDays = { 0 },
            resumeThresholds = FakeResumeThresholdsStore(),
        )
    }

    @After fun close() = room.close()

    private val audio =
        SeriesTrackPreference(
            userId = userId,
            seriesId = seriesId,
            trackType = TRACK_TYPE_AUDIO,
            disabled = false,
            language = "jpn",
            title = "Japanese",
            positionInLanguage = 0,
        )

    @Test
    fun `a saved choice is read back for the same user and series`() = runBlocking {
        repository.saveSeriesTrackPreference(audio)

        assertEquals(listOf(audio), repository.getSeriesTrackPreferences(seriesId))
        assertTrue(repository.getSeriesTrackPreferences(otherSeriesId).isEmpty())
    }

    @Test
    fun `a saved choice is not read for another user`() = runBlocking {
        repository.saveSeriesTrackPreference(audio)

        server.userId = otherUserId
        assertTrue(repository.getSeriesTrackPreferences(seriesId).isEmpty())
    }

    @Test
    fun `a new choice replaces the one before for the same track type`() = runBlocking {
        repository.saveSeriesTrackPreference(audio)
        val subtitlesOff = SeriesTrackPreference(userId, seriesId, TRACK_TYPE_TEXT, disabled = true)
        repository.saveSeriesTrackPreference(subtitlesOff)
        val english = audio.copy(language = "eng", title = "English")
        repository.saveSeriesTrackPreference(english)

        assertEquals(
            setOf(english, subtitlesOff),
            repository.getSeriesTrackPreferences(seriesId).toSet(),
        )
    }

    @Test
    fun `the choice is saved for the current user whatever the preference says`() = runBlocking {
        repository.saveSeriesTrackPreference(audio.copy(userId = otherUserId))

        assertEquals(listOf(audio), repository.getSeriesTrackPreferences(seriesId))
        assertTrue(database.getSeriesTrackPreferences(otherUserId, seriesId).isEmpty())
    }

    private companion object {
        // media3's C.TRACK_TYPE_AUDIO and C.TRACK_TYPE_TEXT; data does not depend on media3.
        const val TRACK_TYPE_AUDIO = 1
        const val TRACK_TYPE_TEXT = 3
    }
}
