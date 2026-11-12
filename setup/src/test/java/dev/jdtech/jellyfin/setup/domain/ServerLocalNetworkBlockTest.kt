package dev.jdtech.jellyfin.setup.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CE (#571): with local network access revoked after sign-in, the screens could only say "Error
 * loading data". They ask whether the server in use is blocked, which is true only while access is
 * denied and that server is on the local network.
 */
class ServerLocalNetworkBlockTest {
    private val noLookup: suspend (String) -> List<ByteArray> = { host ->
        parseIpAddress(host)?.let(::listOf) ?: emptyList()
    }

    private fun block(
        access: LocalNetworkAccess,
        address: String?,
        resolve: suspend (String) -> List<ByteArray> = noLookup,
    ) = ServerLocalNetworkBlock(access, { address }, resolve)

    @Test
    fun `a server on the local network is blocked while access is denied`() = runTest {
        val access = FakeLocalNetworkAccess(denied = true, settingName = "Nearby devices")
        val block = block(access, "http://10.0.2.2:8096")

        assertTrue(block.refresh())

        assertEquals(
            LocalNetworkBlockState(blocked = true, settingName = "Nearby devices"),
            block.state.value,
        )
    }

    @Test
    fun `a server on the internet is not blocked while access is denied`() = runTest {
        val block = block(FakeLocalNetworkAccess(denied = true), "https://8.8.8.8:8096")

        assertFalse(block.refresh())

        assertEquals(LocalNetworkBlockState(), block.state.value)
    }

    @Test
    fun `a host name counts by the addresses it resolves to`() = runTest {
        val access = FakeLocalNetworkAccess(denied = true)
        val lan: suspend (String) -> List<ByteArray> = { listOf(byteArrayOf(192.toByte(), 168.toByte(), 1, 10)) }
        val internet: suspend (String) -> List<ByteArray> = { listOf(byteArrayOf(8, 8, 8, 8)) }

        assertTrue(block(access, "https://jellyfin.example.org", lan).refresh())
        assertFalse(block(access, "https://jellyfin.example.org", internet).refresh())
    }

    @Test
    fun `nothing is blocked while access is granted`() = runTest {
        // Android 16 and older never deny: localNetworkAccessDenied is false there, see
        // LocalNetworkAccessTest.
        val block = block(FakeLocalNetworkAccess(denied = false, settingName = "Nearby devices"), "http://10.0.2.2:8096")

        assertFalse(block.refresh())

        assertEquals(LocalNetworkBlockState(), block.state.value)
    }

    @Test
    fun `nothing is blocked without a server`() = runTest {
        val access = FakeLocalNetworkAccess(denied = true)

        assertFalse(block(access, null).refresh())
        assertFalse(block(access, "").refresh())
    }

    @Test
    fun `the block ends when access returns and when the server changes`() = runTest {
        val access = FakeLocalNetworkAccess(denied = true, settingName = "Nearby devices")
        var address = "http://192.168.1.10:8096"
        val block = ServerLocalNetworkBlock(access, { address }, noLookup)
        assertTrue(block.refresh())

        access.denied = false
        assertFalse(block.refresh())
        assertEquals(LocalNetworkBlockState(), block.state.value)

        access.denied = true
        assertTrue(block.refresh())
        address = "https://203.0.113.5"
        assertFalse(block.refresh())
        assertEquals(LocalNetworkBlockState(), block.state.value)
    }
}
