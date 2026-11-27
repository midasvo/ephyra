package dev.jdtech.jellyfin.film.presentation.show

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.domain.ports.CurrentServerCapabilities
import dev.jdtech.jellyfin.film.presentation.DownloadPrompt
import dev.jdtech.jellyfin.film.presentation.EpisodeDownloadPrompts
import dev.jdtech.jellyfin.film.presentation.ExternalRatingsLoader
import dev.jdtech.jellyfin.film.presentation.RelatedItems
import dev.jdtech.jellyfin.film.presentation.RelatedItemsRequest
import dev.jdtech.jellyfin.film.presentation.detailSections
import dev.jdtech.jellyfin.film.presentation.inFlightIds
import dev.jdtech.jellyfin.film.presentation.keeping
import dev.jdtech.jellyfin.film.presentation.launchRelatedItems
import dev.jdtech.jellyfin.film.presentation.ratingsLookup
import dev.jdtech.jellyfin.film.presentation.selectEpisodesToDownload
import dev.jdtech.jellyfin.film.presentation.shownIn
import dev.jdtech.jellyfin.film.presentation.within
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.EpisodeField
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemPerson
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.PersonKind
import dev.jdtech.jellyfin.models.SeasonEpisodeCounts
import dev.jdtech.jellyfin.models.Show
import dev.jdtech.jellyfin.models.mergeDuplicateRoles
import dev.jdtech.jellyfin.ratings.ExternalRatings
import dev.jdtech.jellyfin.repository.DataChangeEvents
import dev.jdtech.jellyfin.repository.DownloadDeleter
import dev.jdtech.jellyfin.repository.DownloadList
import dev.jdtech.jellyfin.repository.DownloadQueuePort
import dev.jdtech.jellyfin.repository.ItemCatalog
import dev.jdtech.jellyfin.repository.ItemExtras
import dev.jdtech.jellyfin.repository.ReloadPolicy
import dev.jdtech.jellyfin.repository.UserDataUpdates
import dev.jdtech.jellyfin.repository.serverOrStored
import dev.jdtech.jellyfin.repository.serverOrStoredList
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.TimberFallbackLog
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The fields the show page asks for with the series' episodes: the server only fills in
 * `CanDownload` when asked (see `SeasonEpisodeFieldsTest`), and without it nothing would download.
 */
internal val showEpisodeFields = setOf(EpisodeField.CAN_DOWNLOAD)

