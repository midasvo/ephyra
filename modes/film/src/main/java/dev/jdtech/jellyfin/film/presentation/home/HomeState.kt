package dev.jdtech.jellyfin.film.presentation.home

import dev.jdtech.jellyfin.models.HomeItem
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.User

data class HomeState(
    val server: Server? = null,
    val user: User? = null,
    val suggestionsSection: HomeItem.Suggestions? = null,
    val resumeSection: HomeItem.Section? = null,
    val nextUpSection: HomeItem.Section? = null,
    val views: List<HomeItem.ViewItem> = emptyList(),
    // CE (#142): see HomeRowsOrder.kt.
    val rowOrder: List<String> = emptyList(),
    val isLoading: Boolean = true,
    val error: Exception? = null,
    /** CE: offline, and this server has no downloads, so Home is empty for that reason. */
    val nothingDownloaded: Boolean = false,
    /**
     * CE (#608): the cards of Continue Watching and Next Up show the episode's own image instead
     * of the series' or movie's thumb.
     */
    val useEpisodeImages: Boolean = false,
) {
    val isEmpty: Boolean
        get() =
            suggestionsSection == null &&
                resumeSection == null &&
                nextUpSection == null &&
                views.isEmpty()
}
