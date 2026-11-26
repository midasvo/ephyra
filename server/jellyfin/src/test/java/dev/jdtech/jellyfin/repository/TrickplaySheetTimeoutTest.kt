package dev.jdtech.jellyfin.repository

import android.app.Application
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.server.jellyfin.JellyfinDeviceProfiles
import dev.jdtech.jellyfin.server.jellyfin.JellyfinMediaServer
import dev.jdtech.jellyfin.server.jellyfin.toUuid
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CE (#860): a trickplay sheet has a longer timeout than the other requests. Here the socket
 * timeout is 300 ms and a sheet's 3 s (10 s and 30 s in the app), and the server takes 1 s to
 * answer anything: the sheet comes, a request of another kind runs out of time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TrickplaySheetTimeoutTest {
    private val itemId = "8f086228-6a92-020d-0cb7-35d734765afe".toItemId()
    private val sheet = byteArrayOf(1, 2, 3)
    private val server =
        StandInServer(concurrent = true) {
            Thread.sleep(ANSWER_DELAY_MS)
            StandInServer.Response(200, contentType = "image/jpeg", body = sheet)
        }
    private val context = StandInServer.context()
    private val jellyfin by lazy {
        val api =
            JellyfinApi(
                    context,
                    socketTimeout = SOCKET_TIMEOUT_MS,
                    trickplaySheetTimeout = SHEET_TIMEOUT_MS,
                )
                .apply {
                    api.update(baseUrl = server.baseUrl, accessToken = "token")
                    userId = StandInServer.USER_ID.toUuid()
                }
        JellyfinMediaServer(api, JellyfinDeviceProfiles())
    }

    @After
    fun stop() {
        server.close()
    }

    @Test
    fun `a slow sheet still comes`() = runBlocking {
        val tile = jellyfin.playback.trickplayTile(itemId, 320, 0, mediaSourceId = null)

        assertContentEquals(sheet, tile)
    }

    @Test
    fun `another request as slow runs out of time`() = runBlocking {
        assertFailsWith<ServerException> { jellyfin.playback.segments(itemId) }
        Unit
    }

    private companion object {
        const val SOCKET_TIMEOUT_MS = 300L
        const val SHEET_TIMEOUT_MS = 3_000L
        const val ANSWER_DELAY_MS = 1_000L
    }
}