@HiltViewModel
class ShowViewModel
internal constructor(
    private val catalog: ItemCatalog,
    private val userData: UserDataUpdates,
    private val extras: ItemExtras,
    private val capabilities: CurrentServerCapabilities,
    private val appPreferences: AppPreferences,
    private val downloadDeleter: DownloadDeleter,
    private val downloadQueue: DownloadQueuePort,
    private val downloadList: DownloadList,
    dataChanges: DataChangeEvents = DataChangeEvents(),
    externalRatings: ExternalRatings = ExternalRatings.None,
    /**
     * CE (#809): where the cast and crew are sorted out ([defaultDispatcher]) and Room and the
     * downloads are read ([ioDispatcher]); the tests run both on their own dispatcher, so no load
     * outlives its test.
     */
    private val defaultDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    @Inject
    constructor(
        catalog: ItemCatalog,
        userData: UserDataUpdates,
        extras: ItemExtras,
        capabilities: CurrentServerCapabilities,
        appPreferences: AppPreferences,
        downloadDeleter: DownloadDeleter,
        downloadQueue: DownloadQueuePort,
        downloadList: DownloadList,
        dataChanges: DataChangeEvents = DataChangeEvents(),
        externalRatings: ExternalRatings = ExternalRatings.None,
    ) : this(
        catalog,
        userData,
        extras,
        capabilities,
        appPreferences,
        downloadDeleter,
        downloadQueue,
        downloadList,
        dataChanges,
        externalRatings,
        Dispatchers.Default,
        Dispatchers.IO,
    )

    private val _state = MutableStateFlow(ShowState())
    val state = _state.asStateFlow()

    // CE (#331)
    private val ratingsLoader =
        ExternalRatingsLoader(externalRatings, viewModelScope) { ratings ->
            _state.update { it.copy(externalRatings = ratings) }
        }

    private val eventsChannel = Channel<ShowEvent>()
    val events = eventsChannel.receiveAsFlow()

    private var showIdOrNull: ItemId? = null
    private val showId: ItemId
        get() = checkNotNull(showIdOrNull) { "showId is read before it is loaded" }

    private var downloadsOnly: Boolean = false
    private val reloadPolicy = ReloadPolicy(dataChanges)
    private var loadJob: Job? = null
    private var relatedJob: Job? = null
    private var relatedFor: ItemId? = null
    private var relatedRequest: RelatedItemsRequest? = null

    /** Whether coming back to the page needs a load (#251), see [ReloadPolicy]. */
    fun shouldReload(
        showId: ItemId,
        downloadsOnly: Boolean = this.downloadsOnly,
        stopEventFollows: Boolean = false,
    ): Boolean =
        showIdOrNull == null ||
            this.showId != showId ||
            this.downloadsOnly != downloadsOnly ||
            reloadPolicy.shouldReload(stopEventFollows)

    /**
     * Loads the page in two steps (#252): the show, its seasons, next up and cast first, all
     * requested at once; then the per-season download counts. Those come from Room plus a single
     * request for the series' episodes, not one request per season.
     */
    fun loadShow(showId: ItemId, downloadsOnly: Boolean = this.downloadsOnly) {
        showIdOrNull = showId
        this.downloadsOnly = downloadsOnly
        if (relatedFor != null && relatedFor != showId) {
            relatedJob?.cancel()
            relatedFor = null
            _state.update { it.copy(related = RelatedItems()) }
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val token = reloadPolicy.begin()
            // #769: read on every load, as the server may have changed since.
            _state.emit(
                _state.value.copy(
                    error = null,
                    cappedDownloads = capabilities.get().cappedDownloadTranscode,
                )
            )
            try {
                // coroutineScope { } is required: without it a failure inside an async { }
                // child goes straight to viewModelScope and crashes the app, past this
                // try/catch (see HomeViewModel.loadData).
                coroutineScope {
                    val showAsync = async { catalog.getShow(showId) }
                    // CE (#281): the seasons with downloads, from Room. When there are any, an
                    // unreachable server is no error: the page shows what Room has, as the
                    // Downloads tab does.
                    val storedSeasonsAsync = async { catalog.getSeasons(showId, offline = true) }
                    val nextUpAsync = async {
                        if (downloadsOnly) null
                        else
                            serverOrStored(
                                storedSeasonsAsync
                                    .await()
                                    .takeIf { it.isNotEmpty() }
                                    ?.let { suspend { null } },
                                log = TimberFallbackLog,
                            ) {
                                getNextUp(showId)
                            }
                    }
                    val seasonsAsync = async {
                        if (downloadsOnly) storedSeasonsAsync.await()
                        else
                            serverOrStoredList(
                                storedSeasonsAsync.await(),
                                log = TimberFallbackLog,
                            ) {
                                catalog.getSeasons(showId)
                            }
                    }
                    // The download counts are extras: when they fail, the page still shows.
                    val localCountsAsync = async { optional { getLocalEpisodeCounts(showId) } }
                    val serverTotalsAsync = async {
                        if (downloadsOnly) null else optional { getServerEpisodeTotals(showId) }
                    }

                    val show = showAsync.await()
                    val displayCastAndCrew =
                        appPreferences.getValue(appPreferences.displayCastAndCrew)
                    // CE (#510): the director and the writers have a switch of their own.
                    val displayCredits =
                        displayCastAndCrew && appPreferences.getValue(appPreferences.detailCredits)
                    val castAndCrewAsync =
                        async(defaultDispatcher) {
                            if (displayCastAndCrew) castAndCrew(show) else CastAndCrew()
                        }
                    val nextUp = nextUpAsync.await()
                    val seasons = seasonsAsync.await()
                    val castAndCrew = castAndCrewAsync.await()
                    // Room answers long before the server does, so whether anything is
                    // downloaded is right in the first emit and the buttons don't flip later.
                    val localCounts = localCountsAsync.await()
                    val sections = appPreferences.detailSections()
                    _state.emit(
                        _state.value.copy(
                            show = show.shownIn(sections),
                            nextUp = nextUp,
                            seasons = seasons,
                            actors = castAndCrew.actors,
                            director = castAndCrew.director.takeIf { displayCredits },
                            writers = castAndCrew.writers.takeIf { displayCredits }.orEmpty(),
                            externalRatings =
                                ratingsLoader.onItemLoaded(
                                    showId,
                                    // None on the downloads-only page, as no related rows.
                                    show.ratingsLookup().takeUnless { downloadsOnly },
                                    _state.value.externalRatings,
                                    rottenTomatoes =
                                        sections.criticRating && show.criticRating == null,
                                ),
                            hasDownloads =
                                localCounts?.let { counts ->
                                    seasons.any { (counts[it.id]?.downloaded ?: 0) > 0 }
                                } ?: _state.value.hasDownloads,
                        )
                    )
                    // A reload of a page that has its rows: a switch may have changed since.
                    if (relatedFor == showId) loadRelated(showId)

                    if (localCounts == null) return@coroutineScope
                    // On the downloads-only page the stored episodes are all there is, and they
                    // say nothing about what the server allows to download.
                    val totals =
                        if (downloadsOnly)
                            localCounts.mapValues { SeasonEpisodeTotals(total = it.value.total) }
                        else serverTotalsAsync.await() ?: return@coroutineScope
                    _state.emit(
                        _state.value.copy(
                            seasonDownloadInfo =
                                seasons.associate { season ->
                                    val seasonTotals = totals[season.id] ?: SeasonEpisodeTotals()
                                    val total = seasonTotals.total
                                    val downloaded = localCounts[season.id]?.downloaded ?: 0
                                    season.id to
                                        SeasonDownloadInfo(
                                            downloadedCount = downloaded.coerceAtMost(total),
                                            totalCount = total,
                                            canDownload = seasonTotals.canDownload,
                                        )
                                }
                        )
                    )
                }
                reloadPolicy.complete(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e))
            }
        }
    }

    /** Stored and downloaded episodes per season, from Room only, of the current server (#718). */
    private suspend fun getLocalEpisodeCounts(showId: ItemId): Map<ItemId, SeasonEpisodeCounts> =
        withContext(ioDispatcher) {
            val serverId = appPreferences.currentServerId() ?: return@withContext emptyMap()
            downloadList.seasonEpisodeCounts(showId, serverId).associateBy { it.seasonId }
        }

    private data class SeasonEpisodeTotals(val total: Int = 0, val canDownload: Boolean = false)

    /**
     * Episodes per season on the server, and whether any of them may be downloaded, from one
     * request for the whole series.
     */
    private suspend fun getServerEpisodeTotals(showId: ItemId): Map<ItemId, SeasonEpisodeTotals> =
        catalog
            .getEpisodes(seriesId = showId, seasonId = null, fields = showEpisodeFields)
            .groupBy { it.seasonId }
            .mapValues { (_, episodes) ->
                SeasonEpisodeTotals(
                    total = episodes.size,
                    canDownload = episodes.any { it.canDownload },
                )
            }

    private suspend fun <T> optional(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to load the show's download counts")
            null
        }

    /**
     * CE (#332): the extras and "More like this" rows, each from its own request. The page calls
     * this once it has drawn the show, so these requests never delay it; they are made once per
     * show. Not on the downloads-only page.
     */
    fun loadRelated(showId: ItemId) {
        val show = _state.value.show?.takeIf { it.id == showId } ?: return
        if (downloadsOnly) return
        val sections = appPreferences.detailSections()
        // CE (#331): the external ratings, after the first frame like the rows.
        ratingsLoader.load(
            showId,
            show.ratingsLookup(),
            rottenTomatoes = sections.criticRating && show.criticRating == null,
        )
        val request =
            RelatedItemsRequest.forItem(
                    localTrailerCount = show.localTrailerCount,
                    specialFeatureCount = show.specialFeatureCount,
                    collections = false,
                )
                .shownIn(sections)
                .within(capabilities.get())
        if (relatedFor == showId && relatedRequest == request) return
        relatedFor = showId
        relatedRequest = request
        relatedJob?.cancel()
        // A row whose switch was turned off since the last load goes away.
        _state.update { it.copy(related = it.related.keeping(request)) }
        relatedJob =
            viewModelScope.launchRelatedItems(
                extras = extras,
                itemId = showId,
                request = request,
            ) { change ->
                _state.update { it.copy(related = change(it.related)) }
            }
    }

    /**
     * CE (#141): look up the series' theme song for the TV show page, only when theme music is
     * turned on. Separate from [loadShow] so the page never waits for it, and the phone never asks.
     */
    fun loadThemeSong(showId: ItemId) {
        if (!appPreferences.getValue(appPreferences.tvThemeMusic)) return
        // #769: a server without theme songs is not asked for one.
        if (!capabilities.get().themeSongs) return
        viewModelScope.launch {
            val url = extras.getThemeSongUrl(showId)
            _state.emit(_state.value.copy(themeSongUrl = url))
        }
    }

    /** CE (#636): the questions of a download, see [EpisodeDownloadPrompts]. */
    private val downloadPrompts = EpisodeDownloadPrompts()

    /** CE (#636): the open question of a download (which episodes, then the quality), or null. */
    val downloadPrompt: StateFlow<DownloadPrompt?> = downloadPrompts.prompt

    /** CE (#636): the answer to [DownloadPrompt.Episodes]. */
    fun chooseEpisodes(unwatchedOnly: Boolean) = downloadPrompts.chooseEpisodes(unwatchedOnly)

    /** CE (#636): the answer to [DownloadPrompt.Quality], which starts the download. */
    fun chooseQuality(maxBitrate: Int?) {
        val answer = downloadPrompts.chooseQuality(maxBitrate) ?: return
        viewModelScope.launch(ioDispatcher) {
            enqueue(answer.episodes, answer.maxBitrate, answer.unwatchedOnly)
        }
    }

    /** CE (#636): the open question was cancelled, so nothing is downloaded. */
    fun dismissDownloadPrompt() = downloadPrompts.dismiss()

    /**
     * CE (#636): asks the questions of a download of [episodes], or, when every one of them is
     * downloaded or in flight already, says so at once without asking anything.
     */
    private suspend fun startDownload(episodes: List<Episode>) {
        val inFlightIds = downloadQueue.inFlightIds()
        if (selectEpisodesToDownload(episodes, inFlightIds).toQueue.isEmpty()) {
            enqueue(episodes, maxBitrate = null, unwatchedOnly = false)
        } else {
            downloadPrompts.start(episodes, inFlightIds)
        }
    }

    fun downloadSeasons(seasonIds: Set<ItemId>) {
        viewModelScope.launch(ioDispatcher) {
            val episodes = seasonIds.flatMap { seasonId ->
                catalog.getEpisodes(
                    seriesId = showId,
                    seasonId = seasonId,
                    fields = showEpisodeFields + EpisodeField.TRICKPLAY,
                )
            }
            startDownload(episodes)
        }
    }

    private suspend fun enqueue(episodes: List<Episode>, maxBitrate: Int?, unwatchedOnly: Boolean) {
        // Episodes already in flight count as skipped so the toast count
        // matches what actually got added to the queue.
        val (toQueue, skipped) =
            selectEpisodesToDownload(episodes, downloadQueue.inFlightIds(), unwatchedOnly)
        downloadQueue.enqueueAll(toQueue, maxBitrate)
        eventsChannel.send(ShowEvent.DownloadResult(toQueue.size, skipped, 0))
        loadShow(showId)
    }

    fun deleteShowDownloads() {
        viewModelScope.launch(ioDispatcher) {
            for (season in _state.value.seasons) {
                val episodes =
                    catalog.getEpisodes(seriesId = showId, seasonId = season.id, offline = true)
                for (episode in episodes) {
                    val localSource =
                        episode.sources.firstOrNull { it.type == ItemSourceType.LOCAL }
                    if (localSource != null) {
                        downloadDeleter.deleteItem(item = episode, source = localSource)
                    }
                }
            }
            loadShow(showId)
        }
    }

    private suspend fun getNextUp(showId: ItemId): Episode? {
        val nextUpItems = catalog.getNextUp(showId)
        return nextUpItems.getOrNull(0)
    }

    private class CastAndCrew(
        val actors: List<ItemPerson> = emptyList(),
        val director: ItemPerson? = null,
        val writers: List<ItemPerson> = emptyList(),
    )

    private fun castAndCrew(item: Show): CastAndCrew {
        val people = item.people.mergeDuplicateRoles()
        return CastAndCrew(
            actors = people.filter { it.type == PersonKind.ACTOR },
            director = people.firstOrNull { it.type == PersonKind.DIRECTOR },
            writers = people.filter { it.type == PersonKind.WRITER },
        )
    }

    /**
     * Marks the show played or unplayed and updates the page in place (#252) instead of loading it
     * again. Next up and the seasons (their played marks and unplayed counts) change with it, so
     * only those two are fetched again, together.
     */
    private fun setPlayed(played: Boolean) {
        viewModelScope.launch {
            try {
                if (played) userData.markAsPlayed(showId) else userData.markAsUnplayed(showId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(
                    e,
                    if (played) "Failed to mark as played" else "Failed to unmark as played",
                )
                return@launch
            }
            reloadPolicy.changedHere()
            _state.update { state -> state.copy(show = state.show?.copy(played = played)) }
            try {
                coroutineScope {
                    val nextUpAsync = async { if (downloadsOnly) null else getNextUp(showId) }
                    val seasonsAsync = async { catalog.getSeasons(showId, offline = downloadsOnly) }
                    val nextUp = nextUpAsync.await()
                    val seasons = seasonsAsync.await()
                    _state.update { state -> state.copy(nextUp = nextUp, seasons = seasons) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to refresh next up and seasons")
            }
        }
    }

    /** Marks the show favourite or not and updates the page in place (#252). */
    private fun setFavorite(favorite: Boolean) {
        viewModelScope.launch {
            try {
                if (favorite) userData.markAsFavorite(showId) else userData.unmarkAsFavorite(showId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(
                    e,
                    if (favorite) "Failed to mark as favorite" else "Failed to unmark as favorite",
                )
                return@launch
            }
            reloadPolicy.changedHere()
            _state.update { state -> state.copy(show = state.show?.copy(favorite = favorite)) }
        }
    }

    fun onAction(action: ShowAction) {
        when (action) {
            is ShowAction.OnRetryClick -> loadShow(showId)
            is ShowAction.MarkAsPlayed -> setPlayed(true)
            is ShowAction.UnmarkAsPlayed -> setPlayed(false)
            is ShowAction.MarkAsFavorite -> setFavorite(true)
            is ShowAction.UnmarkAsFavorite -> setFavorite(false)
            else -> Unit
        }
    }

    override fun onCleared() {
        super.onCleared()
        eventsChannel.close()
    }
}
