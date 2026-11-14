package dev.jdtech.jellyfin.ce.presentation.player

/**
 * CE (#462): what the TV player does when the app comes back (Home and back, standby and wake). The
 * screen pauses the player on ON_PAUSE; on ON_RESUME it plays again if it was playing, as the
 * phone's BasePlayerActivity does, or shows the controls if it was paused, so the paused state can
 * be seen.
 *
 * The decision is taken at the first ON_PAUSE since the last ON_RESUME, so a second ON_PAUSE cannot
 * overwrite "was playing" with the pause the screen made itself. Anything else that changes the
 * play state while the app is away (a play/pause from the remote or the media session, the
 * still-watching timeout) or the sleep timer firing cancels the resume: the return then leaves the
 * play state alone and only shows the controls if the player is not playing.
 */
internal class TvResumeOnReturn {

    enum class Action {
        /** Nothing to do: no pause to undo, or a dialog is up. */
        NONE,
        /** It was playing before: play again. */
        PLAY,
        /**
         * It was paused already, or someone else took over the play state: show the controls if it
         * is not playing, so the paused state is visible.
         */
        SHOW_CONTROLS,
    }

    private enum class State {
        RESUME_PLAYING,
        SHOW_PAUSED,
        CANCELLED,
    }

    private var state: State? = null

    /** The screen pauses the player next; this is the pause it expects to see. */
    private var expectOwnPause = false

    fun onPause(playWhenReady: Boolean) {
        if (state != null) return
        state = if (playWhenReady) State.RESUME_PLAYING else State.SHOW_PAUSED
        expectOwnPause = playWhenReady
    }

    /** The player's playWhenReady changed (any reason). Ignored while the app is in front. */
    fun onPlayWhenReadyChanged(playWhenReady: Boolean) {
        if (state == null) return
        if (expectOwnPause && !playWhenReady) {
            expectOwnPause = false
            return
        }
        cancel()
    }

    /** Something took over the play state while the app was away (the sleep timer fired). */
    fun cancel() {
        if (state != null) state = State.CANCELLED
    }

    /**
     * The app is back. [blocked]: a dialog that must not have playback start under it is up (the
     * still-watching prompt, an error).
     */
    fun onResume(blocked: Boolean): Action {
        val s = state
        state = null
        expectOwnPause = false
        return when {
            s == null || blocked -> Action.NONE
            s == State.RESUME_PLAYING -> Action.PLAY
            else -> Action.SHOW_CONTROLS
        }
    }
}
