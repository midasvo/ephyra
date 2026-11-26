package dev.jdtech.jellyfin.core.presentation.downloader

import dev.jdtech.jellyfin.models.DownloadFiles
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.EpisodeField
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.repository.CurrentAccount
import dev.jdtech.jellyfin.repository.CurrentServerId
import dev.jdtech.jellyfin.repository.DownloadList
import dev.jdtech.jellyfin.repository.DownloadSettings
import dev.jdtech.jellyfin.repository.DownloadSources
import dev.jdtech.jellyfin.repository.DownloadUserData
import dev.jdtech.jellyfin.repository.ItemCatalog
import dev.jdtech.jellyfin.repository.PlaybackEvents
import dev.jdtech.jellyfin.repository.StoredSource
import dev.jdtech.jellyfin.utils.Downloader
import dev.jdtech.jellyfin.utils.NetworkConnectivity
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Smart Downloads: keeps a few unwatched episodes of a series you are watching downloaded, and
 * removes the ones Smart Downloads queued itself once you have watched them. What to queue and
 * remove is decided by [planSmartDownloads]; this class gathers its input and carries it out.
 *
 * It runs after playback stops and after the sync that pushes offline progress, so episodes watched
 * offline or on another device are covered too.
 */
@Singleton
class SmartDownloads
@Inject
constructor(
    private val downloadQueue: DownloadQueue,
    private val downloader: Downloader,
    private val settings: DownloadSettings,
    private val currentServerId: CurrentServerId,
    private val downloadList: DownloadList,
    private val downloadSources: DownloadSources,
    private val downloadUserData: DownloadUserData,
    private val catalog: Provider<ItemCatalog>,
    private val account: Provider<CurrentAccount>,
    private val networkConnectivity: NetworkConnectivity,
    private val playbackEvents: PlaybackEvents,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var started = false

    /** Starts reacting to playback stopping. Safe to call more than once. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            playbackEvents.stopped.collect { itemId ->
                try {
                    onPlaybackStopped(itemId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(
                        e,
                        "Smart Downloads: failed to handle the stop of ${itemId.asString()}",
                    )
                }
            }
        }
    }

    private suspend fun onPlaybackStopped(itemId: ItemId) {
        if (!isActive()) return
        val episode = catalog.get().getItem(itemId) as? Episode ?: return
        check(episode.seriesId, finishedRecently = episode.played)
    }

    /** Runs [check] for every series that has a completed download. */
    suspend fun checkAll() {
        if (!isActive()) return
        // CE (#718): the current server's series; another server's downloads are not its to manage.
        val serverId = currentServerId.get() ?: return
        for (seriesId in downloadList.seriesIdsWithCompletedDownloads(serverId)) {
            check(seriesId, finishedRecently = false)
        }
    }

    /**
     * Tops up [seriesId] and removes what has been watched.
     *
     * @param finishedRecently whether an episode of the series was just finished, which is what
     *   makes a series count as being watched.
     */
    suspend fun check(seriesId: ItemId, finishedRecently: Boolean) {
        if (!isActive()) return
        mutex.withLock {
            try {
                run(seriesId, finishedRecently)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Smart Downloads: check failed for series ${seriesId.asString()}")
            }
        }
    }

    /** Smart Downloads needs the server to say what is watched, so it never runs offline. */
    private fun isActive(): Boolean = settings.smartDownloads && networkConnectivity.isOnline()

    private suspend fun run(seriesId: ItemId, finishedRecently: Boolean) {
        // Smart Downloads that was already on before the moment was recorded: only what is watched
        // from now on may be removed.
        var since = settings.smartDownloadsSince
        if (since == 0L) {
            since = System.currentTimeMillis()
            settings.smartDownloadsSince = since
        }

        val catalog = this.catalog.get()
        val userId = account.get().getUserId()
        val episodes =
            catalog.getSeasons(seriesId).flatMap { season ->
                // Without CanDownload every episode reads as not downloadable; Trickplay keeps
                // Smart Downloads from having to re-fetch each episode to get its trickplay layout.
                catalog.getEpisodes(
                    seriesId,
                    season.id,
                    fields = setOf(EpisodeField.CAN_DOWNLOAD, EpisodeField.TRICKPLAY),
                )
            }

        // The server decides what counts as watched, so wait until our own progress has reached
        // it. The sync that pushes it runs a check afterwards.
        if (episodes.any { downloadUserData.hasUserDataToBeSynced(userId, it.id) }) {
            Timber.d(
                "Smart Downloads: unsynced progress for series ${seriesId.asString()}, waiting"
            )
            return
        }

        // CE (#718): only this server's downloads count as downloaded here.
        val serverId = currentServerId.get() ?: return
        val sourcesByEpisode = episodes.associate { episode ->
            episode.id to
                downloadSources.sourcesOfServer(episode.id, serverId).filter {
                    it.type == ItemSourceType.LOCAL
                }
        }
        val completed =
            sourcesByEpisode
                .mapValues { (_, sources) -> sources.filter { !DownloadFiles.isPartial(it.path) } }
                .filterValues { it.isNotEmpty() }
        val downloads = completed.mapValues { (_, sources) ->
            SmartDownload(
                downloadedAt = sources.first().downloadedAt,
                smartQueued = sources.first().smartQueued,
            )
        }
        val inFlightIds =
            downloadQueue.entries.value
                .filter {
                    it.state !is DownloadQueue.EntryState.Completed &&
                        it.state !is DownloadQueue.EntryState.Failed
                }
                .map { it.id }
                .toSet()

        val plan =
            planSmartDownloads(
                episodes = episodes,
                downloads = downloads,
                inFlightIds = inFlightIds,
                keepReady = settings.smartDownloadsKeepReady.coerceAtLeast(1),
                since = since,
                finishedRecently = finishedRecently,
                playingItemId = playbackEvents.playingItemId.value,
            )

        Timber.d(
            "Smart Downloads: series ${seriesId.asString()}, queue ${plan.toQueue.map { it.describe() }}, remove ${plan.toDelete.map { it.describe() }}"
        )

        for (episode in plan.toDelete) {
            // The player may have opened it since the plan was made.
            if (playbackEvents.playingItemId.value == episode.id) continue
            val source = completed[episode.id]?.firstOrNull() ?: continue
            Timber.i("Smart Downloads: removing watched ${episode.describe()}")
            downloader.deleteItem(episode, downloadSources.itemSource(source))
        }

        if (plan.toQueue.isEmpty()) return
        val maxBitrate = qualityOf(sourcesByEpisode.values.flatten())
        for (episode in plan.toQueue) {
            Timber.i("Smart Downloads: queueing ${episode.describe()}")
            downloadQueue.enqueue(episode, maxBitrate, smartQueued = true)
        }
    }

    /**
     * The quality of the most recent download of the series, or of any source of it. Null, which
     * means original quality, when there is none: there is no default download quality to use.
     */
    private fun qualityOf(sources: List<StoredSource>): Int? {
        val latest =
            sources.filter { it.downloadedAt != null }.maxByOrNull { it.downloadedAt ?: 0L }
                ?: sources.firstOrNull()
        return latest?.downloadMaxBitrate
    }

    private fun Episode.describe() = "$seriesName S%02dE%02d".format(parentIndexNumber, indexNumber)
}
