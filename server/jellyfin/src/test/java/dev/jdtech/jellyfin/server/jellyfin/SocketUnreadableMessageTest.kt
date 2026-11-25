package dev.jdtech.jellyfin.server.jellyfin

import android.app.Application
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.domain.ports.ActiveServer
import dev.jdtech.jellyfin.domain.ports.ActiveServerHolder
import dev.jdtech.jellyfin.models.ServerType
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.repository.StandInServer
import java.util.Collections
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.util.ApiSerializer
import org.jellyfin.sdk.api.sockets.SocketConnection
import org.jellyfin.sdk.api.sockets.SocketConnectionFactory
import org.jellyfin.sdk.api.sockets.SocketConnectionState
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.PlaystateMessage
import org.jellyfin.sdk.model.api.SessionsMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1011: a socket message the Jellyfin SDK's `ApiSerializer` cannot decode (unknown `MessageType`
 * or malformed field such as a non-UUID `ItemId`) must not close the socket or stop later messages.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SocketUnreadableMessageTest {
    private val sockets = FakeSockets()
    private val active =
        ActiveServerHolder().apply {
            switchTo(
                ActiveServer(
                    type = ServerType.JELLYFIN.value,
                    serverId = "a".toServerId(),
                    baseUrl = "http://a.test",
                    accessToken = "token-a",
                    userId = "00000000-0000-0000-0000-000000000001".toUserId(),
                )
            )
        }
    private val api =
        JellyfinApi(StandInServer.context(), activeServer = active, socketConnections = sockets)
    private val session = JellyfinServerSession(api)

    @Test
    fun `a valid message arrives`() = runBlocking {
        ApiSerializer.decodeSocketMessage(VALID_PLAYSTATE)
        val keeper = launch(Dispatchers.IO) { session.keepAlive().collect {} }
        try {
            sockets.awaitConnects(1)
            val conn = sockets.made.single()

            val received =
                withTimeout(2_000) {
                    val waiter = launch { api.api.webSocket.subscribe<PlaystateMessage>().first() }
                    delay(50)
                    conn.receive(VALID_PLAYSTATE)
                    waiter.join()
                    true
                }

            assertEquals(true, received)
            assertEquals(0, conn.disconnects)
        } finally {
            keeper.cancel()
        }
    }

    @Test
    fun `an unknown MessageType does not disconnect the socket and later messages arrive`() =
        runBlocking {
            val keeper = launch(Dispatchers.IO) { session.keepAlive().collect {} }
            try {
                sockets.awaitConnects(1)
                val conn = sockets.made.single()
                conn.receive(
                    """{"MessageType":"SomethingNewer","MessageId":"00000000000000000000000000000001","Data":{}}"""
                )
                delay(50)
                assertEquals(0, conn.disconnects, "SDK disconnected on unknown MessageType")

                val received =
                    withTimeout(2_000) {
                        val waiter = launch {
                            api.api.webSocket.subscribe<PlaystateMessage>().first()
                        }
                        delay(50)
                        conn.receive(VALID_PLAYSTATE)
                        waiter.join()
                        true
                    }

                assertEquals(true, received)
                assertEquals(0, conn.disconnects)
            } finally {
                keeper.cancel()
            }
        }

    @Test
    fun `a UserDataChanged with a non-UUID ItemId does not disconnect and later messages arrive`() =
        runBlocking {
            val keeper = launch(Dispatchers.IO) { session.keepAlive().collect {} }
            try {
                sockets.awaitConnects(1)
                val conn = sockets.made.single()
                conn.receive(
                    """{"MessageType":"UserDataChanged","MessageId":"00000000000000000000000000000002","Data":{"UserId":"00000000-0000-0000-0000-000000000001","UserDataList":[{"ItemId":"not-a-uuid"}]}}"""
                )
                delay(50)
                assertEquals(0, conn.disconnects, "SDK disconnected on non-UUID ItemId")

                val received =
                    withTimeout(2_000) {
                        val waiter = launch {
                            api.api.webSocket.subscribe<PlaystateMessage>().first()
                        }
                        delay(50)
                        conn.receive(VALID_PLAYSTATE)
                        waiter.join()
                        true
                    }

                assertEquals(true, received)
                assertEquals(0, conn.disconnects)
            } finally {
                keeper.cancel()
            }
        }

    @Test
    fun `identical valid messages separated by an unreadable message both arrive`() = runBlocking {
        val keeper = launch(Dispatchers.IO) { session.keepAlive().collect {} }
        try {
            sockets.awaitConnects(1)
            val conn = sockets.made.single()
            val received = Collections.synchronizedList(mutableListOf<PlaystateMessage>())
            val collector = launch {
                api.api.webSocket.subscribe<PlaystateMessage>().collect { received += it }
            }
            try {
                delay(50)
                conn.receive(VALID_PLAYSTATE)
                withTimeout(2_000) { while (received.size < 1) delay(10) }

                conn.receive(
                    """{"MessageType":"SomethingNewer","MessageId":"00000000000000000000000000000004","Data":{}}"""
                )
                delay(50)

                conn.receive(VALID_PLAYSTATE)
                withTimeout(2_000) { while (received.size < 2) delay(10) }

                assertEquals(2, received.size)
                assertEquals(0, conn.disconnects)
            } finally {
                collector.cancel()
            }
        } finally {
            keeper.cancel()
        }
    }

    @Test
    fun `a start-required subscription after a rejected message still sends its start request`() =
        runBlocking {
            val keeper = launch(Dispatchers.IO) { session.keepAlive().collect {} }
            try {
                sockets.awaitConnects(1)
                val conn = sockets.made.single()
                conn.receive(VALID_PLAYSTATE)
                delay(50)
                conn.receive(
                    """{"MessageType":"SomethingNewer","MessageId":"00000000000000000000000000000005","Data":{}}"""
                )
                delay(50)

                val sessionsCollector = launch {
                    api.api.webSocket.subscribe<SessionsMessage>().collect {}
                }
                try {
                    withTimeout(2_000) {
                        while (conn.sent.none { it.contains("SessionsStart") }) delay(10)
                    }
                    assertEquals(0, conn.disconnects)
                } finally {
                    sessionsCollector.cancel()
                }
            } finally {
                keeper.cancel()
            }
        }

    private class FakeSockets : SocketConnectionFactory {
        val made: MutableList<Connection> = Collections.synchronizedList(mutableListOf())
        val connects: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun create(
            clientOptions: HttpClientOptions,
            scope: CoroutineScope,
        ): SocketConnection = Connection().also { made += it }

        suspend fun awaitConnects(count: Int) =
            withTimeout(5_000) { while (connects.size < count) delay(10) }

        inner class Connection : SocketConnection {
            private val _state =
                MutableStateFlow<SocketConnectionState>(SocketConnectionState.Disconnected())
            override val state: StateFlow<SocketConnectionState> = _state

            val sent: MutableList<String> = Collections.synchronizedList(mutableListOf())
            @Volatile var disconnects = 0
            @Volatile private var open = false

            override suspend fun connect(
                url: String,
                clientName: String,
                clientVersion: String,
                deviceId: String,
                deviceName: String,
                accessToken: String,
                languages: List<String>,
            ): Boolean {
                if (open) disconnect()
                connects += url
                open = true
                _state.value = SocketConnectionState.Connecting
                return true
            }

            fun receive(raw: String) {
                _state.value = SocketConnectionState.Message(raw)
            }

            override suspend fun send(message: String): Boolean {
                sent += message
                return true
            }

            override suspend fun disconnect() {
                if (!open) return
                open = false
                disconnects++
                _state.value = SocketConnectionState.Disconnected()
            }
        }
    }

    private companion object {
        const val VALID_PLAYSTATE =
            """{"MessageType":"Playstate","MessageId":"00000000000000000000000000000003","Data":{"Command":"PlayPause"}}"""
    }
}
