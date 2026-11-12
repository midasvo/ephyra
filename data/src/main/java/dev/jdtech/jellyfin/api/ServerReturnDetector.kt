package dev.jdtech.jellyfin.api

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Notices the server coming back from the app's own requests: after a request that did not reach
 * it, or that a proxy in front of it answered with a server error, the first answer from the
 * server itself. No request is made for this.
 */
class ServerReturnDetector {
    private val failing = AtomicBoolean(false)

    /** A request got no answer (no route, refused, timed out). */
    fun onFailure() = failing.set(true)

    /** A request was answered with [code]. True when this is the server coming back. */
    fun onResponse(code: Int): Boolean {
        if (code >= HTTP_SERVER_ERROR) {
            failing.set(true)
            return false
        }
        return failing.compareAndSet(true, false)
    }

    private companion object {
        const val HTTP_SERVER_ERROR = 500
    }
}
