package dev.jdtech.jellyfin.repository

import android.content.Context
import android.provider.Settings
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.data.files.DownloadedFilesOnDisk
import dev.jdtech.jellyfin.database.RoomDownloadStore
import dev.jdtech.jellyfin.database.RoomServerStore
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.models.PlaybackTarget
import dev.jdtech.jellyfin.models.PlayerEngineId
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.player.DeviceCodecProbe
import dev.jdtech.jellyfin.server.jellyfin.JellyfinDeviceProfiles
import dev.jdtech.jellyfin.server.jellyfin.JellyfinMediaServer
import dev.jdtech.jellyfin.server.jellyfin.toUuid
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.testing.FakeResumeThresholdsStore
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Collections
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.robolectric.RuntimeEnvironment

/**
 * A stand-in Jellyfin server on a [ServerSocket], for tests that run a real [MediaRepositoryImpl]
 * and read what it sent. It records every request and answers each with [answer]: a JSON body with
 * 200, or 204 when [answer] gives `null`. One connection per request (`Connection: close`).
 *
 * #771: the primary constructor answers with any status and body ([respond]), and with [concurrent]
 * each connection on a thread of its own, as the contract tests need: the SDK's server discovery
 * tries https on the same port, which must not hold up the other requests.
 */
internal class StandInServer(
    private val concurrent: Boolean,
    private val respond: (Request) -> Response,
) : Closeable {
    /** A JSON body with 200, or 204 when [answer] gives `null`, one request after the other. */
    constructor(
        answer: (Request) -> String? = { null }
    ) : this(
        concurrent = false,
        respond = { request ->
            val body = answer(request)
            if (body == null) Response(204) else Response(200, body = body.toByteArray())
        },
    )

    /** #771: an answer of any status. No body for an empty [body]. */
    class Response(
        val status: Int,
        val contentType: String = "application/json",
        val body: ByteArray = ByteArray(0),
    )

    /**
     * A request as it reached the server. [target] is the path with its query, as
     * sent. #771: [headers] by their name in lower case.
     */
    data class Request(
        val method: String,
        val target: String,
        val body: String,
        val headers: Map<String, String> = emptyMap(),
    ) {
        val path: String
            get() = target.substringBefore('?')

        /** The target with its query decoded, for matching on query values. */
        val decodedTarget: String
            get() = URLDecoder.decode(target, "UTF-8")

        fun json(): JsonObject = Json.parseToJsonElement(body).jsonObject
    }

    // Loopback only: the repository is the one client, on 127.0.0.1.
    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    val baseUrl: String
        get() = "http://127.0.0.1:${socket.localPort}"

    init {
        // The SDK logs each request through kotlin-logging, which picks SLF4J unless told to use
        // android.util.Log. The tests have no SLF4J; Robolectric has android.util.Log.
        System.setProperty("kotlin-logging-to-android-native", "true")
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val connection = runCatching { socket.accept() }.getOrNull() ?: break
                if (concurrent) {
                    // A connection that is not HTTP (the https attempt) is dropped.
                    thread(isDaemon = true) { runCatching { serve(connection) } }
                } else {
                    serve(connection)
                }
            }
        }
    }

    private fun serve(connection: Socket) {
        connection.use {
            val input = it.getInputStream()
            val requestLine = readLine(input).split(" ")
            var length = 0
            val headers = mutableMapOf<String, String>()
            while (true) {
                val header = readLine(input)
                if (header.isEmpty()) break
                headers[header.substringBefore(':').trim().lowercase()] =
                    header.substringAfter(':').trim()
                if (header.startsWith("Content-Length:", ignoreCase = true)) {
                    length = header.substringAfter(':').trim().toInt()
                }
            }
            val request =
                Request(
                    method = requestLine.getOrElse(0) { "" },
                    target = requestLine.getOrElse(1) { "" },
                    body = String(input.readNBytes(length)),
                    headers = headers,
                )
            requests += request
            val response = respond(request)
            val head = StringBuilder("HTTP/1.1 ${response.status} ${reason(response.status)}\r\n")
            if (response.status != 204) {
                head.append("Content-Type: ${response.contentType}\r\n")
            }
            head.append("Content-Length: ${response.body.size}\r\nConnection: close\r\n\r\n")
            val output = it.getOutputStream()
            output.write(head.toString().toByteArray())
            output.write(response.body)
        }
    }

    private fun reason(status: Int): String =
        when (status) {
            200 -> "OK"
            204 -> "No Content"
            401 -> "Unauthorized"
            404 -> "Not Found"
            else -> "Status"
        }

    /** A header line, without its line end; the body is read by length, so no buffered reader. */
    private fun readLine(input: InputStream): String {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1 || c == '\n'.code) break
            if (c != '\r'.code) line.append(c.toChar())
        }
        return line.toString()
    }

    override fun close() = socket.close()

    companion object {
        val USER_ID: UserId = "00000000-0000-0000-0000-000000000001".toUserId()

        /**
         * CE (#766): the target the player resolves with every player preference at its default:
         * ExoPlayer, Dolby Vision direct play allowed.
         */
        val LOCAL_PLAYER = PlaybackTarget.LocalPlayer(PlayerEngineId.EXOPLAYER, true)

        /** The test application, with the device id the SDK names the device after. */
        fun context(): Context =
            RuntimeEnvironment.getApplication().also {
                // Robolectric has none.
                Settings.Secure.putString(it.contentResolver, Settings.Secure.ANDROID_ID, "test")
            }

        fun preferences(context: Context): AppPreferences =
            AppPreferences(context.getSharedPreferences("test", Context.MODE_PRIVATE))

        /**
         * A database with no downloads, which fails on anything but the question whether there are
         * any: with no current server set, the requests under test read nothing else.
         */
        fun noDownloads(): ServerDatabaseDao =
            fakeProxy(
                ServerDatabaseDao::class.java.classLoader,
                arrayOf(ServerDatabaseDao::class.java),
            ) { _, method, _ ->
                when (method.kotlinName) {
                    "observeHasSources" -> flowOf(false)
                    else -> throw NotImplementedError("ServerDatabaseDao.${method.kotlinName}")
                }
            }
                as ServerDatabaseDao

        /** A recorded server answer under `src/test/resources/`. */
        fun resource(path: String): String =
            checkNotNull(StandInServer::class.java.classLoader?.getResource(path)) { path }
                .readText()
    }
}

