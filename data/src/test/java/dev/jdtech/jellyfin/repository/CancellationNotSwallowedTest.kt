package dev.jdtech.jellyfin.repository

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.jdtech.jellyfin.database.ServerDatabase
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.testing.FakeResumeThresholdsStore
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#821): the repository's calls that turn a failed server request into a fallback value (null,
 * an empty list, an empty URL) must not turn a cancellation into one: a cancelled caller is
 * cancelled, while a real failure still gives the fallback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CancellationNotSwallowedTest {
    private val userId = "00000000-0000-0000-0000-000000000001".toUserId()
    private val itemId = "00000000-0000-0000-0000-000000000030".toItemId()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val room =
        Room.inMemoryDatabaseBuilder(context, ServerDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .build()

    private val server = UnansweredServer(userId)

    private val repository: MediaRepositoryImpl by lazy {
        roomMediaRepository(
            context,
            server,
            room.getServerDatabaseDao(),
            currentServer = { null },
            nextUpMaxDays = { 0 },
            resumeThresholds = FakeResumeThresholdsStore(),
        )
    }

    @After fun close() = room.close()

    private fun cancelled() {
        server.failure = { CancellationException("cancelled") }
    }

    private fun failing() {
        server.failure = { ServerException.Unreachable() }
    }

    @Test
    fun `a cancelled theme song request is cancelled`() =
        runBlocking<Unit> {
            cancelled()
            assertFailsWith<CancellationException> { repository.getThemeSongUrl(itemId) }
        }

    @Test
    fun `a failed theme song request gives null`() =
        runBlocking<Unit> {
            failing()
            assertNull(repository.getThemeSongUrl(itemId))
        }

    @Test
    fun `a cancelled segments request is cancelled`() =
        runBlocking<Unit> {
            cancelled()
            assertFailsWith<CancellationException> { repository.getServerSegments(itemId) }
        }

    @Test
    fun `a failed segments request gives none`() =
        runBlocking<Unit> {
            failing()
            assertTrue(repository.getServerSegments(itemId).isEmpty())
        }

    @Test
    fun `a cancelled trickplay request is cancelled`() =
        runBlocking<Unit> {
            cancelled()
            assertFailsWith<CancellationException> { repository.getTrickplayData(itemId, 320, 0) }
        }

    @Test
    fun `a failed trickplay request gives null`() =
        runBlocking<Unit> {
            failing()
            assertNull(repository.getTrickplayData(itemId, 320, 0))
        }
}
