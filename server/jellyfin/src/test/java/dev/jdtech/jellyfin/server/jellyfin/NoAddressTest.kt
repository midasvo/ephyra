package dev.jdtech.jellyfin.server.jellyfin

import android.app.Application
import android.provider.Settings
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.domain.ports.PlaybackReport
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.models.isUnanswered
import java.util.UUID
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #758: a client without an address fails every request with [ServerException.Unreachable], which
 * counts as no answer. The repository tests in `data` stand in for such a client with
 * `UnansweredServer`, which fails the same way; before the adapter left `data`, they ran this one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NoAddressTest {
    private val context =
        RuntimeEnvironment.getApplication().also {
            // The SDK names the device after it; Robolectric has none.
            Settings.Secure.putString(it.contentResolver, Settings.Secure.ANDROID_ID, "test")
        }
    private val api = JellyfinApi(context).apply { userId = UUID.randomUUID() }
    private val itemId = UUID.randomUUID().toItemId()

    @Test
    fun `every request fails as Unreachable`() = runBlocking {
        val catalog = JellyfinCatalog(api)
        val userData = JellyfinUserData(api)
        val playback = JellyfinPlayback(api, JellyfinDeviceProfiles())
        val session = JellyfinServerSession(api)
        for ((name, request) in
            listOf<Pair<String, suspend () -> Any?>>(
                "favorites" to { catalog.favorites() },
                "search" to { catalog.search("bluey", null) },
                "libraries" to { catalog.libraries() },
                "movie" to { catalog.movie(itemId) },
                "item" to { catalog.item(itemId) },
                "setPlayed" to { userData.setPlayed(itemId, true) },
                "reportStopped" to
                    {
                        playback.reportStopped(PlaybackReport(itemId, 0L, false, false, null))
                    },
                "currentUserImage" to { session.currentUserImage() },
            )) {
            val failure = runCatching { request() }.exceptionOrNull()
            assertIs<ServerException.Unreachable>(failure, name)
            assertTrue(failure.isUnanswered(), name)
        }
    }
}