/** A real repository that talks to this server as [StandInServer.USER_ID] with token `token`. */
internal fun StandInServer.repository(
    context: Context = StandInServer.context(),
    database: ServerDatabaseDao = StandInServer.noDownloads(),
    preferences: AppPreferences = StandInServer.preferences(context),
    /** #769: what the server says it can do; a Jellyfin server can do everything. */
    capabilities: ServerCapabilities = ServerCapabilities.ALL,
): MediaRepositoryImpl {
    val api =
        JellyfinApi(context).apply {
            api.update(baseUrl = baseUrl, accessToken = "token")
            userId = StandInServer.USER_ID.toUuid()
        }
    val jellyfin = JellyfinMediaServer(api, JellyfinDeviceProfiles(DeviceCodecProbe(context)))
    // #966: the repository is in application; the stores are data's, over [database].
    val downloads = RoomDownloadStore(database)
    val servers = RoomServerStore(database)
    return MediaRepositoryImpl(
        server =
            object : MediaServer by jellyfin {
                override val capabilities = capabilities
            },
        storedItems = downloads,
        storedLists = downloads,
        storedSources = downloads,
        storedSegments = downloads,
        storedUserData = downloads,
        trackPreferences = servers,
        storedUsers = servers,
        downloadedFiles = DownloadedFilesOnDisk(context),
        currentServer = { preferences.currentServerId() },
        nextUpMaxDays = { preferences.getValue(preferences.homeNextUpMaxDays) },
        resumeThresholds = FakeResumeThresholdsStore(),
    )
}
