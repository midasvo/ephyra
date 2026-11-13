package dev.jdtech.jellyfin.core.reachability

import dev.jdtech.jellyfin.api.RequestWatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * CE (#281): runs the probes that [ServerReachabilityTracker] asks for and publishes the state.
 *
 * Nothing waits for a probe: every entry point returns at once and the answer arrives in [state].
 * One probe runs at a time, and it ends after [probeTimeoutMs] whatever the network does.
 *
 * @param probe true when the server answered, false when it did not, null when no server is set
 * @param hasNetwork whether the device has a network at all
 * @param enabled false while the Offline Mode setting is on: then nothing is probed or shown
 */
class ServerReachabilityMonitor(
    private val scope: CoroutineScope,
    private val probe: suspend () -> Boolean?,
    private val hasNetwork: () -> Boolean,
    private val enabled: () -> Boolean,
    private val now: () -> Long,
    private val probeTimeoutMs: Long,
    private val settleMs: Long = NETWORK_SETTLE_MS,
    private val slowRequestMs: Long = ServerReachabilityTracker.SLOW_REQUEST_MS,
) : RequestWatcher {
    private val tracker = ServerReachabilityTracker(now)
    private val lock = Any()

    private val _state = MutableStateFlow(ServerReachabilityState())
    val state: StateFlow<ServerReachabilityState> = _state.asStateFlow()

    private val _serverConfirmed =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /**
     * Emits when it is worth sending the server what is waiting for it: a probe reached it, or the
     * app came to the foreground and the server answered a moment ago. While Offline Mode is on
     * nothing is probed, and the foreground and network signs are passed on as they come.
     * (The server answering a request again after failed ones is `JellyfinApi.serverReturned`.)
     */
    val serverConfirmed: SharedFlow<Unit> = _serverConfirmed.asSharedFlow()

    private var probing = false

    /** Counts the servers asked about: a probe's answer only counts for the one it asked. */
    private var server = 0
    private var retryJob: Job? = null
    private var slowRequestJob: Job? = null
    private var probeAgain = false
    private var foreground = false

    override fun onStarted() {
        synchronized(lock) {
            tracker.onRequestStarted()
            if (slowRequestJob?.isActive != true && enabled()) {
                slowRequestJob = scope.launch { watchSlowRequest() }
            }
        }
    }

    override fun onAnswered() {
        synchronized(lock) {
            tracker.onRequestAnswered()
            if (enabled()) publish()
        }
    }

    override fun onUnanswered() {
        val wantsProbe =
            synchronized(lock) {
                val wanted = tracker.onRequestFailed()
                if (enabled()) publish()
                wanted && enabled()
            }
        if (wantsProbe) requestProbe()
    }

    override fun onCancelled() {
        synchronized(lock) { tracker.onRequestCancelled() }
    }

    /**
     * A request that stays unanswered gets one probe beside it: the probe gives up after
     * [probeTimeoutMs], the request only after its own, longer timeouts.
     */
    private suspend fun watchSlowRequest() {
        while (true) {
            val wait =
                synchronized(lock) {
                    val since = tracker.waitingSince() ?: return
                    val left = since + slowRequestMs - now()
                    if (left <= 0) tracker.onWaitProbed()
                    left
                }
            if (wait <= 0) break
            delay(wait)
        }
        requestProbe()
    }

    fun onForeground() {
        val wantsProbe =
            synchronized(lock) {
                foreground = true
                val enabled = enabled()
                if (!enabled) {
                    // Offline Mode was switched on: what was known is not shown or kept.
                    tracker.reset()
                    publish()
                }
                enabled && tracker.wantsProbe()
            }
        // Without a probe (the server answered a moment ago, or Offline Mode is on and nothing
        // is probed) the sign itself is passed on, as before #281.
        if (wantsProbe) requestProbe() else _serverConfirmed.tryEmit(Unit)
    }

    fun onBackground() {
        synchronized(lock) {
            foreground = false
            retryJob?.cancel()
        }
    }

    /** The default network changed or went away. Waits a moment, as such changes come in bursts. */
    fun onNetworkChanged() = requestProbe(settle = true)

    /** The user asked (Retry, pull to refresh). */
    fun check() = requestProbe()

    /** Another server or user: what was known is about the old one. */
    fun reset() {
        synchronized(lock) {
            server++
            tracker.reset()
            publish()
        }
        // A probe under way asked the old server: ask again after it.
        requestProbe(again = true)
    }

    /**
     * Starts a probe unless one is running. With [again] a running probe is followed by another:
     * it may have gone out over the old network, or to the old server.
     */
    private fun requestProbe(settle: Boolean = false, again: Boolean = settle) {
        synchronized(lock) {
            if (probing) {
                if (again) probeAgain = true
                return
            }
            probing = true
        }
        scope.launch {
            var wait = settle
            while (true) {
                if (wait) delay(settleMs)
                runProbe()
                wait = true
                // Decided under the lock, so a request that arrives now is either seen here or
                // starts a probe of its own.
                val more =
                    synchronized(lock) {
                        if (probeAgain) {
                            probeAgain = false
                            true
                        } else {
                            probing = false
                            false
                        }
                    }
                if (!more) break
            }
        }
    }

    private suspend fun runProbe() {
        if (!enabled()) {
            synchronized(lock) {
                tracker.reset()
                publish()
            }
            _serverConfirmed.tryEmit(Unit)
            return
        }
        if (!hasNetwork()) {
            synchronized(lock) {
                tracker.onNoNetwork()
                publish()
            }
            return
        }
        val startedAt = now()
        val askedServer = synchronized(lock) { server }
        // Boxed, so "no server set" (null) is told apart from the timeout.
        val result =
            try {
                withTimeoutOrNull(probeTimeoutMs) { ProbeResult(probe()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Whatever went wrong, the server was not reached.
                null
            }
        val reached = if (result == null) false else result.reached
        synchronized(lock) {
            // The server changed meanwhile: this answer is about the old one.
            if (askedServer != server) return
            if (reached == null) {
                tracker.reset()
            } else {
                tracker.onProbe(reached, startedAt)
                if (reached) _serverConfirmed.tryEmit(Unit)
            }
            publish()
        }
    }

    /** Call with [lock] held. */
    private fun publish() {
        val state = tracker.state
        _state.value = state
        if (!state.unreachable) {
            retryJob?.cancel()
            retryJob = null
        } else if (foreground && retryJob?.isActive != true) {
            val delayMs = tracker.nextRetryDelayMs()
            retryJob =
                scope.launch {
                    delay(delayMs)
                    synchronized(lock) { retryJob = null }
                    requestProbe()
                }
        }
    }

    private class ProbeResult(val reached: Boolean?)

    companion object {
        const val NETWORK_SETTLE_MS = 250L
    }
}
