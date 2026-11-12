package dev.jdtech.jellyfin.player.local.mpv

/**
 * CE (#446): whether mpv's END_FILE means the current file failed to load.
 *
 * The JNI layer passes no END_FILE reason. A file that ends without reaching FILE_LOADED, and that
 * this player did not replace, remove or stop, failed to load. mpv's error lines are no signal of
 * their own: libmpv hands out a pending event before pending log messages, so the error that
 * explains the failure can arrive after END_FILE.
 *
 * CE (#576):
 * - One failure is reported once. After a failed open mpv goes on to the next playlist entries by
 *   itself, and has usually failed on those too before the first END_FILE gets here. Their
 *   START_FILE and END_FILE say nothing new: a failure holds until this player starts a load
 *   itself ([newLoad]).
 * - An END_FILE that comes while mpv is on the entry this player asked for belongs to an earlier
 *   file: one that mpv tried after a failure and that is only delivered after the retry, or the
 *   first entry of a new playlist that mpv had started to open before it was told to play another.
 * - A failure without an error line yet is held ([End.HOLD]) until the first line arrives or the
 *   caller's timer runs out, so that the report can say what went wrong.
 *
 * Not thread-safe: [MPVPlayer] calls it on the main thread.
 */
internal class MpvLoadState {

    /** Whether mpv got as far as FILE_LOADED for the file it started last. */
    var fileLoaded = false
        private set

    /** This player replaced, removed or stopped the current file, so its END_FILE is no failure. */
    var endFileExpected = false
        private set

    /** The errors mpv logged since it started the current file, oldest first. */
    private val errors = mutableListOf<String>()

    /**
     * A failure was found and this player has not started a load since. True from END_FILE on, so
     * also while the report is held: what mpv does until then is not this player's doing.
     */
    var failed = false
        private set

    /** The failure waits for its first error line. */
    private var holding = false

    /** The playlist entry this player last told mpv to play, when it named one. */
    private var wantedIndex: Int? = null

    enum class End {
        /** Not a load failure, or one that was reported before. */
        NOT_A_FAILURE,
        /** The file failed to load: report [failure]. */
        FAILED,
        /**
         * The file failed to load, but mpv's error lines are still on their way. Report when
         * [errorLogged] returns [Logged.COMPLETES_FAILURE], or when [holdTimedOut] returns true.
         */
        HOLD,
    }

    enum class Logged {
        /** Kept for the report, if the current file fails. */
        KEPT,
        /** The line the held failure waited for: report [failure] now. */
        COMPLETES_FAILURE,
        /** The failure was reported already, so the line only belongs in the log. */
        AFTER_FAILURE,
    }

    fun startFile() {
        // After a failure mpv starts the next entries by itself; they are not this player's load.
        if (failed) return
        fileLoaded = false
        endFileExpected = false
        errors.clear()
    }

    fun fileLoaded() {
        if (failed) return
        fileLoaded = true
    }

    /** This player stops mpv on the current file. */
    fun expectEndFile() {
        endFileExpected = true
    }

    /**
     * This player takes over: it loads a playlist or another entry, or stops. The END_FILE of the
     * current file is no failure, an earlier failure is over and a held one is dropped. [index] is
     * the entry mpv was told to play, or null when the command names none.
     */
    fun newLoad(index: Int?) {
        endFileExpected = true
        failed = false
        holding = false
        errors.clear()
        wantedIndex = index
    }

    /** mpv logged [message] at error level. */
    fun errorLogged(message: String): Logged {
        if (holding) {
            holding = false
            errors.add(message)
            return Logged.COMPLETES_FAILURE
        }
        if (failed) return Logged.AFTER_FAILURE
        if (errors.size < MAX_ERRORS) errors.add(message)
        return Logged.KEPT
    }

    /**
     * mpv ended a file. [playingIndex] asks mpv which playlist entry it is on now (null or negative
     * for none); it is only called when the answer decides.
     */
    fun endFile(playingIndex: () -> Int?): End {
        if (failed || fileLoaded || endFileExpected) return End.NOT_A_FAILURE
        // mpv leaves an entry that failed, so while it is on the wanted one this end is an older
        // file's.
        val wanted = wantedIndex
        if (wanted != null && playingIndex() == wanted) return End.NOT_A_FAILURE
        failed = true
        if (errors.isEmpty()) {
            holding = true
            return End.HOLD
        }
        return End.FAILED
    }

    /** The caller's timer for [End.HOLD] ran out. Returns true when the failure is still held. */
    fun holdTimedOut(): Boolean {
        if (!holding) return false
        holding = false
        return true
    }

    /** The failure to report: the last error mpv logged for the file, and all of them. */
    val failure: Failure
        get() = Failure(errors.lastOrNull(), errors.toList())

    /** [message] is null when mpv logged no error for the file. */
    data class Failure(val message: String?, val errors: List<String> = listOfNotNull(message))

    companion object {
        /** How long a failure waits for its error line. They came 20 to 40 ms after END_FILE. */
        const val HOLD_MS = 300L

        private const val MAX_ERRORS = 20
    }
}
