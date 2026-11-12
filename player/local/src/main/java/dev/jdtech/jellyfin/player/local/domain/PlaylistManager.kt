package dev.jdtech.jellyfin.player.local.domain

import androidx.core.net.toUri
import androidx.media3.common.C
import dev.jdtech.jellyfin.models.FindroidChapter
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.FindroidSources
import dev.jdtech.jellyfin.player.core.domain.models.ExternalSubtitle
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.core.domain.models.ServerAudioTrack
import dev.jdtech.jellyfin.player.core.domain.models.TrickplayInfo
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import timber.log.Timber

class PlaylistManager @Inject internal constructor(private val repository: JellyfinRepository) {
    private var startItem: FindroidItem? = null
    private var items: List<FindroidItem> = emptyList()
    private val playerItems: MutableList<PlayerItem> = mutableListOf()
    var currentItemIndex: Int = 0

    private var currentItemId: UUID? = null

    /**
     * Fetches the full episode list for the queue. Set when the first item could start playing
     * without it (an episode already knows its season); run by [loadQueue].
     */
    private var queueLoader: (suspend () -> List<FindroidItem>)? = null
    private val queueMutex = Mutex()

    /**
     * CE (#580): the audio stream each item was last requested with, by the server's index. The
     * stream of a transcode does not say which track it carries, so a quality switch from one
     * transcode to another has only this to keep the track.
     */
    private val requestedAudio = HashMap<UUID, Int>()

    private suspend fun fetchEpisodes(seriesId: UUID, seasonId: UUID): List<FindroidEpisode> =
        repository
            .getEpisodes(
                seriesId = seriesId,
                seasonId = seasonId,
                fields = listOf(ItemFields.CHAPTERS, ItemFields.TRICKPLAY),
            )
            .filter { !it.missing }

