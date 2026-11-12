package dev.jdtech.jellyfin.presentation.cast.components

import android.content.Context
import androidx.annotation.StringRes
import dev.jdtech.jellyfin.models.FindroidSegment
import dev.jdtech.jellyfin.models.FindroidSegmentType
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter
import dev.jdtech.jellyfin.player.local.R as PlayerR
import dev.jdtech.jellyfin.settings.R as SettingsR
import java.util.Locale

/**
 * Returns the string resource ID for the localized label of a [FindroidSegmentType], or null if
 * unlabeled.
 */
val FindroidSegmentType.labelRes: Int?
    @StringRes
    get() =
        when (this) {
            FindroidSegmentType.INTRO -> SettingsR.string.media_segments_type_intro
            FindroidSegmentType.OUTRO -> SettingsR.string.media_segments_type_outro
            FindroidSegmentType.RECAP -> SettingsR.string.media_segments_type_recap
            FindroidSegmentType.PREVIEW -> SettingsR.string.media_segments_type_preview
            FindroidSegmentType.COMMERCIAL -> SettingsR.string.media_segments_type_commercial
            FindroidSegmentType.UNKNOWN -> null
        }

/**
 * Computes a user-facing label for the media position, combining chapter name and segment label if
 * present.
 */
fun getSectionTitle(
    posMs: Long,
    chapters: List<PlayerChapter>,
    segments: List<FindroidSegment>,
    context: Context,
): String? {
    val chapterIndex = chapters.indexOfLast { posMs >= it.startPosition }
    val chapter = if (chapterIndex >= 0) chapters[chapterIndex] else null
    val segment = segments.firstOrNull { posMs in it.startTicks..it.endTicks }
    val segmentLabel = segment?.type?.labelRes?.let { context.getString(it) }

    val chapterLabel =
        if (chapter != null) {
            if (!chapter.name.isNullOrBlank()) {
                chapter.name
            } else {
                context.getString(PlayerR.string.chapter_number, chapterIndex + 1)
            }
        } else {
            null
        }

    return when {
        chapterLabel != null && segmentLabel != null -> {
            if (chapterLabel.equals(segmentLabel, ignoreCase = true)) {
                val fallback = context.getString(PlayerR.string.chapter_number, chapterIndex + 1)
                "$fallback • $segmentLabel"
            } else if (chapterLabel.contains(segmentLabel, ignoreCase = true)) {
                chapterLabel
            } else {
                "$chapterLabel • $segmentLabel"
            }
        }
        chapterLabel != null -> chapterLabel
        segmentLabel != null -> segmentLabel
        else -> null
    }
}

/** Formats a duration in milliseconds to standard digital clock format (e.g. "04:15" or "1:22:45"). */
fun formatDuration(millis: Long): String {
    val durationSeconds = (millis.coerceAtLeast(0L) / 1000)
    val hours = durationSeconds / 3600
    val minutes = (durationSeconds % 3600) / 60
    val seconds = durationSeconds % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}

/** Calculates a progress fraction (0.0f ... 1.0f) given an X coordinate and component width. */
fun calculateProgressFromX(x: Float, width: Float): Float {
    if (width <= 0f) return 0f
    return (x / width).coerceIn(0f, 1f)
}
