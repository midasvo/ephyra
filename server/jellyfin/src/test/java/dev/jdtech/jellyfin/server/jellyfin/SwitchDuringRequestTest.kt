package dev.jdtech.jellyfin.server.jellyfin

import android.app.Application
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.domain.ports.ActiveServer
import dev.jdtech.jellyfin.domain.ports.ActiveServerHolder
import dev.jdtech.jellyfin.domain.ports.ItemExtrasPort
import dev.jdtech.jellyfin.models.ServerType
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.player.DeviceCodecProbe
import dev.jdtech.jellyfin.repository.StandInServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #899: the Jellyfin client while the app switches to another server during a call. The theme song
 * is two steps: the request for the song, then its stream URL. The switch comes between them.
 *
 * The ports `CurrentMediaServer` takes for the value on the holder ([JellyfinMediaServer.at]) keep
 * that value for the whole call: the song is asked of server A with A's token and the URL is A's.
 * Ports without a value read the holder at each step, as the client did before, and end on B.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SwitchDuringRequestTest {
    /** Let go once the switch to B is on. */
    private val switched = CountDownLatch(1)

    /** Counted down when the song request reached A. */
    private val asked = CountDownLatch(1)

    private val serverA =
        StandInServer(concurrent = true) { request ->
            if (request.path.endsWith("/ThemeSongs")) {
                asked.countDown()
                // Without the switch the test would not test it: no song then.
                if (switched.await(5, TimeUnit.SECONDS)) {
                    StandInServer.Response(200, body = THEME_SONGS.toByteArray())
                } else {
                    StandInServer.Response(500)
                }
            } else {
                StandInServer.Response(404)
            }
        }
    private val serverB = StandInServer(concurrent = true) { StandInServer.Response(404) }

    private val active = ActiveServerHolder()
    private val context = StandInServer.context()
    private val api = JellyfinApi(context, activeServer = active)
    private val jellyfin =
        JellyfinMediaServer(api, JellyfinDeviceProfiles(DeviceCodecProbe(context)))

    @After
    fun stop() {
        serverA.close()
        serverB.close()
    }

    private fun on(server: StandInServer, token: String, id: String, user: String = USER) =
        ActiveServer(
            type = ServerType.JELLYFIN.value,
            serverId = id.toServerId(),
            baseUrl = server.baseUrl,
            accessToken = token,
            userId = user.toUserId(),
        )

    /** Asks [extras] for the theme song while the app switches from A to B. */
    private fun themeSongAcrossASwitch(extras: () -> ItemExtrasPort): String? = runBlocking {
        active.switchTo(on(serverA, "token-a", "a"))
        val port = extras()
        val song = async(Dispatchers.IO) { port.themeSongUrl(SERIES.toItemId()) }

        assertTrue(asked.await(5, TimeUnit.SECONDS), "The song was not asked of A")
        active.switchTo(on(serverB, "token-b", "b"))
        switched.countDown()
        withTimeout(10_000) { song.await() }
    }

    @Test
    fun `a call keeps the server, the token and the address it started with`() {
        val url = assertNotNull(themeSongAcrossASwitch { jellyfin.at(active.value).itemExtras })

        assertTrue(url.startsWith(serverA.baseUrl), url)
        val request = serverA.requests.single()
        assertTrue(request.headers.getValue("authorization").contains("token-a"))
        assertTrue(request.target.contains("userId=$USER"), request.target)
        assertEquals(emptyList(), serverB.requests.toList())
    }

    @Test
    fun `ports taken before a switch to another user ask with the user and token they were taken with`() {
        active.switchTo(on(serverA, "token-a", "a"))
        val extras = jellyfin.at(active.value).itemExtras
        active.switchTo(on(serverB, "token-b", "b", user = OTHER_USER))
        switched.countDown()

        val url = assertNotNull(runBlocking { extras.themeSongUrl(SERIES.toItemId()) })

        assertTrue(url.startsWith(serverA.baseUrl), url)
        val request = serverA.requests.single()
        assertTrue(request.headers.getValue("authorization").contains("token-a"))
        assertTrue(request.target.contains("userId=$USER"), request.target)
        assertEquals(emptyList(), serverB.requests.toList())
    }

    @Test
    fun `without a value the second step follows the switch`() {
        // What the check above rules out: the song from A, its URL on B.
        val url = assertNotNull(themeSongAcrossASwitch { jellyfin.itemExtras })

        assertTrue(url.startsWith(serverB.baseUrl), url)
    }

    private companion object {
        const val USER = "00000000-0000-0000-0000-000000000001"
        const val OTHER_USER = "00000000-0000-0000-0000-000000000002"
        const val SERIES = "00000000-0000-0000-0000-000000000201"

        val THEME_SONGS =
            """
            {
              "Items": [
                {
                  "Name": "Theme",
                  "ServerId": "00000000000000000000000000000002",
                  "Id": "00000000000000000000000000000602",
                  "Type": "Audio",
                  "IsFolder": false,
                  "MediaType": "Audio",
                  "ImageTags": {},
                  "BackdropImageTags": [],
                  "LocationType": "FileSystem"
                }
              ],
              "TotalRecordCount": 1,
              "StartIndex": 0,
              "OwnerId": "00000000000000000000000000000201"
            }
            """
                .trimIndent()
    }
}
