package dev.jdtech.jellyfin.player.local.domain

import androidx.media3.common.PlaybackException

/**
 * CE (#450): what the player does when a stream breaks because the connection went. ExoPlayer
 * stops with a network error once its buffer runs out and stays stopped; this waits for the
 * network and tells the player to load again, at the same position and with the same play/pause
 * state, as pressing Play would.
 *
 * Only for a player that can load again with `prepare()` (`COMMAND_PREPARE`): ExoPlayer, and mpv
 * since #474.
 *
 * The caller retries when a network with internet access becomes available, and every
 * [RETRY_INTERVAL_MS] while one is up but loading still fails. After [GIVE_UP_MS] without
 * success the normal error stays, with Play as the manual retry. An item change, leaving the
 * player or playback picking up again ends the wait.
 */
class NetworkRecovery(private val nowMs: () -> Long) {

    enum class OnError {
        /** Not a network error, or the player can't load again: show the error as before. */
        SHOW_ERROR,
        /** A new wait for the network began: show the waiting state. */
        WAIT,
        /** A retry failed again; keep waiting. */
        KEEP_WAITING,
        /** Waited [GIVE_UP_MS] without success: the wait is over, show the error as before. */
        GIVE_UP,
    }

    /** True while waiting for the network to come back. */
    var isWaiting: Boolean = false
        private set

    private var mediaId: String? = null
    private var startedAtMs = 0L

    /**
     * The player reported [errorCode] on the item [mediaId] (null when nothing is loaded).
     * [canPrepare]: the player can load the item again (`COMMAND_PREPARE`).
     */
    fun onError(errorCode: Int, mediaId: String?, canPrepare: Boolean): OnError {
        val recoverable = errorCode in NETWORK_ERRORS && mediaId != null && canPrepare
        if (!recoverable) {
            val wasWaiting = isWaiting
            stop()
            return if (wasWaiting) OnError.GIVE_UP else OnError.SHOW_ERROR
        }
        if (isWaiting && this.mediaId == mediaId) {
            if (nowMs() - startedAtMs >= GIVE_UP_MS) {
                stop()
                return OnError.GIVE_UP
            }
            return OnError.KEEP_WAITING
        }
        isWaiting = true
        this.mediaId = mediaId
        startedAtMs = nowMs()
        return OnError.WAIT
    }

    enum class Check {
        /** Load again now (when the network allows). */
        RETRY,
        /** Waited [GIVE_UP_MS] without success: the wait is over, show the error as before. */
        GIVE_UP,
        /** Not waiting (any more): the item changed, or playback picked up again. */
        STOPPED,
    }

    /** What to do when the network comes back or the retry timer fires, on [currentMediaId]. */
    fun check(currentMediaId: String?): Check {
        if (!isWaiting) return Check.STOPPED
        if (currentMediaId != mediaId) {
            stop()
            return Check.STOPPED
        }
        if (nowMs() - startedAtMs >= GIVE_UP_MS) {
            stop()
            return Check.GIVE_UP
        }
        return Check.RETRY
    }

    /** Playback picked up again, the item changed or the player is closing: no more retries. */
    fun stop() {
        isWaiting = false
        mediaId = null
    }

    companion object {
        const val RETRY_INTERVAL_MS = 15_000L
        const val GIVE_UP_MS = 10 * 60_000L

        val NETWORK_ERRORS =
            setOf(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            )
    }
}
