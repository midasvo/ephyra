package dev.jdtech.jellyfin.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.util.ApiSerializer
import org.jellyfin.sdk.api.sockets.SocketConnection
import org.jellyfin.sdk.api.sockets.SocketConnectionFactory
import org.jellyfin.sdk.api.sockets.SocketConnectionState
import timber.log.Timber

/**
 * #1011: wraps a [SocketConnectionFactory] so incoming socket messages that the SDK's
 * [ApiSerializer.decodeSocketMessage] cannot decode (such as an unknown `MessageType` or a non-UUID
 * id) are dropped and logged before `DefaultSocketApi` sees them and closes the socket.
 */
internal class SafeSocketConnectionFactory(private val delegate: SocketConnectionFactory) :
    SocketConnectionFactory {
    override fun create(clientOptions: HttpClientOptions, scope: CoroutineScope): SocketConnection =
        SafeSocketConnection(delegate.create(clientOptions, scope), scope)
}

private class SafeSocketConnection(private val delegate: SocketConnection, scope: CoroutineScope) :
    SocketConnection {
    override val state: StateFlow<SocketConnectionState> =
        delegate.state
            .filter { item ->
                if (item !is SocketConnectionState.Message) return@filter true
                canDecode(item.message)
            }
            .stateIn(scope, SharingStarted.Eagerly, delegate.state.value)

    override suspend fun connect(
        url: String,
        clientName: String,
        clientVersion: String,
        deviceId: String,
        deviceName: String,
        accessToken: String,
        languages: List<String>,
    ): Boolean =
        delegate.connect(
            url,
            clientName,
            clientVersion,
            deviceId,
            deviceName,
            accessToken,
            languages,
        )

    override suspend fun send(message: String): Boolean = delegate.send(message)

    override suspend fun disconnect() = delegate.disconnect()

    private fun canDecode(raw: String): Boolean =
        try {
            ApiSerializer.decodeSocketMessage(raw)
            true
        } catch (e: Exception) {
            Timber.w("Dropping unreadable socket message (MessageType=%s)", messageTypeOf(raw))
            false
        }

    private fun messageTypeOf(raw: String): String =
        try {
            Json.parseToJsonElement(raw).jsonObject["MessageType"]?.jsonPrimitive?.contentOrNull
                ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
}
