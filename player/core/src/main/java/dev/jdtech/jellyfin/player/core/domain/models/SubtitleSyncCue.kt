package dev.jdtech.jellyfin.player.core.domain.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * CE (#619): Represents a parsed subtitle dialogue cue used for real-time subtitle sync alignment
 * and cue matching.
 */
@Parcelize
data class SubtitleSyncCue(
    val startTimeMs: Long,
    val endTimeMs: Long = startTimeMs + 3_000L,
    val text: String,
) : Parcelable
