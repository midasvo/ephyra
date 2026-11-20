package dev.jdtech.jellyfin.repository

import android.app.Application
import android.content.Context
import android.provider.Settings
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.player.DeviceProfileBuilder
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.lang.reflect.Proxy
import java.net.ServerSocket
import java.util.Collections
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#728): the playback reports name the play session of the stream the player loaded, not of the
 * last playback info answer. A quality switch asks for new streams for the whole queue and swaps
 * them in only when every request succeeded; one that fails keeps the old streams, so the answer it
 * got for the playing item must not change the reports. Before, a failed switch to Original made
 * the reports of a playing transcode say DirectPlay, and the server showed that.
 *
 * The server here answers each playback info request with the next answer of [answers] and records
 * the body of each start report.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlaySessionOfPlayingStreamTest {
    private val itemId = UUID.fromString("00000000-0000-0000-0000-000000000040")
    private val server = ServerSocket(0)
    private val answers = Collections.synchronizedList(mutableListOf<String>())
    private val starts = Collections.synchronizedList(mutableListOf<JsonObject>())

    init {
        // As in HomeRowFieldsTest: the SDK logs through kotlin-logging, which needs this here.
        System.setProperty("kotlin-logging-to-android-native", "true")
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    val input = it.getInputStream()
                    val path = readLine(input).split(" ").getOrElse(1) { "" }
                    var length = 0
                    while (true) {
                        val header = readLine(input)
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", ignoreCase = true)) {
                            length = header.substringAfter(':').trim().toInt()
                        }
                    }
                    val body = String(input.readNBytes(length))
                    val answer =
                        when {
                            path.contains("/PlaybackInfo") -> answers.removeAt(0)
                            path.startsWith("/Sessions/Playing") -> {
                                if (path.substringBefore('?') == "/Sessions/Playing") {
                                    starts += Json.parseToJsonElement(body).jsonObject
                                }
                                null
                            }
                            else -> null
                        }
                    val response =
                        if (answer == null) "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n"
                        else
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${answer.toByteArray().size}\r\n" +
                                "Connection: close\r\n\r\n$answer"
                    it.getOutputStream().write(response.toByteArray())
                }
            }
        }
    }

    /** A header line, without its line end; the body is read by length, so no buffered reader. */
    private fun readLine(input: java.io.InputStream): String {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1 || c == '\n'.code) break
            if (c != '\r'.code) line.append(c.toChar())
        }
        return line.toString()
    }

    @After
    fun stop() {
        server.close()
    }

    private val context: Context by lazy {
        RuntimeEnvironment.getApplication().also {
            // The SDK names the device after it; Robolectric has none.
            Settings.Secure.putString(it.contentResolver, Settings.Secure.ANDROID_ID, "test")
        }
    }

    // No current server is set, so the playback info request reads no downloads.
    private val database =
        Proxy.newProxyInstance(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "observeHasSources" -> flowOf(false)
                else -> throw NotImplementedError("ServerDatabaseDao.${method.name}")
            }
        } as ServerDatabaseDao

    private val repository by lazy {
        val api =
            JellyfinApi(context).apply {
                api.update(baseUrl = "http://127.0.0.1:${server.localPort}", accessToken = "token")
                userId = UUID.fromString("00000000-0000-0000-0000-000000000001")
            }
        JellyfinRepositoryImpl(
            context,
            api,
            database,
            AppPreferences(context.getSharedPreferences("test", Context.MODE_PRIVATE)),
            DeviceProfileBuilder(context),
        )
    }

    /** The playback info answer: a transcode with [session], or the file itself without one. */
    private fun answer(session: String?): String {
        val transcoding =
            if (session == null) """"TranscodingSubProtocol":"http","""
            else
                """"TranscodingUrl":"/videos/$itemId/master.m3u8?PlaySessionId=$session",""" +
                    """"TranscodingSubProtocol":"hls","""
        return """{"MediaSources":[{"Id":"source","Protocol":"Http","Path":"http://file",""" +
            """"Type":"Default",$transcoding"IsRemote":false,"ReadAtNativeFramerate":false,""" +
            """"IgnoreDts":false,"IgnoreIndex":false,"GenPtsInput":false,""" +
            """"SupportsTranscoding":true,"SupportsDirectStream":false,""" +
            """"SupportsDirectPlay":${session == null},"IsInfiniteStream":false,""" +
            """"UseMostCompatibleTranscodingProfile":false,"RequiresOpening":false,""" +
            """"RequiresClosing":false,"RequiresLooping":false,"SupportsProbing":false,""" +
            """"MediaStreams":[],"HasSegments":false}],""" +
            """"PlaySessionId":"${session ?: "direct"}"}"""
    }

    /** Asks for the item's stream, as a switch does, and loads it in the player if [load]. */
    private suspend fun request(session: String?, load: Boolean) {
        answers += answer(session)
        val source = repository.getPlaybackSources(itemId).single()
        assertEquals(session, source.playSessionId, message = "the source carries its session")
        if (load) repository.setPlaySession(itemId, source.playSessionId, source.transcoded)
    }

    private fun startReport(): Pair<String?, String?> {
        val start = starts.last()
        return start["PlayMethod"]?.jsonPrimitive?.content to
            start["PlaySessionId"]?.jsonPrimitive?.takeIf { it.isString }?.content
    }

    @Test
    fun `a switch to Original that fails keeps reporting the transcode that plays`() = runBlocking {
        request(session = "playing", load = true)
        request(session = null, load = false)

        repository.postPlaybackStart(itemId, transcoded = true)

        assertEquals(
            "Transcode" to "playing",
            startReport(),
            message = "the stream that plays is still the transcode",
        )
    }

    @Test
    fun `a switch to another transcode that fails keeps the session that plays`() = runBlocking {
        request(session = "playing", load = true)
        request(session = "never-played", load = false)

        repository.postPlaybackStart(itemId, transcoded = true)

        assertEquals("Transcode" to "playing", startReport(), message = "no job runs for the other")
    }

    @Test
    fun `the stream the player loads names its session`() = runBlocking {
        request(session = "first", load = true)
        request(session = "second", load = true)

        repository.postPlaybackStart(itemId, transcoded = true)

        assertEquals("Transcode" to "second", startReport(), message = "the new stream plays")
    }

    @Test
    fun `a stream that plays directly names no session`() = runBlocking {
        request(session = "first", load = true)
        request(session = null, load = true)

        repository.postPlaybackStart(itemId, transcoded = false)

        val (method, session) = startReport()
        assertEquals("DirectPlay", method, message = "the file plays")
        assertNull(session, message = "a direct stream has no transcode to name")
    }
}
