package dev.jdtech.jellyfin.player.local.mpv

import androidx.media3.common.C
import androidx.media3.common.Tracks

/** One video track as mpv lists it: whether it is selected and its container frame rate. */
internal data class VideoTrackCandidate(val isSelected: Boolean, val frameRate: Float)

/**
 * The frame rate of the selected video track, or of the first video track when none is selected, or
 * null when it is unknown. mpv lists attached pictures (cover art) as extra 1fps video tracks, so
 * taking the last video group would hint the display with 1fps (#338).
 */
internal fun selectedVideoFrameRate(candidates: List<VideoTrackCandidate>): Float? {
    val candidate =
        candidates.firstOrNull { it.isSelected } ?: candidates.firstOrNull() ?: return null
    return candidate.frameRate.takeIf { it > 0f && !it.isNaN() }
}

internal fun selectedVideoFrameRate(tracks: Tracks): Float? =
    selectedVideoFrameRate(
        tracks.groups
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group ->
                (0 until group.length).map { i ->
                    VideoTrackCandidate(group.isTrackSelected(i), group.getTrackFormat(i).frameRate)
                }
            }
    )