    /**
     * CE (#594): [partial] as the server has it in full, with its trickplay and chapters. If that
     * fails, [partial] itself: playback without thumbnails is better than none.
     */
    private suspend fun fullEpisode(partial: FindroidEpisode): FindroidEpisode =
        try {
            repository.getEpisode(partial.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e("Failed to retrieve episode ${partial.id}: $e")
            partial
        }

    /**
     * Resolves the item to start playing. For a movie or episode, the item and its playback
     * sources are fetched concurrently, and the episode list for the queue is not fetched at all:
     * call [loadQueue] after the player is prepared (the neighbour lookups also wait for it).
     */
    suspend fun getInitialItem(
        itemId: UUID,
        itemKind: BaseItemKind,
        mediaSourceId: String? = null,
        startFromBeginning: Boolean = false,
        shuffle: Boolean = false,
    ): PlayerItem? {
        Timber.d("Retrieving initial player item")

        queueLoader = null
        // Playback sources fetched while the item itself was still loading.
        var prefetchedSources: List<FindroidSource>? = null

        val initialItem =
            when (itemKind) {
                BaseItemKind.MOVIE -> {
                    val (movie, sources) =
                        coroutineScope {
                            val movie = async { repository.getMovie(itemId) }
                            val sources = async { repository.getPlaybackSources(itemId, mediaSourceId) }
                            movie.await() to sources.await()
                        }
                    prefetchedSources = sources

                    items = listOf(movie)
                    movie
                }
                BaseItemKind.SERIES -> {
                    if (shuffle) {
                        val allSeasons = repository.getSeasons(itemId)
                        val seasons = allSeasons.filter { it.indexNumber != 0 }.ifEmpty { allSeasons }
                        val episodes =
                            seasons
                                .flatMap { season -> fetchEpisodes(itemId, season.id) }
                                .shuffled()

                        if (episodes.isEmpty()) {
                            return null
                        }

                        val episode = episodes.first()
                        items = episodes
                        episode
                    } else {
                        val nextUpEpisode = repository.getNextUp(itemId).firstOrNull()

                        if (nextUpEpisode != null) {
                            // CE (#594): next-up answers without trickplay and chapters (Home's
                            // rows do not need them), so the episode itself is fetched, next to its
                            // sources. The season's list only fills the queue.
                            val (episode, sources) =
                                coroutineScope {
                                    val episode = async { fullEpisode(nextUpEpisode) }
                                    val sources = async {
                                        repository.getPlaybackSources(nextUpEpisode.id, mediaSourceId)
                                    }
                                    episode.await() to sources.await()
                                }
                            prefetchedSources = sources
                            val seasonId = episode.seasonId
                            items = listOf(episode)
                            queueLoader = { fetchEpisodes(itemId, seasonId) }
                            episode
                        } else {
                            val seasons = repository.getSeasons(itemId)
                            if (seasons.isEmpty()) {
                                return null
                            }

                            val episodes = fetchEpisodes(itemId, seasons.first().id)
                            if (episodes.isEmpty()) {
                                return null
                            }

                            items = episodes
                            episodes.first()
                        }
                    }
                }
                BaseItemKind.SEASON -> {
                    val season = repository.getSeason(itemId)
                    val episodes = fetchEpisodes(season.seriesId, season.id)

                    if (episodes.isEmpty()) {
                        return null
                    }

                    val episode = episodes.first()

                    items = episodes
                    episode
                }
                BaseItemKind.EPISODE -> {
                    val (episode, sources) =
                        coroutineScope {
                            val episode = async { repository.getEpisode(itemId) }
                            val sources = async { repository.getPlaybackSources(itemId, mediaSourceId) }
                            episode.await() to sources.await()
                        }
                    prefetchedSources = sources

                    items = listOf(episode)
                    queueLoader = { fetchEpisodes(episode.seriesId, episode.seasonId) }
                    episode
                }
                else -> null
            }

        if (initialItem == null) {
            return null
        }

        startItem = initialItem
        currentItemId = initialItem.id

        currentItemIndex = items.indexOfFirst { it.id == initialItem.id }

        val playbackPosition =
            if (shuffle || startFromBeginning) 0L else initialItem.playbackPositionTicks.div(10000)
        val playerItem = initialItem.toPlayerItem(mediaSourceId, playbackPosition, prefetchedSources)
        playerItems.add(playerItem)

        return playerItem
    }

    /**
     * Fetches the episode list that [getInitialItem] left out, so the queue (previous and next
     * episodes) works. Safe to call any number of times, also concurrently: it loads once. A
     * failure leaves the queue as just the playing item, and the next call tries again.
     */
    suspend fun loadQueue() {
        queueMutex.withLock {
            val loader = queueLoader ?: return
            try {
                val episodes = loader()
                items = episodes
                currentItemIndex = items.indexOfFirst { it.id == currentItemId }
                queueLoader = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e("Failed to retrieve the episode queue: $e")
            }
        }
    }

    suspend fun getPreviousPlayerItem(): PlayerItem? {
        Timber.d("Retrieving previous player item")

        loadQueue()
        val itemIndex = currentItemIndex - 1
        val playerItem =
            when (startItem) {
                is FindroidMovie -> null
                is FindroidEpisode -> {
                    if (currentItemIndex == 0) {
                        null
                    } else {
                        val item = items[itemIndex]
                        if (playerItems.firstOrNull { it.itemId == item.id } == null) {
                            try {
                                item.toPlayerItem(null, 0L)
                            } catch (e: Exception) {
                                Timber.e("Failed to retrieve previous player item: $e")
                                null
                            }
                        } else {
                            null
                        }
                    }
                }
                else -> null
            }

        if (playerItem != null) {
            playerItems.add(playerItem)
        }

        return playerItem
    }

    suspend fun getNextPlayerItem(): PlayerItem? {
        Timber.d("Retrieving next player item")

        loadQueue()
        val itemIndex = currentItemIndex + 1
        val playerItem =
            when (startItem) {
                is FindroidMovie -> null
                is FindroidEpisode -> {
                    if (currentItemIndex == items.lastIndex) {
                        appendNextSeason()
                    }

                    if (currentItemIndex == items.lastIndex) {
                        null
                    } else {
                        val item = items[itemIndex]
                        if (playerItems.firstOrNull { it.itemId == item.id } == null) {
                            try {
                                item.toPlayerItem(null, 0L)
                            } catch (e: Exception) {
                                Timber.e("Failed to retrieve next player item: $e")
                                null
                            }
                        } else {
                            null
                        }
                    }
                }
                else -> null
            }

        if (playerItem != null) {
            playerItems.add(playerItem)
        }

        return playerItem
    }

    /**
     * Queue the episodes of the next season, so playback continues into it. Seasons are fetched
     * only when the current one runs out. Specials (season 0) do not roll over into season 1.
     */
    private suspend fun appendNextSeason() {
        val queuedEpisodes = items.filterIsInstance<FindroidEpisode>()
        val seriesId = queuedEpisodes.lastOrNull()?.seriesId ?: return

        try {
            val seasons = repository.getSeasons(seriesId).sortedBy { it.indexNumber }
            // The highest queued season, not the last episode's: the server can end a season's
            // list with a special that airs after it, and that episode belongs to season 0.
            val queuedSeasonIds = queuedEpisodes.map { it.seasonId }
            val seasonNumber =
                seasons.lastOrNull { it.id in queuedSeasonIds }?.indexNumber ?: return
            if (seasonNumber == 0) {
                return
            }

            val nextEpisodes =
                seasons
                    .filter { it.indexNumber > seasonNumber }
                    .firstNotNullOfOrNull { season ->
                        repository
                            .getEpisodes(
                                seriesId = seriesId,
                                seasonId = season.id,
                                fields = listOf(ItemFields.CHAPTERS, ItemFields.TRICKPLAY),
                            )
                            .filter { !it.missing }
                            .ifEmpty { null }
                    } ?: return

            items = items + nextEpisodes
        } catch (e: Exception) {
            Timber.e("Failed to retrieve next season: $e")
        }
    }

    fun setCurrentMediaItemIndex(itemId: UUID) {
        currentItemId = itemId
        currentItemIndex = items.indexOfFirst { it.id == itemId }
    }

    /** CE (#131): the queued items after the current one, for the TV player's queue row. */
    fun upcomingItems(): List<FindroidItem> =
        if (currentItemIndex < 0) emptyList() else items.drop(currentItemIndex + 1)

    /**
     * CE (#131): start over at [itemId], a queued item, from its beginning. Forgets which items the
     * player already has, as the caller replaces the player's playlist with the returned item.
     */
    suspend fun jumpTo(itemId: UUID): PlayerItem? {
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0) return null
        val playerItem = items[index].toPlayerItem(null, 0L)
        playerItems.clear()
        playerItems.add(playerItem)
        currentItemIndex = index
        return playerItem
    }

