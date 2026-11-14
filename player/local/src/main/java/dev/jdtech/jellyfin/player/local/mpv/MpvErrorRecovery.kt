package dev.jdtech.jellyfin.player.local.mpv

import androidx.media3.common.PlaybackException

/**
 * CE (#474): what [MPVPlayer.prepare] does after a playback error, and how a load that fails then
 * is reported.
 *
 * After an error, prepare() loads the item the error was on again, at the position it broke (as
 * ExoPlayer does), instead of loading the whole playlist from its first start position. Only until
 * the playlist is replaced: setMediaItems followed by prepare() is a new load, as before.
 *
 * A load that fails while reloading after a network error is reported as a network error, so the
 * player keeps waiting for the network ([dev.jdtech.jellyfin.player.local.domain.NetworkRecovery])
 * instead of giving up at the first failed retry.
 *
 * CE (#576): so is a first load that fails because the server could not be reached (the next
 * episode of a queue while the network is down), as ExoPlayer reports it: the player waits for the
 * network there too. Any other failed load keeps its plain error (#446).
 *
 * Not thread-safe: [MPVPlayer] calls it on the main thread.
 */
internal class MpvErrorRecovery {

    /** The item the current error is on, until prepare() or a new playlist. */
    private var failedIndex: Int? = null

    /** The current error is a network error. */
    private var networkError = false

    /** The load in progress is a reload after a network error. */
    private var reloadingAfterNetworkError = false

    /** The player reported an error with [errorCode] on the item at [index]. */
    fun onError(index: Int, errorCode: Int) {
        failedIndex = index
        networkError = errorCode in NETWORK_ERRORS
    }

    /** The playlist was replaced, or another item was picked: the next prepare() is a new load. */
    fun forget() {
        failedIndex = null
        networkError = false
        reloadingAfterNetworkError = false
    }

    /**
     * prepare() was called on a playlist of [itemCount] items. Returns the index of the item to
     * load again after an error, or null for a normal prepare.
     */
    fun onPrepare(itemCount: Int): Int? {
        val index = failedIndex?.takeIf { it in 0 until itemCount }
        reloadingAfterNetworkError = index != null && networkError
        failedIndex = null
        networkError = false
        return index
    }

    /** The file mpv was loading opened. */
    fun fileLoaded() {
        reloadingAfterNetworkError = false
    }

    /**
     * The error code for a file that failed to open. [message] is the last error mpv logged for it
     * and [errors] all of them, oldest first.
     */
    fun loadErrorCode(message: String?, errors: List<String> = listOfNotNull(message)): Int =
        when {
            reloadingAfterNetworkError -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            errors.any(::isConnectionError) ->
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            message?.contains("recognize file format") == true ->
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
            else -> PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        }

    companion object {
        private val NETWORK_ERRORS =
            setOf(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            )

        /**
         * #576: ffmpeg's TCP layer could not connect: "ffmpeg: tcp: Connection to tcp://host:8096
         * failed: Connection refused" with the server gone, "... failed: Network is unreachable"
         * without a network, and its lines for a timeout or a host name that does not resolve. An
         * answer from the server ("ffmpeg: http: HTTP error 404 Not Found") is no connection error,
         * as ExoPlayer's bad HTTP status is none.
         */
        fun isConnectionError(error: String): Boolean = error.startsWith("ffmpeg: tcp:")
    }
}
