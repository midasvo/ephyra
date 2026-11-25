package dev.jdtech.jellyfin.core.server

import dev.jdtech.jellyfin.domain.ports.ActiveServer
import dev.jdtech.jellyfin.domain.ports.ActiveServerHolder
import dev.jdtech.jellyfin.domain.ports.ItemExtrasPort
import dev.jdtech.jellyfin.domain.ports.ItemListPort
import dev.jdtech.jellyfin.domain.ports.ItemPort
import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.domain.ports.PlaybackPort
import dev.jdtech.jellyfin.domain.ports.ServerInfoPort
import dev.jdtech.jellyfin.domain.ports.ServerSession
import dev.jdtech.jellyfin.domain.ports.UserDataPort
import dev.jdtech.jellyfin.models.Library
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.ServerType
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.testing.fakeProxy
import java.util.Collections
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * #899: a server switch while a request is between "pick the adapter" and "read the address and the
 * token". Before, the type was on one field and the address and the token on the client, and the
 * request read them at two moments: it went out with one server's adapter and the other server's
 * address and token. Now [CurrentMediaServer] reads the [ActiveServerHolder] once and the adapter's
 * ports use that value ([MediaServer.at]), so the request uses one consistent set.
 */
class CurrentMediaServerSwitchTest {
    private val active = ActiveServerHolder()

    /** What a request went out with: the adapter's type, the address and the token. */
    private data class Sent(val adapter: ServerType, val baseUrl: String?, val accessToken: String?)

    private val sent: MutableList<Sent> = Collections.synchronizedList(mutableListOf())

    /** Held until opened: the request waits there after its adapter was picked. */
    private var gate = CompletableDeferred<Unit>()

    /**
     * An adapter whose one request ([ItemListPort.libraries]) waits at [gate] and then reads the
     * address and the token: of the value it was handed with [MediaServer.at], or, with
     * [followsHolder], of the holder at that moment, as the client read its fields before.
     */
    private inner class Adapter(
        override val type: ServerType,
        private val handed: ActiveServer? = null,
        private val followsHolder: Boolean = false,
    ) : MediaServer {
        override val capabilities = ServerCapabilities.ALL
        override val session: ServerSession = uncalled()
        override val userData: UserDataPort = uncalled()
        override val playback: PlaybackPort = uncalled()
        override val items: ItemPort = uncalled()
        override val itemExtras: ItemExtrasPort = uncalled()
        override val info: ServerInfoPort = uncalled()

        override val itemLists: ItemListPort =
            object : ItemListPort by uncalled() {
                override suspend fun libraries(): List<Library> {
                    gate.await()
                    val server = if (followsHolder) active.value else checkNotNull(handed)
                    sent += Sent(type, server.baseUrl, server.accessToken)
                    return emptyList()
                }
            }

        override fun at(active: ActiveServer): MediaServer =
            if (followsHolder) this else Adapter(type, active)
    }

    private fun currentServer(followsHolder: Boolean = false) =
        CurrentMediaServer(
            mapOf(
                ServerType.JELLYFIN to Adapter(ServerType.JELLYFIN, followsHolder = followsHolder),
                ServerType.EMBY to Adapter(ServerType.EMBY, followsHolder = followsHolder),
            ),
            active,
        )

    /** Starts a request on [server], switches to [EMBY_B] while it waits, then lets it go. */
    private fun requestAcrossASwitch(server: MediaServer) = runBlocking {
        active.switchTo(JELLYFIN_A)
        val request = async(start = CoroutineStart.UNDISPATCHED) { server.itemLists.libraries() }

        active.switchTo(EMBY_B)
        gate.complete(Unit)
        request.await()
    }

    @Test
    fun `a request started before a switch uses the adapter, address and token of one server`() {
        val server = currentServer()

        requestAcrossASwitch(server)

        assertEquals(listOf(Sent(ServerType.JELLYFIN, A, "token-a")), sent.toList())
    }

    @Test
    fun `the next request uses the new server's adapter, address and token`() = runBlocking {
        val server = currentServer()
        requestAcrossASwitch(server)
        sent.clear()

        server.itemLists.libraries()

        assertEquals(listOf(Sent(ServerType.EMBY, B, "token-b")), sent.toList())
    }

    @Test
    fun `reading the address apart from the type mixes the two servers`() {
        // The check above sees the race: an adapter that reads the address and the token at their
        // own moment, as before, sends the Jellyfin request to the Emby server with its token.
        val server = currentServer(followsHolder = true)

        requestAcrossASwitch(server)

        assertEquals(listOf(Sent(ServerType.JELLYFIN, B, "token-b")), sent.toList())
    }

    private companion object {
        const val A = "https://a.example"
        const val B = "https://b.example"

        val JELLYFIN_A =
            ActiveServer(
                type = ServerType.JELLYFIN.value,
                serverId = "a".toServerId(),
                baseUrl = A,
                accessToken = "token-a",
                userId = "00000000-0000-0000-0000-00000000000a".toUserId(),
            )
        val EMBY_B =
            ActiveServer(
                type = ServerType.EMBY.value,
                serverId = "b".toServerId(),
                baseUrl = B,
                accessToken = "token-b",
                userId = "00000000-0000-0000-0000-00000000000b".toUserId(),
            )

        inline fun <reified T> uncalled(): T =
            fakeProxy(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
                throw NotImplementedError("${T::class.simpleName}.${method.name}")
            }
                as T
    }
}
