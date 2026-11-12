package dev.jdtech.jellyfin.utils.download

import dev.jdtech.jellyfin.utils.NetworkConnectivity
import java.io.File
import java.nio.file.Files
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CE (#383): a download the server answers with an HTTP error fails at once with that status. It
 * never passes through PAUSED, which the Downloads row shows as "Waiting for network".
 */
class MediaDownloadEngineHttpErrorTest {
    private val dir: File = Files.createTempDirectory("engine-http-error").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `a 404 fails at once with its status and never waits for the network`() {
        val seen = run(code = 404)

        assertFalse("PAUSED reads as Waiting for network: $seen", DownloadStatus.PAUSED in seen)
        assertEquals(DownloadStatus.FAILED, seen.last())
        assertEquals(404, engine.snapshot(ID)?.httpStatus)
    }

    @Test
    fun `a 503 fails at once with its status too`() {
        val seen = run(code = 503)

        assertFalse(DownloadStatus.PAUSED in seen)
        assertEquals(503, engine.snapshot(ID)?.httpStatus)
    }

    @Test
    fun `4xx answers are permanent except 408 and 429, 5xx answers are not`() {
        listOf(400, 401, 403, 404, 410).forEach { assertTrue("$it", isPermanentHttpFailure(it)) }
        listOf(408, 429, 500, 502, 503, 504).forEach {
            assertFalse("$it", isPermanentHttpFailure(it))
        }
    }

    private lateinit var engine: MediaDownloadEngine

    /** Starts a download the server answers with [code]; returns every status seen until FAILED. */
    private fun run(code: Int): List<Int> {
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("Error")
                        .body("".toResponseBody())
                        .build()
                }
                .build()
        engine = MediaDownloadEngine(client, Online)
        engine.start(
            MediaDownloadEngine.Request(
                id = ID,
                url = "http://server.test/videos/item/stream",
                destFile = File(dir, "item.mkv.download"),
                allowMetered = true,
                allowRoaming = true,
            )
        )
        val seen = mutableListOf<Int>()
        // Under the old code a server error sat in PAUSED for 1.5 s before FAILED.
        val deadline = System.currentTimeMillis() + 1_000L
        while (System.currentTimeMillis() < deadline) {
            val status = engine.snapshot(ID)?.status ?: continue
            if (seen.lastOrNull() != status) seen.add(status)
            if (status == DownloadStatus.FAILED) return seen
            Thread.sleep(5)
        }
        return seen
    }

    private object Online : NetworkConnectivity {
        override fun isOnline() = true

        override fun isMetered() = false

        override fun isRoaming() = false
    }

    private companion object {
        const val ID = 1L
    }
}
