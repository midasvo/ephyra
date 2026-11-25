package dev.jdtech.jellyfin.domain.ports

import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.ServerType

/** One server, as the repository sees it (#757). */
interface MediaServer {
    val type: ServerType

    /** #769: what this server can do; the app does not offer what it cannot. */
    val capabilities: ServerCapabilities

    val session: ServerSession

    /**
     * #967: the catalog by role, each one port an adapter can implement on its own (the Jellyfin
     * adapter implements all four with one class).
     */
    val items: ItemPort
    val itemLists: ItemListPort
    val itemExtras: ItemExtrasPort
    val info: ServerInfoPort

    val userData: UserDataPort
    val playback: PlaybackPort

    /**
     * #899: this server's ports with every request they make held to [active]: its address, its
     * token and its user, whatever is switched to while the request runs. `CurrentMediaServer`
     * picks the adapter by [ActiveServer.type] and hands it the same value, so a request never
     * mixes the adapter of one server with the address or token of another. The [session] keeps
     * following the server in use.
     *
     * The default is this server itself, for servers whose ports keep no address of their own (the
     * fakes, the server of an unsupported type).
     */
    fun at(active: ActiveServer): MediaServer = this
}
