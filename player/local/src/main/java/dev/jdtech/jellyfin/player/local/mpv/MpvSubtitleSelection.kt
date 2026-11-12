package dev.jdtech.jellyfin.player.local.mpv

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters

/**
 * CE (#617): the subtitle track id (`sid`) that [parameters] and [currentSid] ask for across a
 * `sub-add` command:
 * - `no` when text tracks are disabled by [parameters].
 * - an override's format id when one is set in [parameters].
 * - [currentSid] when a track was already selected (`currentSid` not null, not "no", not "auto").
 * - null when track selection was automatic and no track was selected yet, letting mpv's auto
 *   selection choose the newly added subtitle if it matches language preferences.
 */
internal fun targetSubtitleTrack(
    parameters: TrackSelectionParameters,
    currentSid: String?,
): String? {
    if (C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes) return "no"
    val override = parameters.overrides.entries.firstOrNull { it.key.type == C.TRACK_TYPE_TEXT }
    override?.key?.getFormat(0)?.id?.let {
        return it
    }
    if (currentSid != null && currentSid != "no" && currentSid != "auto") {
        return currentSid
    }
    return null
}