    /**
     * Re-resolve an already-queued item's media sources — used when the user
     * changes the max streaming bitrate mid-playback, which invalidates every
     * queued stream URL. The cached entry is replaced (not just added) so the
     * duplicate check in [getPreviousPlayerItem]/[getNextPlayerItem] keeps
     * treating the item as already queued.
     *
     * CE (#580): [selectedAudio] is the audio track playing now, when the stream names it (a
     * transcode's single track does not). It goes to the server with the request, so a transcode
     * the switch leads to carries the same track.
     *
     * CE (#596): [audioStreamIndex] is the track the user picked in the audio dialog of a
     * transcode, by the server's index ([ServerAudioTrack.index]). It wins over everything else.
     */
    suspend fun recreatePlayerItem(
        playerItem: PlayerItem,
        selectedAudio: StoredChoice? = null,
        audioStreamIndex: Int? = null,
    ): PlayerItem {
        val item = items.firstOrNull { it.id == playerItem.itemId } ?: return playerItem
        val recreated =
            item.toPlayerItem(
                playerItem.mediaSourceId,
                playerItem.playbackPosition,
                selectedAudio = selectedAudio,
                audioStreamIndex = audioStreamIndex,
            )
        playerItems.removeAll { it.itemId == playerItem.itemId }
        playerItems.add(recreated)
        return recreated
    }

