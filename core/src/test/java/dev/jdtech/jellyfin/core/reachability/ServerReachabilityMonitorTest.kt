package dev.jdtech.jellyfin.core.reachability

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CE (#281): the probes behind the reachability banner. The risk named in the issue is a probe
 * with a bad timeout that makes the app wait, so the first tests are about time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerReachabilityMonitorTest {
    private var probes = 0
    private var probe: suspend () -> Boolean? = { true }
    private var network = true
    private var enabled = true

    private fun TestScope.monitor() =
        ServerReachabilityMonitor(
            scope = backgroundScope,
            probe = {
                probes++
                probe()
            },
            hasNetwork = { network },
            enabled = { enabled },
            now = { testScheduler.currentTime },
            probeTimeoutMs = 3_000,
            settleMs = 250,
            slowRequestMs = 1_500,
        )

    private val ServerReachabilityMonitor.reachability
        get() = state.value.reachability

    @Test
    fun `asking returns at once and a probe that never answers ends after three seconds`() =
        runTest {
            probe = { awaitCancellation() }
            val monitor = monitor()

            monitor.onForeground()
            // Nothing waited: the call is back and the state is what it was.
            assertEquals(Reachability.Unknown, monitor.reachability)

            advanceTimeBy(2_999)
            runCurrent()
            assertEquals(Reachability.Unknown, monitor.reachability)

            advanceTimeBy(2)
            runCurrent()
            assertEquals(Reachability.Unreachable, monitor.reachability)
            assertFalse("one slow probe does not confirm", monitor.state.value.confirmed)
        }

    @Test
    fun `one probe runs at a time`() = runTest {
        probe = {
            delay(1_000)
            true
        }
        val monitor = monitor()

        monitor.check()
        monitor.check()
        monitor.onForeground()
        runCurrent()
        assertEquals(1, probes)

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(1, probes)
        assertEquals(Reachability.Reachable, monitor.reachability)
    }

    @Test
    fun `without a network it is unreachable at once, and nothing is probed`() = runTest {
        network = false
        val monitor = monitor()

        monitor.onForeground()
        runCurrent()

        assertTrue(monitor.state.value.confirmedUnreachable)
        assertEquals(0, probes)
    }

    @Test
    fun `the network coming back clears it without a restart`() = runTest {
        network = false
        val monitor = monitor()
        monitor.onForeground()
        runCurrent()
        assertTrue(monitor.state.value.unreachable)

        network = true
        monitor.onNetworkChanged()
        // A change of network comes in bursts: it settles first.
        runCurrent()
        assertEquals(0, probes)
        advanceTimeBy(251)
        runCurrent()

        assertEquals(1, probes)
        assertEquals(Reachability.Reachable, monitor.reachability)
    }

    @Test
    fun `a network change during a probe probes again over the new network`() = runTest {
        var reached = false
        probe = {
            delay(1_000)
            reached
        }
        val monitor = monitor()
        monitor.check()
        runCurrent()

        reached = true
        monitor.onNetworkChanged()
        advanceTimeBy(1_001 + 250 + 1_001)
        runCurrent()

        assertEquals(2, probes)
        assertEquals(Reachability.Reachable, monitor.reachability)
    }

    @Test
    fun `a failed request asks the server, and the answer confirms at once`() = runTest {
        probe = { false }
        val monitor = monitor()

        monitor.onStarted()
        monitor.onUnanswered()
        runCurrent()

        assertEquals(1, probes)
        assertTrue(monitor.state.value.confirmedUnreachable)

        // Screens that keep failing do not keep asking.
        repeat(5) {
            monitor.onStarted()
            monitor.onUnanswered()
        }
        runCurrent()
        assertEquals(1, probes)
    }

    @Test
    fun `an answered request clears the banner without a probe`() = runTest {
        probe = { false }
        val monitor = monitor()
        monitor.check()
        runCurrent()
        assertTrue(monitor.state.value.unreachable)
        val probesSoFar = probes

        monitor.onStarted()
        monitor.onAnswered()

        assertEquals(Reachability.Reachable, monitor.reachability)
        assertEquals(probesSoFar, probes)
    }

    @Test
    fun `a request that stays unanswered gets a probe after a second and a half`() = runTest {
        probe = { false }
        val monitor = monitor()

        monitor.onStarted()
        advanceTimeBy(1_499)
        runCurrent()
        assertEquals(0, probes)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, probes)
        assertTrue(monitor.state.value.unreachable)
        assertFalse("the request itself has not failed yet", monitor.state.value.confirmed)

        // The request gives up after its own timeout: that confirms.
        monitor.onUnanswered()
        assertTrue(monitor.state.value.confirmedUnreachable)
    }

    @Test
    fun `requests that are answered in time are never probed`() = runTest {
        val monitor = monitor()

        repeat(10) {
            monitor.onStarted()
            advanceTimeBy(400)
            monitor.onAnswered()
        }
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(0, probes)
        assertEquals(Reachability.Reachable, monitor.reachability)
    }

    @Test
    fun `while unreachable in the foreground it asks again at 5, 10 and 20 seconds`() = runTest {
        probe = { false }
        val monitor = monitor()
        monitor.onForeground()
        runCurrent()
        assertEquals(1, probes)

        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(2, probes)

        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(3, probes)

        advanceTimeBy(19_000)
        runCurrent()
        assertEquals(3, probes)

        // The server is back: the next retry finds it and the retries stop.
        probe = { true }
        advanceTimeBy(1_002)
        runCurrent()
        assertEquals(4, probes)
        assertEquals(Reachability.Reachable, monitor.reachability)

        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(4, probes)
    }

    @Test
    fun `in the background nothing is asked, and coming back asks at once`() = runTest {
        probe = { false }
        val monitor = monitor()
        monitor.onForeground()
        runCurrent()
        assertEquals(1, probes)

        monitor.onBackground()
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(1, probes)

        monitor.onForeground()
        runCurrent()
        assertEquals(2, probes)
    }

    @Test
    fun `with Offline Mode on nothing is probed or shown, and the signs are passed on`() =
        runTest {
            enabled = false
            probe = { false }
            val monitor = monitor()
            var confirmed = 0
            backgroundScope.launch { monitor.serverConfirmed.collect { confirmed++ } }
            runCurrent()

            monitor.onForeground()
            monitor.onStarted()
            monitor.onUnanswered()
            advanceTimeBy(60_000)
            runCurrent()

            assertEquals(0, probes)
            assertEquals(Reachability.Unknown, monitor.reachability)
            // The user data sync still gets "the app is in front", as before #281.
            assertEquals(1, confirmed)

            monitor.onNetworkChanged()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(0, probes)
            assertEquals(2, confirmed)
        }

    @Test
    fun `the sync is told when a probe reaches the server, not when it fails`() = runTest {
        probe = { false }
        val monitor = monitor()
        var confirmed = 0
        backgroundScope.launch { monitor.serverConfirmed.collect { confirmed++ } }
        runCurrent()

        monitor.onForeground()
        runCurrent()
        assertEquals(0, confirmed)

        probe = { true }
        monitor.onNetworkChanged()
        advanceTimeBy(300)
        runCurrent()
        assertEquals(1, confirmed)

        // In front again right after an answer: no probe, and the sync is told all the same.
        val probesSoFar = probes
        monitor.onBackground()
        monitor.onForeground()
        runCurrent()
        assertEquals(probesSoFar, probes)
        assertEquals(2, confirmed)
    }

    @Test
    fun `Offline Mode switched on while unreachable clears what was known`() = runTest {
        probe = { false }
        val monitor = monitor()
        monitor.onForeground()
        runCurrent()
        assertTrue(monitor.state.value.unreachable)

        // The setting restarts the activity: the app is in front again with the mode on.
        enabled = false
        monitor.onBackground()
        monitor.onForeground()
        runCurrent()

        assertEquals(Reachability.Unknown, monitor.reachability)
    }

    @Test
    fun `an answer about the old server is dropped and the new one is asked`() = runTest {
        var reached = false
        probe = {
            delay(1_000)
            reached
        }
        val monitor = monitor()
        monitor.check()
        runCurrent()
        assertEquals(1, probes)

        // The user switches server while the probe to the old, dead one is out.
        reached = true
        monitor.reset()
        advanceTimeBy(1_001)
        runCurrent()
        assertFalse("the old server's answer", monitor.state.value.unreachable)

        advanceTimeBy(250 + 1_001)
        runCurrent()
        assertEquals(2, probes)
        assertEquals(Reachability.Reachable, monitor.reachability)
    }

    @Test
    fun `a probe that throws counts as not reached and the next one still runs`() = runTest {
        probe = { throw IllegalStateException("boom") }
        val monitor = monitor()
        monitor.check()
        runCurrent()
        assertTrue(monitor.state.value.unreachable)

        probe = { true }
        monitor.check()
        runCurrent()
        assertEquals(Reachability.Reachable, monitor.reachability)
    }

    @Test
    fun `without a server nothing is known`() = runTest {
        probe = { null }
        val monitor = monitor()

        monitor.onForeground()
        runCurrent()

        assertEquals(Reachability.Unknown, monitor.reachability)
    }

    @Test
    fun `another server is asked about from nothing`() = runTest {
        probe = { false }
        val monitor = monitor()
        monitor.check()
        runCurrent()
        assertTrue(monitor.state.value.unreachable)

        probe = { true }
        monitor.reset()
        assertEquals(Reachability.Unknown, monitor.reachability)
        runCurrent()

        assertEquals(Reachability.Reachable, monitor.reachability)
    }
}
