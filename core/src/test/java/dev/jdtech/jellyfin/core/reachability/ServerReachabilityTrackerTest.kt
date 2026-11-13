package dev.jdtech.jellyfin.core.reachability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CE (#281): the rules that say whether the server answers, and how sure that is. */
class ServerReachabilityTrackerTest {
    private var clock = 1_000L
    private val tracker = ServerReachabilityTracker { clock }

    private fun answer() {
        tracker.onRequestStarted()
        tracker.onRequestAnswered()
    }

    private fun fail(): Boolean {
        tracker.onRequestStarted()
        return tracker.onRequestFailed()
    }

    @Test
    fun `nothing is known at the start, and that is not unreachable`() {
        assertEquals(Reachability.Unknown, tracker.state.reachability)
        assertFalse(tracker.state.unreachable)
        assertFalse(tracker.state.confirmedUnreachable)
    }

    @Test
    fun `an answered request means reachable, without a probe`() {
        answer()

        assertEquals(Reachability.Reachable, tracker.state.reachability)
    }

    @Test
    fun `a failed request asks for a probe and decides nothing itself`() {
        answer()

        assertTrue(fail())

        assertEquals(Reachability.Reachable, tracker.state.reachability)
    }

    @Test
    fun `one failed probe alone shows the banner but does not confirm`() {
        answer()
        clock += 10_000

        tracker.onProbe(reached = false, startedAt = clock)

        assertTrue(tracker.state.unreachable)
        // A slow server looks like this too: the download and trailer buttons stay.
        assertFalse(tracker.state.confirmedUnreachable)
    }

    @Test
    fun `a second failed probe confirms`() {
        tracker.onProbe(reached = false, startedAt = clock)
        clock += 5_000
        tracker.onProbe(reached = false, startedAt = clock)

        assertTrue(tracker.state.confirmedUnreachable)
    }

    @Test
    fun `a failed request and a failed probe confirm at once, in either order`() {
        fail()
        tracker.onProbe(reached = false, startedAt = clock)
        assertTrue(tracker.state.confirmedUnreachable)

        tracker.reset()
        tracker.onProbe(reached = false, startedAt = clock)
        assertFalse(tracker.state.confirmedUnreachable)
        fail()
        assertTrue(tracker.state.confirmedUnreachable)
    }

    @Test
    fun `no network is unreachable and confirmed, with no probe`() {
        answer()

        tracker.onNoNetwork()

        assertTrue(tracker.state.confirmedUnreachable)
    }

    @Test
    fun `failing requests do not keep asking while the server is known to be away`() {
        assertTrue(fail())
        tracker.onProbe(reached = false, startedAt = clock)

        assertFalse(fail())
        assertFalse(fail())
    }

    @Test
    fun `an answer clears unreachable and what confirmed it`() {
        fail()
        tracker.onProbe(reached = false, startedAt = clock)
        assertTrue(tracker.state.confirmedUnreachable)

        answer()
        assertEquals(Reachability.Reachable, tracker.state.reachability)

        // The next time starts from nothing: one slow probe does not confirm.
        clock += 10_000
        tracker.onProbe(reached = false, startedAt = clock)
        assertTrue(tracker.state.unreachable)
        assertFalse(tracker.state.confirmedUnreachable)
    }

    @Test
    fun `a probe that fails after a request was answered meanwhile is dropped`() {
        val probeStartedAt = clock
        clock += 1_000
        answer()
        clock += 2_000

        tracker.onProbe(reached = false, startedAt = probeStartedAt)

        assertEquals(Reachability.Reachable, tracker.state.reachability)
    }

    @Test
    fun `a probe that reaches the server means reachable`() {
        tracker.onNoNetwork()

        tracker.onProbe(reached = true, startedAt = clock)

        assertEquals(Reachability.Reachable, tracker.state.reachability)
        assertFalse(tracker.state.confirmed)
    }

    @Test
    fun `coming to the foreground probes unless the server answered a moment ago`() {
        assertTrue("nothing known", tracker.wantsProbe())

        answer()
        clock += ServerReachabilityTracker.RECENT_ANSWER_MS - 1
        assertFalse(tracker.wantsProbe())

        clock += 1
        assertTrue(tracker.wantsProbe())

        answer()
        tracker.onProbe(reached = false, startedAt = clock + 1)
        assertTrue("unreachable is always worth asking again", tracker.wantsProbe())
    }

    @Test
    fun `retries while unreachable grow to thirty seconds and start over after an answer`() {
        assertEquals(
            listOf(5_000L, 10_000L, 20_000L, 30_000L, 30_000L),
            List(5) { tracker.nextRetryDelayMs() },
        )

        answer()

        assertEquals(5_000L, tracker.nextRetryDelayMs())
    }

    @Test
    fun `a request that is out is waited for since it started`() {
        assertNull(tracker.waitingSince())

        tracker.onRequestStarted()
        assertEquals(1_000L, tracker.waitingSince())

        // A second one does not move the start of the wait.
        clock += 400
        tracker.onRequestStarted()
        assertEquals(1_000L, tracker.waitingSince())
    }

    @Test
    fun `an answer restarts the wait for what is still out, and ends it when nothing is`() {
        tracker.onRequestStarted()
        tracker.onRequestStarted()
        clock += 300

        tracker.onRequestAnswered()
        assertEquals(1_300L, tracker.waitingSince())

        tracker.onRequestAnswered()
        assertNull(tracker.waitingSince())
    }

    @Test
    fun `cancelled and failed requests end the wait too, and a wait is probed once`() {
        tracker.onRequestStarted()
        tracker.onRequestCancelled()
        assertNull(tracker.waitingSince())

        tracker.onRequestStarted()
        tracker.onWaitProbed()
        assertNull(tracker.waitingSince())

        tracker.onRequestFailed()
        tracker.onRequestStarted()
        assertEquals(1_000L, tracker.waitingSince())
    }

    @Test
    fun `nothing is waited for while the server is known to be away`() {
        tracker.onProbe(reached = false, startedAt = clock)

        tracker.onRequestStarted()

        assertNull(tracker.waitingSince())
    }

    @Test
    fun `more ends than starts do not go below zero`() {
        tracker.onRequestCancelled()
        tracker.onRequestCancelled()

        tracker.onRequestStarted()
        assertEquals(1_000L, tracker.waitingSince())
        tracker.onRequestCancelled()
        assertNull(tracker.waitingSince())
    }

    @Test
    fun `another server starts from nothing`() {
        fail()
        tracker.onProbe(reached = false, startedAt = clock)
        tracker.nextRetryDelayMs()

        tracker.reset()

        assertEquals(ServerReachabilityState(), tracker.state)
        assertTrue(tracker.wantsProbe())
        assertEquals(5_000L, tracker.nextRetryDelayMs())
    }
}
