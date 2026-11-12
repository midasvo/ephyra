package dev.jdtech.jellyfin.core.presentation.localnetwork

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CE (#571): an error screen loads again by itself when local network access comes back. Once: a
 * retry that fails for another reason must show that error and stop.
 */
class UnblockRetryTest {
    @Test
    fun `access coming back retries once`() {
        val retry = UnblockRetry()

        assertFalse(retry.shouldRetry(explained = true, blocked = true))
        assertTrue(retry.shouldRetry(explained = false, blocked = false))
        // The same state again, as a recomposition gives it: nothing more.
        assertFalse(retry.shouldRetry(explained = false, blocked = false))
    }

    @Test
    fun `a retry that fails for another reason does not retry again`() {
        val retry = UnblockRetry()
        retry.shouldRetry(explained = true, blocked = true)
        assertTrue(retry.shouldRetry(explained = false, blocked = false))

        // The server is down as well: the next error is not explained by the block.
        repeat(5) { assertFalse(retry.shouldRetry(explained = false, blocked = false)) }
    }

    @Test
    fun `an error that was never explained by the block does not retry`() {
        val retry = UnblockRetry()

        assertFalse(retry.shouldRetry(explained = false, blocked = false))
        assertFalse(retry.shouldRetry(explained = false, blocked = true))
        assertFalse(retry.shouldRetry(explained = false, blocked = false))
    }

    @Test
    fun `an error that changes while still blocked waits for access`() {
        val retry = UnblockRetry()
        retry.shouldRetry(explained = true, blocked = true)

        // Another error replaced the connection error; access is still denied.
        assertFalse(retry.shouldRetry(explained = false, blocked = true))
        assertTrue(retry.shouldRetry(explained = false, blocked = false))
    }

    @Test
    fun `being blocked a second time retries a second time`() {
        val retry = UnblockRetry()
        retry.shouldRetry(explained = true, blocked = true)
        assertTrue(retry.shouldRetry(explained = false, blocked = false))

        assertFalse(retry.shouldRetry(explained = true, blocked = true))
        assertTrue(retry.shouldRetry(explained = false, blocked = false))
    }
}
