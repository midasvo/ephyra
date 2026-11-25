package dev.jdtech.jellyfin.domain.ports

import dev.jdtech.jellyfin.models.ServerInfo

/**
 * What the server says about itself (#967): its id, name and version, which Settings → About shows
 * and Home uses to notice a renamed server. Failures are thrown as `ServerException` (#756).
 */
interface ServerInfoPort {
    suspend fun serverInfo(): ServerInfo
}
