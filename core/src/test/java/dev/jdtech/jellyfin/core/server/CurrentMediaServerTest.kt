package dev.jdtech.jellyfin.core.server

import dev.jdtech.jellyfin.core.presentation.session.NoServerSession
import dev.jdtech.jellyfin.domain.ports.ActiveServer
import dev.jdtech.jellyfin.domain.ports.ActiveServerHolder
import dev.jdtech.jellyfin.domain.ports.ItemExtrasPort
import dev.jdtech.jellyfin.domain.ports.ItemListPort
import dev.jdtech.jellyfin.domain.ports.ItemPort
import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.domain.ports.PlaybackPort
import dev.jdtech.jellyfin.domain.ports.ServerInfoPort
import dev.jdtech.jellyfin.domain.ports.ServerSession
import dev.jdtech.jellyfin.domain.ports.UnsupportedServerTypeException
import dev.jdtech.jellyfin.domain.ports.UserDataPort
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.ServerType
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** #770: [CurrentMediaServer] delegates to the adapter of the current server's type. */
class CurrentMediaServerTest {
    private val jellyfin = FakeMediaServer(ServerType.JELLYFIN, ServerCapabilities.ALL)
    private val emby =
        FakeMediaServer(ServerType.EMBY, ServerCapabilities.ALL.copy(quickConnect = false))
    private val active = ActiveServerHolder()

    @Test
    fun `every port is the current type's adapter's`() {
        val server =
            CurrentMediaServer(
                mapOf(ServerType.JELLYFIN to jellyfin, ServerType.EMBY to emby),
                active,
            )

        active.switchTo(ActiveServer(type = "emby"))

        assertEquals(ServerType.EMBY, server.type)
        assertSame(emby.capabilities, server.capabilities)
        assertSame(emby.session, server.session)
        assertSame(emby.items, server.items)
        assertSame(emby.itemLists, server.itemLists)
        assertSame(emby.itemExtras, server.itemExtras)
        assertSame(emby.info, server.info)
        assertSame(emby.userData, server.userData)
        assertSame(emby.playback, server.playback)
    }

    @Test
    fun `it follows a switch of server, and a call keeps the adapter it started on`() {
        val server =
            CurrentMediaServer(
                mapOf(ServerType.JELLYFIN to jellyfin, ServerType.EMBY to emby),
                active,
            )
        active.switchTo(ActiveServer(type = "jellyfin"))
        val startedOnJellyfin = server.items

        active.switchTo(ActiveServer(type = "emby"))

        assertSame(jellyfin.items, startedOnJellyfin)
        assertSame(emby.items, server.items)
        assertEquals(ServerType.EMBY, server.type)

        active.switchTo(ActiveServer(type = "jellyfin"))

        assertSame(jellyfin.items, server.items)
    }

    @Test
    fun `without a server it is the Jellyfin adapter, as before servers had a type`() {
        val server = CurrentMediaServer(mapOf(ServerType.JELLYFIN to jellyfin), active)

        assertNull(active.value.type)
        assertSame(jellyfin.items, server.items)
        assertSame(jellyfin.session, server.session)
    }

    @Test
    fun `a type without an adapter offers nothing and fails every request`() = runBlocking {
        val server = CurrentMediaServer(mapOf(ServerType.JELLYFIN to jellyfin), active)

        for (stored in listOf("emby", "plex")) {
            active.switchTo(ActiveServer(type = stored))

            assertEquals(ServerCapabilities.NONE, server.capabilities)
            assertSame(NoServerSession, server.session)
            assertNull(server.playback.chapterImageUrl(ITEM, 0, "tag"))
            assertNull(server.playback.seasonBackdropUrl(ITEM))
            val failures =
                listOf(
                    assertFailsWith<UnsupportedServerTypeException> { server.items.movie(ITEM) },
                    assertFailsWith<UnsupportedServerTypeException> {
                        server.itemLists.libraries()
                    },
                    assertFailsWith<UnsupportedServerTypeException> {
                        server.itemExtras.similar(ITEM, limit = 12)
                    },
                    assertFailsWith<UnsupportedServerTypeException> { server.info.serverInfo() },
                    assertFailsWith<UnsupportedServerTypeException> {
                        server.userData.setPlayed(ITEM, true)
                    },
                    assertFailsWith<UnsupportedServerTypeException> {
                        server.playback.segments(ITEM)
                    },
                    assertFailsWith<UnsupportedServerTypeException> { server.type },
                )
            failures.forEach { assertEquals(stored, it.storedType) }
        }
    }

    /** Ports that are only compared, never called. */
    private class FakeMediaServer(
        override val type: ServerType,
        override val capabilities: ServerCapabilities,
    ) : MediaServer {
        override val session: ServerSession = uncalled()
        override val items: ItemPort = uncalled()
        override val itemLists: ItemListPort = uncalled()
        override val itemExtras: ItemExtrasPort = uncalled()
        override val info: ServerInfoPort = uncalled()
        override val userData: UserDataPort = uncalled()
        override val playback: PlaybackPort = uncalled()
    }

    private companion object {
        val ITEM: ItemId = "00000000-0000-0000-0000-000000000001".toItemId()

        inline fun <reified T> uncalled(): T =
            fakeProxy(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
                when (method.kotlinName) {
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "uncalled ${T::class.simpleName}"
                    else -> throw NotImplementedError("${T::class.simpleName}.${method.kotlinName}")
                }
            }
                as T
    }
}
