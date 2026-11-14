package dev.jdtech.jellyfin.player.local.domain

/** CE (#298): what the sleep timer is set to. */
sealed interface SleepTimerOption {
    data object Off : SleepTimerOption

    /** Pause after [minutes] of wall-clock time, whether or not something plays meanwhile. */
    data class Duration(val minutes: Int) : SleepTimerOption

    /** Pause when the current item ends, instead of starting the next one. */
    data object EndOfItem : SleepTimerOption

    companion object {
        private val DURATIONS = listOf(15, 30, 45, 60, 90)

        /**
         * The choices the player offers, in order. [debug] (debuggable builds only) adds a
         * one-minute duration, for testing.
         */
        fun choices(debug: Boolean): List<SleepTimerOption> = buildList {
            add(Off)
            if (debug) add(Duration(1))
            DURATIONS.forEach { add(Duration(it)) }
            add(EndOfItem)
        }
    }
}

/**
 * CE (#298): the player's sleep timer. Lives as long as one player session and is never stored.
 *
 * Pure: no Android, no media3, no coroutines. The caller passes a monotonic wall clock (one that
 * keeps counting while paused and while the device sleeps) and polls [isDue].
 */
class SleepTimer {
    var option: SleepTimerOption = SleepTimerOption.Off
        private set

    private var deadlineMs = 0L
    private var itemId: String? = null

    /**
     * Set once the timer fired, until playback resumes: automatic playback must not start the next
     * item meanwhile.
     */
    var holdsAutoPlay = false
        private set

    /** Set the timer to [option], starting now, for the item [itemId] that is playing. */
    fun set(option: SleepTimerOption, nowMs: Long, itemId: String?) {
        this.option = option
        this.itemId = itemId
        deadlineMs =
            if (option is SleepTimerOption.Duration) nowMs + option.minutes * 60_000L else 0L
    }

    fun clear() {
        option = SleepTimerOption.Off
        holdsAutoPlay = false
    }

    /** Time left on a duration timer, or `null` when it is not one. */
    fun remainingMs(nowMs: Long): Long? =
        if (option is SleepTimerOption.Duration) (deadlineMs - nowMs).coerceAtLeast(0L) else null

    /** True when a duration timer has run out. */
    fun isDue(nowMs: Long): Boolean = option is SleepTimerOption.Duration && nowMs >= deadlineMs

    /** True when the end of the current item must pause instead of going on to the next one. */
    val stopsAtEndOfItem: Boolean
        get() = option == SleepTimerOption.EndOfItem || holdsAutoPlay

    /** The timer went off: it is over, and autoplay waits until the user resumes. */
    fun fire() {
        option = SleepTimerOption.Off
        holdsAutoPlay = true
    }

    /** Playback resumed (the user pressed play): autoplay works as before. */
    fun onResumed() {
        holdsAutoPlay = false
    }

    /**
     * The player moved to item [newItemId]. A duration keeps running; a timer for the end of the
     * item it was set on is over, since that item is left. Returns true when the timer changed.
     */
    fun onItemChanged(newItemId: String?): Boolean {
        if (option != SleepTimerOption.EndOfItem || newItemId == itemId) return false
        option = SleepTimerOption.Off
        return true
    }
}
