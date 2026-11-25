package dev.jdtech.jellyfin.player.local.engine.exo

import androidx.media3.common.PlaybackException
import timber.log.Timber

/**
 * Turns ExoPlayer's tunneled playback off for an item whose audio track the tunnel output refuses
 * (#924).
 *
 * `EngineDefaults` asks for tunneling, and ExoPlayer takes it when both renderers say they support
 * it. A tunneled AudioTrack needs the device's HW A/V sync output, and that output need not take
 * every format: on an Amlogic TV box it only has stereo, 5.1 and 7.1 channel masks, so a mono track
 * gets no output (`getOutputForAttr() return error -38`) and ExoPlayer fails with
 * `ERROR_CODE_AUDIO_TRACK_INIT_FAILED` instead of falling back. Without tunneling the same track
 * goes to the normal output.
 *
 * Tunneling stays off for the rest of that item, also across a quality or audio switch (the same
 * media id), and comes back on when another item plays: the next episode may well have a format the
 * tunnel takes.
 *
 * Called on the player's application thread.
 *
 * @param tunnelingEnabled whether the track selector asks for tunneling now.
 * @param setTunnelingEnabled changes that; ExoPlayer selects tracks again on the next prepare.
 */
internal class TunnelingFallback(
    private val tunnelingEnabled: () -> Boolean,
    private val setTunnelingEnabled: (Boolean) -> Unit,
) {
    /** Whether this fallback turned tunneling off, and for which media id. */
    private var turnedOff = false
    private var turnedOffFor: String? = null

    /**
     * Turns tunneling off when an audio track failed to open while tunneling was on.
     *
     * @param errorCode the [PlaybackException.errorCode] of the error.
     * @param mediaId the media id of the item that failed.
     * @return `true` if preparing the player again (at the same position) can recover. Only once
     *   per item: with tunneling off, the same error goes the normal error path.
     */
    fun onPlayerError(errorCode: Int, mediaId: String?): Boolean {
        if (errorCode != PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return false
        if (!tunnelingEnabled()) return false
        Timber.w("Tunneling failed to open an audio track, retrying without tunneling")
        setTunnelingEnabled(false)
        turnedOff = true
        turnedOffFor = mediaId
        return true
    }

    /** Turns tunneling back on when an item other than the one it was turned off for plays. */
    fun onMediaItemTransition(mediaId: String?) {
        if (!turnedOff || mediaId == turnedOffFor) return
        Timber.i("Tunneling back on for the next item")
        turnedOff = false
        turnedOffFor = null
        setTunnelingEnabled(true)
    }
}
