package dev.jdtech.jellyfin.testing

import dev.jdtech.jellyfin.domain.ports.ServerInfoPort
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The contract of [ServerInfoPort] (#771, #967), written like [ItemContract]. */
abstract class ServerInfoContract : MediaServerContract() {
    @Test
    fun `the server info is the server's`() = runTest {
        assertEquals(library.server.id, server().info.serverInfo().id)
    }
}