    private suspend fun FindroidItem.toPlayerItem(
        mediaSourceId: String?,
        playbackPosition: Long,
        prefetchedSources: List<FindroidSource>? = null,
        selectedAudio: StoredChoice? = null,
        audioStreamIndex: Int? = null,
    ): PlayerItem {
        Timber.d("Converting FindroidItem ${this.id} to PlayerItem")

        // A downloaded copy shares its id with the server version, so it wins for that version too.
        val mediaSources =
            (prefetchedSources ?: repository.getPlaybackSources(id, mediaSourceId)).let { sources ->
                sources.filter { it.id == mediaSourceId }.ifEmpty { sources }
            }
        // Never an unfinished download (#454): its partial file plays as far as it got, or not at
        // all. With no other source (offline) there is nothing to play: the exception carries no
        // message, so the player shows its own error instead of a raw one.
        val local =
            mediaSources.firstOrNull {
                it.type == FindroidSourceType.LOCAL && !it.path.endsWith(".download")
            }
        val remote =
            if (local != null) {
                null
            } else {
                mediaSources
                    .firstOrNull { it.type != FindroidSourceType.LOCAL }
                    ?.let { withChosenAudio(it, selectedAudio, audioStreamIndex) }
            }
        val mediaSource = local ?: remote?.source ?: throw NoSuchElementException()
        val externalSubtitles =
            subtitleFiles(mediaSource).map {
                ExternalSubtitle(
                    title = it.title,
                    language = it.language,
                    uri = it.path.toUri(),
                    mimeType = it.mimeType,
                    embedded = it.embedded,
                    forced = it.forced,
                    default = it.default,
                )
            }
        val trickplayInfo =
            when (this) {
                is FindroidSources -> {
                    this.trickplayInfo?.get(mediaSource.id)?.let {
                        TrickplayInfo(
                            width = it.width,
                            height = it.height,
                            tileWidth = it.tileWidth,
                            tileHeight = it.tileHeight,
                            thumbnailCount = it.thumbnailCount,
                            interval = it.interval,
                            bandwidth = it.bandwidth,
                        )
                    }
                }
                else -> null
            }
        return PlayerItem(
            name = name,
            itemId = id,
            mediaSourceId = mediaSource.id,
            mediaSourceUri = mediaSource.path,
            isTranscoded = mediaSource.transcoded,
            audioTracks =
                if (remote != null && mediaSource.transcoded) {
                    serverAudioTracks(mediaSource.mediaStreams)
                } else {
                    emptyList()
                },
            audioStreamIndex = remote?.audioStreamIndex,
            playbackPosition = playbackPosition,
            parentIndexNumber = if (this is FindroidEpisode) parentIndexNumber else null,
            indexNumber = if (this is FindroidEpisode) indexNumber else null,
            indexNumberEnd = if (this is FindroidEpisode) indexNumberEnd else null,
            externalSubtitles = externalSubtitles,
            chapters = chapters.toPlayerChapters(),
            trickplayInfo = trickplayInfo,
            seriesName = if (this is FindroidEpisode) seriesName else null,
            seriesId = if (this is FindroidEpisode) seriesId else null,
            seasonId = if (this is FindroidEpisode) seasonId else null,
            artworkUri = images.primary ?: images.showPrimary,
        )
    }

    /** A server source, and the audio stream the server put in it when it is a transcode. */
    private class RemoteSource(val source: FindroidSource, val audioStreamIndex: Int?)

    /**
     * CE (#580): a transcode carries one audio track, the server's default unless the request
     * names another. So when [source] is a transcode and the user's track is not that default,
     * the source is asked for once more, with the track. In order: the track picked in the audio
     * dialog of a transcode ([pickedIndex], #596), the track playing now ([selectedAudio], on a
     * quality switch), the choice saved for the series, the track this item was last requested
     * with. A source that plays directly is returned as it is: the player selects among its
     * tracks itself.
     */
    private suspend fun FindroidItem.withChosenAudio(
        source: FindroidSource,
        selectedAudio: StoredChoice?,
        pickedIndex: Int?,
    ): RemoteSource {
        if (!source.transcoded) return RemoteSource(source, null)
        val default = RemoteSource(source, source.defaultAudioStreamIndex)
        val index =
            pickedIndex?.takeIf { picked ->
                serverAudioTracks(source.mediaStreams).any { it.index == picked }
            }
                ?: selectedAudio?.let { audioStreamIndex(it, source.mediaStreams) }
                ?: savedAudioChoice()?.let { audioStreamIndex(it, source.mediaStreams) }
                ?: requestedAudio[id]
                ?: return default
        requestedAudio[id] = index
        if (index == source.defaultAudioStreamIndex) return default
        return try {
            repository
                .getPlaybackSources(id, source.id, audioStreamIndex = index)
                .firstOrNull { it.id == source.id && it.type != FindroidSourceType.LOCAL }
                ?.let { RemoteSource(it, index) } ?: default
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The default track is better than no playback.
            Timber.e("Failed to request audio stream $index: $e")
            default
        }
    }

    /** The audio track saved for this episode's series, or null: a movie has none. */
    private suspend fun FindroidItem.savedAudioChoice(): StoredChoice? {
        if (this !is FindroidEpisode) return null
        val preference =
            try {
                repository.getSeriesTrackPreferences(seriesId).firstOrNull {
                    it.trackType == C.TRACK_TYPE_AUDIO && !it.disabled
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e("Failed to read the series' track choice: $e")
                null
            } ?: return null
        return StoredChoice(preference.language, preference.title, preference.positionInLanguage)
    }

    private fun List<FindroidChapter>.toPlayerChapters(): List<PlayerChapter> {
        return this.map { chapter ->
            PlayerChapter(
                startPosition = chapter.startPosition,
                name = chapter.name,
                imageTag = chapter.imageTag,
            )
        }
    }
}
