package dev.jdtech.jellyfin

/**
 * Whether the player's PiP params enable auto-enter (Android 12+), which makes Home enter PiP.
 *
 * Only with the PiP home gesture on. The PiP button's params stay with the activity after the
 * window is expanded again, so auto-enter in them would make Home enter PiP for the rest of the
 * session with the gesture off (#447).
 */
internal fun pipAutoEnterEnabled(gestureEnabled: Boolean, isPlaying: Boolean): Boolean =
    gestureEnabled && isPlaying

/**
 * CE (#480): whether the player gives the system new PiP params. When auto-enter differs from the
 * params it last gave ([autoEnterSet], off when none were given), so a gesture setting changed
 * while the player was open takes effect, and when [refreshAnyway] (a play/pause change with the
 * gesture on, which keeps the aspect ratio up to date as before).
 */
internal fun pipParamsNeedUpdate(
    autoEnterSet: Boolean,
    autoEnter: Boolean,
    refreshAnyway: Boolean,
): Boolean = refreshAnyway || autoEnter != autoEnterSet
