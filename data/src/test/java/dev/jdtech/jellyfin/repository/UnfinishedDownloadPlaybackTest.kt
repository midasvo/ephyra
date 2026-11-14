package dev.jdtech.jellyfin.repository

import android.app.Application
import android.content.Context
import android.provider.Settings
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.FindroidSourceDto
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.player.DeviceProfileBuilder
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val episodeId = UUID.fromString("33333333-3333-3333-3333-333333333333")

    private val partial =
        FindroidSourceDto(
            id = "source",
            itemId = episodeId,
            name = "Original",
            type = FindroidSourceType.LOCAL,
            path = "/storage/downloads/Show/S01/S01E01 - Episode.ts.download",
            downloadId = 1L,
        )

    private val database =
        Proxy.newProxyInstance(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, args ->
            val id = args?.firstOrNull() as? UUID
            when (method.name) {
                "observeHasSources" -> flowOf(true)
                // The queries' SQL, on the one row this item has.
                "getSources" -> listOf(partial).filter { it.itemId == id }
                "getCompletedSources" ->
                    listOf(partial).filter { it.itemId == id && !it.path.endsWith(".download") }
                "getMediaStreamsBySourceId" -> emptyList<Any>()
                else -> throw NotImplementedError("ServerDatabaseDao.${method.name}")
            }
        } as ServerDatabaseDao

    private val context: Context by lazy {
        RuntimeEnvironment.getApplication().also {
            // The SDK names the device after it; Robolectric has none.
            Settings.Secure.putString(it.contentResolver, Settings.Secure.ANDROID_ID, "test")
        }
    }

    private fun api() =
        JellyfinApi(context).apply { userId = this@UnfinishedDownloadPlaybackTest.userId }

    private fun preferences() =
        AppPreferences(context.getSharedPreferences("test", Context.MODE_PRIVATE))

    @Test
    fun `online, an unfinished download is not played and the server is asked instead`() =
        runBlocking {
            val repository =
                JellyfinRepositoryImpl(
                    context,
                    api(),
                    database,
                    preferences(),
                    DeviceProfileBuilder(context),
                )

            val result = runCatching { repository.getPlaybackSources(episodeId, null) }

            // Asking the server is what fails here; the partial file must not be the answer.
            assertTrue("Got ${result.getOrNull()?.map { it.path }}", result.isFailure)
        }

    @Test
    fun `offline, an unfinished download is not a playback source`() = runBlocking {
        val repository = JellyfinRepositoryOfflineImpl(context, api(), database, preferences())

        assertEquals(emptyList<Any>(), repository.getPlaybackSources(episodeId, null))
    }
}
