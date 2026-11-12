package dev.jdtech.jellyfin.settings.domain

import dev.jdtech.jellyfin.settings.domain.models.Preference

/**
 * [value] within the range [preference] allows. The settings screen saves this and shows this, so
 * it never shows a value the app does not use (#559: a prompt timeout of 0 s that waited 5 s).
 */
fun AppPreferences.bounded(preference: Preference<Int>, value: Int): Int =
    when (preference) {
        subtitleFontScale ->
            value.coerceIn(
                Constants.SubtitleStyle.FONT_SCALE_MIN,
                Constants.SubtitleStyle.FONT_SCALE_MAX,
            )
        smartDownloadsKeepReady -> value.coerceAtLeast(1)
        stillWatchingPromptTimeoutSeconds ->
            value.coerceAtLeast(Constants.StillWatching.PROMPT_TIMEOUT_MIN_SECONDS)
        else -> value
    }
