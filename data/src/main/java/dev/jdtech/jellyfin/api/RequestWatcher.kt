package dev.jdtech.jellyfin.api

/**
 * CE (#281): how the app's requests to the server go, for whoever keeps track of whether the server
 * answers. Every [onStarted] is followed by exactly one of the other three. The calls come on the
 * request's own thread: return at once.
 */
interface RequestWatcher {
    fun onStarted()

    /** The server answered, with whatever status below 500. */
    fun onAnswered()

    /** No answer (no route, refused, timed out), or a proxy answered with a server error. */
    fun onUnanswered()

    /** The app gave up on the request itself, which says nothing about the server. */
    fun onCancelled()
}
