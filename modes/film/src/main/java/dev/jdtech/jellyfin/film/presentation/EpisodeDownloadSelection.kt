package dev.jdtech.jellyfin.film.presentation

import dev.jdtech.jellyfin.core.presentation.downloader.DownloadQueue
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.isDownloaded
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

/**
 * The episodes already in the queue (pending, downloading or paused). A download counts them as
 * skipped rather than queuing them again: enqueueAll dedupes too, but without this the "Started N
 * downloads" toast would overstate the count.
 */
internal fun DownloadQueue.inFlightIds(): Set<UUID> =
    entries.value
        .filter {
            it.state is DownloadQueue.EntryState.Pending ||
                it.state is DownloadQueue.EntryState.Downloading ||
                it.state is DownloadQueue.EntryState.Paused
        }
        .map { it.id }
        .toSet()

/** CE (#636): the question a season or show download is asking, before it queues anything. */
sealed interface DownloadPrompt {
    /** The quality to download at. */
    data object Quality : DownloadPrompt
}

/**
 * CE (#636): the questions of a season or show download, before it queues anything: the quality.
 * Shared by the Season and Show screens' view models, so their phone and wide layouts ask the same
 * way.
 */
internal class EpisodeDownloadPrompts {
    /** What to queue once the questions are answered. */
    data class Answer(val episodes: List<FindroidEpisode>, val maxBitrate: Int?)

    private var episodes: List<FindroidEpisode> = emptyList()
    private val _prompt = MutableStateFlow<DownloadPrompt?>(null)

    /** The open question, or null when none is. */
    val prompt: StateFlow<DownloadPrompt?> = _prompt.asStateFlow()

    /** Starts the questions for a download of [episodes]. */
    fun start(episodes: List<FindroidEpisode>) {
        this.episodes = episodes
        _prompt.value = DownloadPrompt.Quality
    }

    /** The answer to [DownloadPrompt.Quality]: the download to queue, or null if none was asked. */
    fun chooseQuality(maxBitrate: Int?): Answer? {
        if (_prompt.value != DownloadPrompt.Quality) return null
        val answer = Answer(episodes, maxBitrate)
        dismiss()
        return answer
    }

    /** Closes the open question without downloading anything. */
    fun dismiss() {
        _prompt.value = null
        episodes = emptyList()
    }
}
