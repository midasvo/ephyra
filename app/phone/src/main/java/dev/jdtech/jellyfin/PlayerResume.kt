package dev.jdtech.jellyfin

/**
 * The playWhenReady to give back to the player when its screen returns (not from PiP), or null
 * to leave the player as it is.
 *
 * Leaving the screen pauses the player only when it does not play on in the background, and only
 * that pause is undone here, with [saved], the playWhenReady from before it. With background audio
 * the player kept its own state while the screen was away, and that state may have changed since:
 * the still-watching prompt or the sleep timer paused it, or it was paused from the notification.
 * Giving back [saved] played it again, and at the end of an item that prompted once more (#477).
 */
internal fun playWhenReadyToRestore(pausedOnLeave: Boolean, saved: Boolean): Boolean? =
    if (pausedOnLeave) saved else null
