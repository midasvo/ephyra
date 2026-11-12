package dev.jdtech.jellyfin.player.local.domain

/**
 * CE (#601): when an item's playback start report goes out. Not before the player has loaded the
 * item: the server counts a start as a play, and marks an item without a runtime played at its
 * first position report. An item that never opens must send nothing.
 *
 * Every item transition owes one start, sent at the first ready state after it: a new item, and
 * also a transition to the same item (ExoPlayer reports one when a quality switch replaces the
 * playlist), as before. A ready state without a transition (rebuffering, the reload after a lost
 * connection) owes nothing, as before. Only the moment changes: the transition, then the ready
 * state, and never for an item that does not get there.
 *
 * Not thread-safe: called from the player's listener and the main thread only.
 */
class PlaybackStartGate<T : Any> {
    private var pending: T? = null

    /**
     * A transition to [item]. Returns [item] when its start is due now, because the player is
     * already [ready] (an auto-advance to an item that buffered ahead). Otherwise holds it for
     * [onReady], replacing an item that was left before it loaded.
     *
     * [current] is false when the player has already moved on from [item] by the time the
     * transition is handled (it waits for the previous item's stop): mpv's quality switch passes
     * through the item before the current one. Nothing is owed for it, and the start held for the
     * item the player moved on to stays.
     */
    fun onTransition(item: T, ready: Boolean, current: Boolean = true): T? {
        if (!current) return null
        pending = if (ready) null else item
        return item.takeIf { ready }
    }

    /** The player is ready. Returns the item whose start is due, once, or `null`. */
    fun onReady(): T? = pending.also { pending = null }

    /** The player closed: no start is owed any more. */
    fun clear() {
        pending = null
    }
}
