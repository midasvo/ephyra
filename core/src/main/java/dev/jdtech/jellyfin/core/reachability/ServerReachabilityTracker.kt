package dev.jdtech.jellyfin.core.reachability

/** Whether the server in use answers. [Unknown] until something says so, and shown as reachable. */
enum class Reachability {
    Unknown,
    Reachable,
    Unreachable,
}

/**
 * CE (#281): what the UI reads.
 *
 * [confirmed] is false while one failed probe is all there is: a server that was only slow to
 * answer the probe shows the banner for a moment, but the download and trailer buttons stay until
 * more says the server is gone (no network, a request of the app that failed too, or a second
 * failed probe).
 */
data class ServerReachabilityState(
    val reachability: Reachability = Reachability.Unknown,
    val confirmed: Boolean = false,
) {
    val unreachable: Boolean
        get() = reachability == Reachability.Unreachable

    /** Unreachable for sure: what needs the server (downloading, trailers) is left out. */
    val confirmedUnreachable: Boolean
        get() = unreachable && confirmed
}

/**
 * CE (#281): the rules of server reachability, without Android or coroutines.
 *
 * The app's own requests carry most of it: an answered one means reachable, with no request made to
 * find that out. A probe is only asked for when something suggests a change (a request that failed
 * or stays unanswered, a network change, the app coming to the foreground, the user's Retry), and
 * while the server is unreachable, in growing steps.
 *
 * Not thread-safe: the caller serialises the calls.
 */
class ServerReachabilityTracker(private val now: () -> Long) {
    private var reachability = Reachability.Unknown
    private var failedProbes = 0
    private var requestFailed = false
    private var noNetwork = false
    private var lastAnswerAt: Long? = null
    private var retryStep = 0
    private var inFlight = 0
    private var waitingSince: Long? = null

    val state: ServerReachabilityState
        get() =
            ServerReachabilityState(
                reachability = reachability,
                confirmed =
                    reachability == Reachability.Unreachable &&
                        (noNetwork || requestFailed || failedProbes >= CONFIRMING_PROBES),
            )

    /** A request of the app went out. */
    fun onRequestStarted() {
        inFlight++
        if (waitingSince == null) waitingSince = now()
    }

    /** The server answered a request of the app. */
    fun onRequestAnswered() {
        requestEnded()
        reachable()
        // What is still out has been waiting since this answer at most.
        waitingSince = if (inFlight > 0) now() else null
    }

    /**
     * A request of the app got no answer. True when a probe should say whether the server is gone;
     * false when that is known already, so failing screens do not keep asking.
     */
    fun onRequestFailed(): Boolean {
        requestEnded()
        requestFailed = true
        return reachability != Reachability.Unreachable
    }

    /** The app gave up on a request: nothing learnt. */
    fun onRequestCancelled() = requestEnded()

    /**
     * Since when requests have been out without any answer, or null when none is waiting or the
     * server is known to be away. A request that waits longer than [SLOW_REQUEST_MS] is worth a
     * probe: the probe gives up after 3 s, the request itself only after its connect timeout.
     */
    fun waitingSince(): Long? = waitingSince.takeIf { reachability != Reachability.Unreachable }

    /** The wait was handed to a probe: one probe per wait. */
    fun onWaitProbed() {
        waitingSince = null
    }

    /**
     * Whether a sign that proves nothing by itself (the app in the foreground) is worth a probe:
     * not when the server answered a request a moment ago.
     */
    fun wantsProbe(): Boolean {
        if (reachability != Reachability.Reachable) return true
        val answeredAt = lastAnswerAt ?: return true
        return now() - answeredAt >= RECENT_ANSWER_MS
    }

    /**
     * The result of a probe started at [startedAt]. A failed probe is dropped when a request was
     * answered after it started: the server is there, the probe was slow.
     */
    fun onProbe(reached: Boolean, startedAt: Long) {
        if (reached) {
            reachable()
            return
        }
        val answeredAt = lastAnswerAt
        if (answeredAt != null && answeredAt >= startedAt) return
        failedProbes++
        noNetwork = false
        reachability = Reachability.Unreachable
    }

    /** The device has no network: unreachable, and nothing to probe. */
    fun onNoNetwork() {
        noNetwork = true
        reachability = Reachability.Unreachable
    }

    /** Another server, or the Offline Mode setting: nothing is known. */
    fun reset() {
        reachability = Reachability.Unknown
        failedProbes = 0
        requestFailed = false
        noNetwork = false
        lastAnswerAt = null
        retryStep = 0
        waitingSince = null
    }

    /** How long to wait before the next probe while unreachable. Grows up to the last step. */
    fun nextRetryDelayMs(): Long {
        val delay = RETRY_STEPS_MS[retryStep.coerceAtMost(RETRY_STEPS_MS.lastIndex)]
        retryStep++
        return delay
    }

    private fun requestEnded() {
        if (inFlight > 0) inFlight--
        if (inFlight == 0) waitingSince = null
    }

    private fun reachable() {
        reachability = Reachability.Reachable
        failedProbes = 0
        requestFailed = false
        noNetwork = false
        lastAnswerAt = now()
        retryStep = 0
    }

    companion object {
        /** An answer this recent makes a probe on returning to the app pointless. */
        const val RECENT_ANSWER_MS = 5_000L

        /** A request unanswered for this long gets a probe beside it. */
        const val SLOW_REQUEST_MS = 1_500L

        /** Failed probes in a row that confirm what the first one said. */
        const val CONFIRMING_PROBES = 2

        val RETRY_STEPS_MS = listOf(5_000L, 10_000L, 20_000L, 30_000L)
    }
}
