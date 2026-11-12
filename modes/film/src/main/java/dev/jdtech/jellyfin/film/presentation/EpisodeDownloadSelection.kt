package dev.jdtech.jellyfin.film.presentation

import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.isDownloaded
import java.util.UUID

/** What a season or show download adds to the queue, and how many it skipped. */
internal data class EpisodeDownloadSelection(val toQueue: List<FindroidEpisode>, val skipped: Int)

/**
 * The episodes a season or show download queues: those the server allows to be downloaded (#373)
 * that are neither downloaded nor in flight already. The downloaded and in-flight ones count as
 * skipped; the ones the server does not allow are left out.
 */
internal fun selectEpisodesToDownload(
    episodes: List<FindroidEpisode>,
    inFlightIds: Set<UUID>,
): EpisodeDownloadSelection {
    val toQueue = mutableListOf<FindroidEpisode>()
    var skipped = 0
    for (episode in episodes) {
        when {
            episode.isDownloaded() || episode.id in inFlightIds -> skipped++
            episode.canDownload -> toQueue.add(episode)
        }
    }
    return EpisodeDownloadSelection(toQueue, skipped)
}
