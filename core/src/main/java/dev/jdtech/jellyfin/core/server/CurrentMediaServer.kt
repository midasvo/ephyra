package dev.jdtech.jellyfin.core.server

import dev.jdtech.jellyfin.domain.ports.ActiveServerHolder
import dev.jdtech.jellyfin.domain.ports.ItemExtrasPort
import dev.jdtech.jellyfin.domain.ports.ItemListPort
import dev.jdtech.jellyfin.domain.ports.ItemPort
import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.domain.ports.PlaybackPort
import dev.jdtech.jellyfin.domain.ports.ServerInfoPort
import dev.jdtech.jellyfin.domain.ports.ServerSession
import dev.jdtech.jellyfin.domain.ports.UserDataPort
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.ServerType

/**
 * #770: the [MediaServer] of the server in use. Delegates every port to the adapter for that
 * server's type, looked up on each access: a call takes its port once, when it starts, so a request
 * started on one server finishes on that server's adapter after a switch.
 *
 * #899: each access reads the [ActiveServerHolder] once and hands the adapter that same value
 * ([MediaServer.at]): the port a call takes uses the address, token and user of the server whose
 * type picked the adapter, also when another server is switched to before the request goes out.
 *
 * A server of a type this build has no adapter for gets [UnsupportedMediaServer]: no features, no
 * session, and every request fails with
 * [dev.jdtech.jellyfin.domain.ports.UnsupportedServerTypeException].
 */
class CurrentMediaServer(
    private val servers: Map<ServerType, MediaServer>,
    private val active: ActiveServerHolder,
) : MediaServer {
    private fun current(): MediaServer {
        val server = active.value
        val adapter =
            servers.forStoredType(server.type)
                ?: UnsupportedMediaServer(server.type ?: ServerType.JELLYFIN.value)
        return adapter.at(server)
    }

    override val type: ServerType
        get() = current().type

    override val capabilities: ServerCapabilities
        get() = current().capabilities

    override val session: ServerSession
        get() = current().session

    override val items: ItemPort
        get() = current().items

    override val itemLists: ItemListPort
        get() = current().itemLists

    override val itemExtras: ItemExtrasPort
        get() = current().itemExtras

    override val info: ServerInfoPort
        get() = current().info

    override val userData: UserDataPort
        get() = current().userData

    override val playback: PlaybackPort
        get() = current().playback
}
