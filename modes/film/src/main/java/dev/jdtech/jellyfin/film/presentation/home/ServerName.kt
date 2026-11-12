package dev.jdtech.jellyfin.film.presentation.home

import dev.jdtech.jellyfin.models.Server
import org.jellyfin.sdk.model.api.PublicSystemInfo

/**
 * CE (#543): this server with the name it now reports, or null when there is nothing to change.
 * Only the same server renames it: an address that now answers for another server leaves the name
 * alone, and so does a blank name.
 */
fun Server.renamedTo(info: PublicSystemInfo): Server? {
    val name = info.serverName
    if (info.id != id || name.isNullOrBlank() || name == this.name) return null
    return copy(name = name)
}
