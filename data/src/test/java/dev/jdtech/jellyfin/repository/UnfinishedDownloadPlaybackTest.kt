package dev.jdtech.jellyfin.repository

import android.app.Application
import android.content.Context
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.ItemSourceEntity
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.PlaybackTarget
import dev.jdtech.jellyfin.models.PlayerEngineId
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toMediaSourceId
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.testing.FakeResumeThresholdsStore
import dev.jdtech.jellyfin.testing.asItemId
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#454): a download that has not finished (its file still ends in `.download`) is not a
 * playback source. The server here always fails: the API client has no address.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UnfinishedDownloadPlaybackTest {
    private val userId = "00000000-0000-0000-0000-000000000001".toUserId()
    private val episodeId = "33333333-3333-3333-3333-333333333333".toItemId()

    private val partial =
        ItemSourceEntity(
            id = "source".toMediaSourceId(),
            itemId = episodeId,
            name = "Original",
            type = ItemSourceType.LOCAL,
            path = "/storage/downloads/Show/S01/S01E01 - Episode.ts.download",
            downloadId = 1L,
        )

    private val database =
        fakeProxy(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, args ->
            // A value class parameter reaches the proxy as its String (see testing/ProxyCalls.kt).
            val id = (args?.firstOrNull() as? String)?.asItemId()
            when (method.kotlinName) {
                "observeHasSources" -> flowOf(true)
                // The queries' SQL, on the one row this item has.
                "getSourcesOfServer" -> listOf(partial).filter { it.itemId == id }
                "getCompletedSources" ->
                    listOf(partial).filter { it.itemId == id && !it.path.endsWith(".download") }
                "getMediaStreamsBySourceId" -> emptyList<Any>()
                else -> throw NotImplementedError("ServerDatabaseDao.${method.kotlinName}")
            }
        }
            as ServerDatabaseDao

    private val context: Context by lazy { RuntimeEnvironment.getApplication() }

    @Test
    fun `online, an unfinished download is not played and the server is asked instead`() =
        runBlocking {
            val repository =
                roomMediaRepository(
                    context,
                    UnansweredServer(userId),
                    database,
                    currentServer = { "server".toServerId() },
                    nextUpMaxDays = { 0 },
                    resumeThresholds = FakeResumeThresholdsStore(),
                )

            val result = runCatching {
                repository.getPlaybackSources(episodeId, LOCAL_PLAYER, null)
            }

            // Asking the server is what fails here; the partial file must not be the answer.
            assertTrue(result.isFailure, message = "Got ${result.getOrNull()?.map { it.path }}")
        }

    private companion object {
        // CE (#766): the local player with every player preference at its default.
        val LOCAL_PLAYER = PlaybackTarget.LocalPlayer(PlayerEngineId.EXOPLAYER, true)
    }
}
