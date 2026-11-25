package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.ServerException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Test

/** CE (#281): the server's answer when it comes, Room's copy when it doesn't. */
class StoredFallbackTest {
    private var storedBuilt = false
    private val stored: suspend () -> String = {
        storedBuilt = true
        "room"
    }

    @Test
    fun `the server's answer wins and the stored copy is not built`() = runBlocking {
        assertEquals("server", serverOrStored(stored) { "server" })
        assertTrue(!storedBuilt)
    }

    @Test
    fun `a failing server gives the stored copy`() = runBlocking {
        assertEquals("room", serverOrStored(stored) { throw IOException("unreachable") })
    }

    @Test
    fun `a slow server gives the stored copy after the timeout`() = runBlocking {
        assertEquals("room", serverOrStored(stored, timeoutMs = 50) { awaitCancellation() })
    }

    @Test
    fun `without a stored copy the server's error surfaces`() = runBlocking {
        try {
            serverOrStored<String>(stored = null) { throw IOException("unreachable") }
            fail("expected the server's error")
        } catch (e: IOException) {
            assertEquals("unreachable", e.message)
        }
    }

    @Test
    fun `a server answer of null is not taken for a timeout`() = runBlocking {
        assertNull(serverOrStored<String?>(stored) { null })
    }

    @Test
    fun `an empty stored list means Room has nothing`() = runBlocking {
        try {
            serverOrStoredList<String>(emptyList()) { throw IOException("unreachable") }
            fail("expected the server's error")
        } catch (e: IOException) {
            assertEquals("unreachable", e.message)
        }
    }

    @Test
    fun `a list waits for a slow server instead of giving the shorter stored one`() = runTest {
        val result =
            serverOrStoredWhenUnanswered(stored) {
                delay(STORED_FALLBACK_TIMEOUT_MS * 10)
                "server"
            }
        assertEquals("server", result)
        assertTrue(!storedBuilt)
    }

    @Test
    fun `a list a server leaves unanswered comes from Room`() = runTest {
        assertEquals(
            "room",
            serverOrStoredWhenUnanswered(stored) { throw IOException("unreachable") },
        )
    }

    @Test
    fun `an error the server answered with surfaces instead of the stored list`() = runTest {
        for (status in listOf(401, 500, 503)) {
            try {
                serverOrStoredWhenUnanswered(stored) { throw ServerException.HttpStatus(status) }
                fail("expected the server's $status")
            } catch (e: ServerException.HttpStatus) {
                assertEquals(status, e.status)
            }
        }
        assertTrue(!storedBuilt)
    }

    // What an adapter throws for a request that got no answer, see isUnanswered in domain. The
    // Jellyfin adapter's SdkErrorsTest checks that the SDK's client failures come out as these.
    @Test
    fun `a list falls back on a connection failure or a timeout of the client`() = runTest {
        for (failure in
            listOf(
                ServerException.Timeout(ConnectException("Connection refused")),
                ServerException.Timeout(UnknownHostException("jellyfin.home")),
                ServerException.Timeout(SocketTimeoutException("timeout")),
                ServerException.Tls(SSLHandshakeException("handshake")),
                ServerException.Unreachable(),
                RuntimeException("reset", IOException("Connection reset")),
            )) {
            assertEquals("room", serverOrStoredWhenUnanswered(stored) { throw failure }, "$failure")
        }
    }

    @Test
    fun `a body that could not be read or a mapping failure surfaces`() = runTest {
        for (failure in
            listOf(
                // What the SDK's InvalidContentException is: a body that did not decode.
                RuntimeException("bad body", SerializationException("missing field")),
                IllegalStateException("mapping failed"),
                NullPointerException(),
            )) {
            try {
                serverOrStoredWhenUnanswered(stored) { throw failure }
                fail("expected $failure to surface")
            } catch (e: Exception) {
                assertEquals(failure, e)
            }
        }
        assertTrue(!storedBuilt)
    }

    @Test
    fun `without a stored list a request left unanswered still fails`() = runTest {
        try {
            serverOrStoredWhenUnanswered<String>(stored = null) { throw IOException("unreachable") }
            fail("expected the server's error")
        } catch (e: IOException) {
            assertEquals("unreachable", e.message)
        }
    }

    @Test
    fun `a stored list stands in for a failing server`() = runBlocking {
        assertEquals(
            listOf("episode 1"),
            serverOrStoredList(listOf("episode 1")) { throw IOException("unreachable") },
        )
    }
}
