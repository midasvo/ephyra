package dev.jdtech.jellyfin.player.local.mpv

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters

/**
 * CE (#617): the subtitle track id (`sid`) that [parameters] explicitly asks for across a
 * `sub-add` command:
 * - `no` when text tracks are disabled by [parameters].
 * - an override's format id when one is explicitly set in [parameters].
 * - null when track selection is automatic, letting mpv's automatic track selection choose the best
 *   matching subtitle (by language preferences such as `slang`) across all added tracks.
 */
internal fun targetSubtitleTrack(parameters: TrackSelectionParameters): String? {
    if (C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes) return "no"
    val override = parameters.overrides.entries.firstOrNull { it.key.type == C.TRACK_TYPE_TEXT }
    return override?.key?.getFormat(0)?.id
}
