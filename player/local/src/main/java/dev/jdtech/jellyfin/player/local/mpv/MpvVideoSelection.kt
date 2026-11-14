package dev.jdtech.jellyfin.player.local.mpv

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters

/**
 * CE (#460): the value of mpv's `vid` that [parameters] ask for, the same way
 * [MPVPlayer.setTrackSelectionParameters] selects it: an override's track id, `no` when video is
 * disabled (background audio), else `auto`. [MPVPlayer] deselects video while it has no surface and
 * selects this again when the surface comes back.
 */
internal fun videoTrackSelection(parameters: TrackSelectionParameters): String {
    val override = parameters.overrides.entries.firstOrNull { it.key.type == C.TRACK_TYPE_VIDEO }
    override?.key?.getFormat(0)?.id?.let {
        return it
    }
    return if (C.TRACK_TYPE_VIDEO in parameters.disabledTrackTypes) "no" else "auto"
}
