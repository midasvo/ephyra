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
 * CE (#636): the question a season or show download asks when some of its episodes are watched:
 * queue all [allCount] episodes, or only the [unwatchedCount] unwatched ones.
 */
data class EpisodeDownloadChoice(val allCount: Int, val unwatchedCount: Int)

/**
 * The episodes a season or show download queues: those the server allows to be downloaded (#373)
 * that are neither downloaded nor in flight already. The downloaded and in-flight ones count as
 * skipped; the ones the server does not allow are left out.
 *
 * CE (#636): with [unwatchedOnly], watched episodes are left out too. They do not count as skipped,
 * because the user chose to leave them out. An episode watched halfway is not `played`, so it is
 * unwatched and still queued.
 */
internal fun selectEpisodesToDownload(
    episodes: List<FindroidEpisode>,
    inFlightIds: Set<UUID>,
    unwatchedOnly: Boolean = false,
): EpisodeDownloadSelection {
    val toQueue = mutableListOf<FindroidEpisode>()
    var skipped = 0
    for (episode in episodes) {
        when {
            episode.isDownloaded() || episode.id in inFlightIds -> skipped++
            unwatchedOnly && episode.played -> Unit
            episode.canDownload -> toQueue.add(episode)
        }
    }
    return EpisodeDownloadSelection(toQueue, skipped)
}

/**
 * CE (#636): the choice to offer before queuing [episodes], or null when there is nothing to ask
 * because none of the episodes the download would queue are watched.
 */
internal fun downloadChoiceFor(
    episodes: List<FindroidEpisode>,
    inFlightIds: Set<UUID>,
): EpisodeDownloadChoice? {
    val all = selectEpisodesToDownload(episodes, inFlightIds).toQueue.size
    val unwatched =
        selectEpisodesToDownload(episodes, inFlightIds, unwatchedOnly = true).toQueue.size
    return if (unwatched < all) EpisodeDownloadChoice(allCount = all, unwatchedCount = unwatched)
    else null
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
    /** Download all episodes, or only the unwatched ones. Asked only when some are watched. */
    data class Episodes(val choice: EpisodeDownloadChoice) : DownloadPrompt

    /** The quality to download at, asked last. */
    data object Quality : DownloadPrompt
}

/**
 * CE (#636): the questions of a season or show download, in order: which episodes (only when some
 * are watched), then the quality. Shared by the Season and Show screens' view models.
 */
internal class EpisodeDownloadPrompts {
    /** What to queue once both questions are answered. */
    data class Answer(
        val episodes: List<FindroidEpisode>,
        val maxBitrate: Int?,
        val unwatchedOnly: Boolean,
    )

    private var episodes: List<FindroidEpisode> = emptyList()
    private var unwatchedOnly = false
    private val _prompt = MutableStateFlow<DownloadPrompt?>(null)

    /** The open question, or null when none is. */
    val prompt: StateFlow<DownloadPrompt?> = _prompt.asStateFlow()

    /** Starts the questions for a download of [episodes]. */
    fun start(episodes: List<FindroidEpisode>, inFlightIds: Set<UUID>) {
        this.episodes = episodes
        unwatchedOnly = false
        val choice = downloadChoiceFor(episodes, inFlightIds)
        _prompt.value =
            if (choice != null) DownloadPrompt.Episodes(choice) else DownloadPrompt.Quality
    }

    /** The answer to [DownloadPrompt.Episodes]; the quality is asked next. */
    fun chooseEpisodes(unwatchedOnly: Boolean) {
        if (_prompt.value !is DownloadPrompt.Episodes) return
        this.unwatchedOnly = unwatchedOnly
        _prompt.value = DownloadPrompt.Quality
    }

    /** The answer to [DownloadPrompt.Quality]: the download to queue, or null if none was asked. */
    fun chooseQuality(maxBitrate: Int?): Answer? {
        if (_prompt.value != DownloadPrompt.Quality) return null
        val answer = Answer(episodes, maxBitrate, unwatchedOnly)
        dismiss()
        return answer
    }

    /** Closes the open question without downloading anything. */
    fun dismiss() {
        _prompt.value = null
        episodes = emptyList()
        unwatchedOnly = false
    }
}
