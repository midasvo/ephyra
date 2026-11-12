package dev.jdtech.jellyfin.setup.domain

import java.net.InetAddress
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CE (#376): on Android 17 a denied local network permission made discovery and every address on
 * the local network fail with nothing but "Server not found". These are the decisions behind the
 * texts that now name the permission: whether it applies, and whether an address is a local one.
 */
class LocalNetworkAccessTest {
    private fun bytes(address: String): ByteArray = parseIpAddress(address)!!

    @Test
    fun `the permission does not exist before Android 17, granted or not`() {
        for (sdk in listOf(29, 30, 33, 34, 35, 36)) {
            assertFalse("API $sdk", localNetworkAccessDenied(sdkInt = sdk, granted = false))
            assertFalse("API $sdk", localNetworkAccessDenied(sdkInt = sdk, granted = true))
        }
    }

    @Test
    fun `from Android 17 access is denied until the permission is granted`() {
        assertTrue(localNetworkAccessDenied(sdkInt = 37, granted = false))
        assertFalse(localNetworkAccessDenied(sdkInt = 37, granted = true))
        assertTrue(localNetworkAccessDenied(sdkInt = 38, granted = false))
    }

    @Test
    fun `private, link-local, multicast and broadcast addresses are local`() {
        val local =
            listOf(
                "10.0.2.2",
                "10.255.255.255",
                "172.16.0.1",
                "172.31.255.254",
                "192.168.1.10",
                "169.254.10.20",
                "100.64.0.1", // carrier-grade NAT, Tailscale
                "100.127.255.254",
                "224.0.0.251",
                "239.255.255.250",
                "255.255.255.255",
                "fe80::1",
                "febf::1",
                "fc00::1",
                "fd12:3456:789a::1",
                "ff02::fb",
                "::ffff:192.168.1.10", // IPv4 written as IPv6
                "::ffff:10.0.2.2",
            )
        for (address in local) {
            assertTrue(address, isLocalNetworkAddress(bytes(address)))
        }
    }

    @Test
    fun `internet and loopback addresses are not local`() {
        val notLocal =
            listOf(
                "8.8.8.8",
                "172.15.255.255",
                "172.32.0.1",
                "192.167.1.1",
                "192.169.1.1",
                "100.63.255.255",
                "100.128.0.1",
                "169.253.1.1",
                "223.255.255.255",
                "240.0.0.1",
                "127.0.0.1",
                "0.0.0.0",
                "::1",
                "2001:db8::1",
                "2a00:1450:400e:80c::200e",
                "fec0::1",
                "::ffff:8.8.8.8",
                "::ffff:127.0.0.1",
            )
        for (address in notLocal) {
            assertFalse(address, isLocalNetworkAddress(bytes(address)))
        }
    }

    @Test
    fun `an IPv4 address inside 16 bytes is read as IPv4`() {
        // The JVM hands a mapped address back as 4 bytes, so build the 16 by hand.
        val mapped = ByteArray(16)
        mapped[10] = 0xFF.toByte()
        mapped[11] = 0xFF.toByte()
        InetAddress.getByName("192.168.1.10").address.copyInto(mapped, 12)
        assertTrue(isLocalNetworkAddress(mapped))

        InetAddress.getByName("8.8.8.8").address.copyInto(mapped, 12)
        assertFalse(isLocalNetworkAddress(mapped))
    }

    @Test
    fun `bytes that are no address are not local`() {
        assertFalse(isLocalNetworkAddress(ByteArray(0)))
        assertFalse(isLocalNetworkAddress(byteArrayOf(10, 0, 2)))
    }

    @Test
    fun `the host is read from an address as it is typed`() {
        assertEquals("10.0.2.2", hostOfServerAddress("10.0.2.2:8096"))
        assertEquals("10.0.2.2", hostOfServerAddress("  http://10.0.2.2:8096/jellyfin  "))
        assertEquals("demo.jellyfin.org", hostOfServerAddress("https://demo.jellyfin.org/stable"))
        assertEquals("nas", hostOfServerAddress("nas"))
        assertEquals("fe80::1", hostOfServerAddress("http://[fe80::1]:8096"))
        assertEquals("fd00::1", hostOfServerAddress("[fd00::1]:8096"))
        assertNull(hostOfServerAddress(""))
        assertNull(hostOfServerAddress("   "))
        assertNull(hostOfServerAddress("http://"))
        assertNull(hostOfServerAddress("not a host"))
    }

    @Test
    fun `an IP address is parsed without a lookup, a name is not parsed`() {
        assertArrayEquals(byteArrayOf(10, 0, 2, 2), parseIpAddress("10.0.2.2"))
        assertEquals(16, parseIpAddress("fe80::1")!!.size)
        assertNull(parseIpAddress("demo.jellyfin.org"))
        assertNull(parseIpAddress("nas"))
        assertNull(parseIpAddress("localhost"))
        assertNull(parseIpAddress("300.1.1.1"))
        assertNull(parseIpAddress("1.2.3"))
        assertNull(parseIpAddress(""))
    }

    @Test
    fun `a name is local when it resolves to a local address`() {
        assertTrue(isLocalNetworkHost("jellyfin.example.org", listOf(bytes("192.168.1.10"))))
        assertTrue(
            isLocalNetworkHost("jellyfin.example.org", listOf(bytes("8.8.8.8"), bytes("10.0.0.5")))
        )
        assertFalse(isLocalNetworkHost("demo.jellyfin.org", listOf(bytes("8.8.8.8"))))
        assertFalse(isLocalNetworkHost("localhost", listOf(bytes("127.0.0.1"), bytes("::1"))))
        // What it resolves to wins over what the name looks like.
        assertFalse(isLocalNetworkHost("nas", listOf(bytes("8.8.8.8"))))
    }

    @Test
    fun `a name that does not resolve is local only when it can be nothing else`() {
        assertTrue(isLocalNetworkHost("nas", emptyList()))
        assertTrue(isLocalNetworkHost("nas.local", emptyList()))
        assertTrue(isLocalNetworkHost("NAS.Local.", emptyList()))
        assertFalse(isLocalNetworkHost("jellyfin.example.org", emptyList()))
        assertFalse(isLocalNetworkHost("localhost", emptyList()))
        assertFalse(isLocalNetworkHost("", emptyList()))
    }

    @Test
    fun `a local address explains a failed connection only while access is denied`() = runTest {
        val denied = FakeLocalNetworkAccess(denied = true)
        val granted = FakeLocalNetworkAccess(denied = false)

        assertTrue(denied.blocks("10.0.2.2:8096"))
        assertTrue(denied.blocks("http://192.168.1.10:8096/jellyfin"))
        assertTrue(denied.blocks("[fd00::1]:8096"))
        assertFalse(granted.blocks("10.0.2.2:8096"))
    }

    @Test
    fun `an internet, loopback or unreadable address keeps the usual error`() = runTest {
        val denied = FakeLocalNetworkAccess(denied = true)

        assertFalse(denied.blocks("8.8.8.8:8096"))
        assertFalse(denied.blocks("127.0.0.1:8096"))
        assertFalse(denied.blocks("[::1]:8096"))
        assertFalse(denied.blocks(""))
        assertFalse(denied.blocks("not a host"))
        assertFalse(denied.blocks("localhost:8096") { listOf(bytes("127.0.0.1")) })
        assertFalse(denied.blocks("demo.jellyfin.org/stable") { listOf(bytes("8.8.8.8")) })
        assertFalse(denied.blocks("jellyfin.example.org") { emptyList() })
    }

    @Test
    fun `a name is looked up, and only while access is denied`() = runTest {
        val lookedUp = mutableListOf<String>()
        val resolve: suspend (String) -> List<ByteArray> = { host ->
            lookedUp.add(host)
            listOf(bytes("192.168.1.10"))
        }

        assertFalse(FakeLocalNetworkAccess(denied = false).blocks("jellyfin.example.org", resolve))
        assertTrue(lookedUp.isEmpty())

        assertTrue(FakeLocalNetworkAccess(denied = true).blocks("jellyfin.example.org", resolve))
        assertEquals(listOf("jellyfin.example.org"), lookedUp)
    }

    @Test
    fun `a refused request opens the settings only when the system showed no dialog`() {
        // Never asked: the dialog showed, and a first denial leaves a rationale.
        assertFalse(shouldOpenSettingsAfterRefusal(rationaleBefore = false, rationaleAfter = true))
        // Denied once: the dialog showed, and the second denial is final.
        assertFalse(shouldOpenSettingsAfterRefusal(rationaleBefore = true, rationaleAfter = false))
        // Denied twice already: no dialog.
        assertTrue(shouldOpenSettingsAfterRefusal(rationaleBefore = false, rationaleAfter = false))
    }
}
