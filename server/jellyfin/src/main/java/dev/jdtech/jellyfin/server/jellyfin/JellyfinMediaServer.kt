package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.domain.ports.ActiveServer
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
 * A Jellyfin server (#757): its ports, all over the app's one client.
 *
 * #899: [pin] is the value its catalog, user data and playback talk to ([at]); without one, the
 * holder's value at each read. The [session] always follows the holder.
 */
class JellyfinMediaServer(
    private val api: JellyfinApi,
    private val deviceProfiles: JellyfinDeviceProfiles,
    override val session: ServerSession = JellyfinServerSession(api),
    private val pin: ActiveServer? = null,
) : MediaServer {
    override val type: ServerType = ServerType.JELLYFIN

    /** #769: Jellyfin has every feature the app knows. */
    override val capabilities: ServerCapabilities = ServerCapabilities.ALL

    /** #967: one [JellyfinCatalog] is all four parts of the catalog. */
    private val catalog = JellyfinCatalog(api, pin)
    override val items: ItemPort = catalog
    override val itemLists: ItemListPort = catalog
    override val itemExtras: ItemExtrasPort = catalog
    override val info: ServerInfoPort = catalog
    override val userData: UserDataPort = JellyfinUserData(api, pin)
    override val playback: PlaybackPort = JellyfinPlayback(api, deviceProfiles, pin)

    /** #899: three small objects over the same client; made for each call that takes a port. */
    override fun at(active: ActiveServer): MediaServer =
        JellyfinMediaServer(api, deviceProfiles, session, active)
}
