package dev.jdtech.jellyfin.film.presentation.show

import dev.jdtech.jellyfin.film.presentation.RelatedItems
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItemPerson
import dev.jdtech.jellyfin.models.FindroidSeason
import dev.jdtech.jellyfin.models.FindroidShow
import java.util.UUID

data class SeasonDownloadInfo(
    val downloadedCount: Int = 0,
    val totalCount: Int = 0,
    /** Whether the server allows at least one of the season's episodes to be downloaded (#373). */
    val canDownload: Boolean = false,
)

data class ShowState(
    val show: FindroidShow? = null,
    val nextUp: FindroidEpisode? = null,
    val seasons: List<FindroidSeason> = emptyList(),
    val seasonDownloadInfo: Map<UUID, SeasonDownloadInfo> = emptyMap(),
    val actors: List<FindroidItemPerson> = emptyList(),
    val director: FindroidItemPerson? = null,
    val writers: List<FindroidItemPerson> = emptyList(),
    val hasDownloads: Boolean = false,
    // CE (#141): the TV show page's theme song, null when off or the server has none.
    val themeSongUrl: String? = null,
    // CE (#332): loaded after the page is shown, see ShowViewModel.loadRelated.
    val related: RelatedItems = RelatedItems(),
    val error: Exception? = null,
) {
    /**
     * Whether the page offers Download (#373): known once the series' episodes are in, so a page
     * without them (the request failed, or offline) offers none.
     */
    val canDownload: Boolean
        get() = seasonDownloadInfo.values.any { it.canDownload }

    /** The seasons the season picker of a show download lists. */
    val downloadableSeasons: List<FindroidSeason>
        get() = seasons.filter { seasonDownloadInfo[it.id]?.canDownload == true }
}
