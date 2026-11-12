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
import java.net.URLDecoder
import java.util.Collections
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#410): the Home rows other than Suggestions ask for the overview. Jellyfin leaves `Overview`
 * out of Resume, Next Up and Latest unless `fields` names it, and the TV hero shows it for every
 * card. The server here only records each request's query and answers with an empty list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeRowFieldsTest {
    private val server = ServerSocket(0)
    private val queries = Collections.synchronizedList(mutableListOf<String>())

    init {
        // The SDK logs each request through kotlin-logging, which picks SLF4J unless told to use
        // android.util.Log. The tests have no SLF4J; Robolectric has android.util.Log.
        System.setProperty("kotlin-logging-to-android-native", "true")
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    val requestLine = reader.readLine().orEmpty()
                    while (reader.readLine().orEmpty().isNotEmpty()) Unit
                    queries += URLDecoder.decode(requestLine, "UTF-8")
                    val path = requestLine.split(" ").getOrElse(1) { "" }
                    // Latest answers with a bare list, the others with a result page.
                    val body =
                        if (path.startsWith("/Items/Latest")) "[]"
                        else """{"Items":[],"TotalRecordCount":0,"StartIndex":0}"""
                    it.getOutputStream()
                        .write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                    "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n" +
                                    body)
                                .toByteArray()
                        )
                }
            }
        }
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

    private fun repository(): JellyfinRepositoryImpl {
        val api =
            JellyfinApi(context).apply {
                api.update(baseUrl = "http://127.0.0.1:${server.localPort}", accessToken = "token")
                userId = UUID.fromString("00000000-0000-0000-0000-000000000001")
            }
        return JellyfinRepositoryImpl(
            context,
            api,
            database,
            AppPreferences(context.getSharedPreferences("test", Context.MODE_PRIVATE)),
            DeviceProfileBuilder(context),
        )
    }

    private fun assertAsksForOverview(endpoint: String) {
        val request = queries.single { it.contains(endpoint) }
        assertTrue(request, Regex("""[?&]fields=[^& ]*\bOverview\b""").containsMatchIn(request))
    }

    @Test
    fun `Continue Watching asks for the overview`() = runBlocking {
        repository().getResumeItems()
        assertAsksForOverview("/UserItems/Resume")
    }

    @Test
    fun `Next Up asks for the overview`() = runBlocking {
        repository().getNextUp()
        assertAsksForOverview("/Shows/NextUp")
    }

    @Test
    fun `Latest asks for the overview`() = runBlocking {
        repository().getLatestMedia(UUID.fromString("22222222-2222-2222-2222-222222222222"))
        assertAsksForOverview("/Items/Latest")
    }
}
