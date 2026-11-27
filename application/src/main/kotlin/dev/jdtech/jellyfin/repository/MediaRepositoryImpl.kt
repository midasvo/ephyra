package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.domain.ports.DeviceCodecs
import dev.jdtech.jellyfin.domain.ports.ItemListPort
import dev.jdtech.jellyfin.domain.ports.ItemQuery
import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.domain.ports.PlaybackReport
import dev.jdtech.jellyfin.domain.ports.PlaybackRequest
import dev.jdtech.jellyfin.domain.ports.snapshot
import dev.jdtech.jellyfin.domain.userdata.PlayState
import dev.jdtech.jellyfin.domain.userdata.PlayStop
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.EpisodeField
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemFilter
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.Library
import dev.jdtech.jellyfin.models.MediaSourceId
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.Person
import dev.jdtech.jellyfin.models.PlaybackTarget
import dev.jdtech.jellyfin.models.PlayerEngineId
import dev.jdtech.jellyfin.models.Season
import dev.jdtech.jellyfin.models.Segment
import dev.jdtech.jellyfin.models.SeriesTrackPreference
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.ServerInfo
import dev.jdtech.jellyfin.models.Show
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.player.DeviceDecoders
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The server in use composed with the stores: every role of the repository in one class (#967),
 * because the roles share its state (the play sessions, the resume settings, whether anything is
 * downloaded). Hilt binds each role to the one instance (`MediaRepositoryModule` in `core`);
 * nothing else names this class. #966: pure Kotlin, over the parts of [MediaServer] and the store
 * roles; the downloaded files are [DownloadedFiles], the log is [log] and [fallbackLog].
 */
class MediaRepositoryImpl(
    private val server: MediaServer,
    private val storedItems: StoredItems,
    private val storedLists: StoredItemLists,
    private val storedSources: StoredItemSources,
    private val storedSegments: DownloadSegmentsAndTrickplay,
    private val storedUserData: DownloadUserData,
    private val trackPreferences: StoredTrackPreferences,
    private val storedUsers: StoredUsers,
    private val downloadedFiles: DownloadedFiles,
    private val currentServer: CurrentServerId,
    private val nextUpMaxDays: NextUpMaxDays,
    /** CE (#637): the resume settings read from each server, kept for the stops made offline. */
    private val resumeThresholds: ResumeThresholdsStore,
    private val syncRequester: UserDataSyncRequester = UserDataSyncRequester.NONE,
    private val knownServerState: KnownServerState = KnownServerState.NONE,
    private val storedLibraryNames: StoredLibraryNames = StoredLibraryNames.ENGLISH,
    /** Why a stored copy answered (#899: Timber in the app, as before), and what it reports. */
    private val fallbackLog: FallbackLog = FallbackLog.NONE,
    private val log: RepositoryLog = RepositoryLog.NONE,
    private val deviceCodecs: DeviceCodecs = DeviceCodecs.NONE,
) :
    CurrentAccount,
    ItemCatalog,
    ItemLists,
    ItemExtras,
    UserDataUpdates,
    SeriesTrackPreferences,
    PlaybackMedia,
    PlaybackReports {
    private val capabilities: ServerCapabilities
        get() = server.capabilities

    private val playSessions = PlaySessions()

    /**
     * CE (#775): a list Room can answer: straight from Room while the server is known to be away,
     * else from Room only when the request gets no answer. Not after [STORED_FALLBACK_TIMEOUT_MS]
     * as an item is: a slow server would leave the shorter downloads-only list on a screen that
     * does not say so. A request left unanswered gets a probe, and the screens reload when that
     * says unreachable. CE (#779): the result says which of the two answered.
     */
    private suspend fun <T> serverOrStoredItems(
        stored: (suspend () -> List<T>)?,
        server: suspend () -> List<T>,
    ): ItemsResult<T> {
        if (stored != null && knownServerState.isKnownUnreachable()) {
            knownServerState.onServerSkipped()
            return ItemsResult(stored(), fromStore = true)
        }
        var fromStore = false
        val items =
            serverOrStoredWhenUnanswered(
                stored?.let { read ->
                    suspend {
                        fromStore = true
                        read()
                    }
                },
                fallbackLog,
                server,
            )
        return ItemsResult(items, fromStore)
    }

    /**
     * CE (#775): maps stored rows only when they are needed; null when there are none, so the
     * server's error comes through. With [emptyIsAnAnswer] no stored rows is an answer too: the
     * favourites can say "none downloaded", which an empty library or search could not.
     */
    private fun storedItems(
        rows: StoredRows<Item>?,
        emptyIsAnAnswer: Boolean = false,
    ): (suspend () -> List<Item>)? {
        if (rows == null) return null
        if (rows.size == 0) return if (emptyIsAnAnswer) suspend { emptyList() } else null
        return suspend { rows.load(currentUserId) }
    }

    /** #966: the rows for the current user, read only when there are any, as a map over them. */
    private suspend fun <T> StoredRows<T>.forCurrentUser(): List<T> =
        if (size == 0) emptyList() else load(currentUserId)

    private fun currentServerId(): ServerId? = currentServer.get()

    /**
     * CE (#637): reads the server's resume settings once per app run, so stops here and offline
     * follow them. A failure keeps the last ones read, or Jellyfin's defaults.
     */
    private suspend fun readResumeThresholds() {
        // #769: a server without its own thresholds leaves the defaults.
        if (!capabilities.serverResumeThresholds) return
        val serverId = currentServerId() ?: return
        if (resumeThresholds.isFresh(serverId)) return
        try {
            server.playback.resumeThresholds()?.let { resumeThresholds.put(serverId, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e, "Could not read the server's resume settings")
        }
    }

    private val currentUserId: UserId
        get() =
            server.session.userId
                ?: throw IllegalStateException(
                    "No authenticated user. userId is null — session may have expired."
                )

    override suspend fun getServerInfo(): ServerInfo = server.info.serverInfo()

    override suspend fun getUserViews(): List<Library> = server.itemLists.userViews()

    // CE (#281): the item getters fall back to the copy in Room (a download, or the show and season
    // of a downloaded episode) when the server fails or is slow, see serverOrStored. CE (#718):
    // only
    // a copy stored for the current server: another server can have the same item ids.
    override suspend fun getEpisode(itemId: ItemId): Episode =
        withContext(Dispatchers.IO) {
            val stored = currentServerId()?.let { storedItems.episodeOfServer(itemId, it) }
            serverOrStored(
                stored?.let { row -> suspend { row.load(currentUserId) } },
                log = fallbackLog,
            ) {
                server.items.episode(itemId).withLocalSources(localSourcesFor(listOf(itemId)))
            }
        }

    override suspend fun getMovie(itemId: ItemId): Movie =
        withContext(Dispatchers.IO) {
            val stored = currentServerId()?.let { storedItems.movieOfServer(itemId, it) }
            serverOrStored(
                stored?.let { row -> suspend { row.load(currentUserId) } },
                log = fallbackLog,
            ) {
                server.items.movie(itemId).withLocalSources(localSourcesFor(listOf(itemId)))
            }
        }

    override suspend fun getShow(itemId: ItemId): Show =
        withContext(Dispatchers.IO) {
            val stored = currentServerId()?.let { storedItems.showOfServer(itemId, it) }
            serverOrStored(
                stored?.let { row -> suspend { row.load(currentUserId) } },
                log = fallbackLog,
            ) {
                server.items.show(itemId)
            }
        }

    override suspend fun getSeason(itemId: ItemId): Season =
        withContext(Dispatchers.IO) {
            val stored = currentServerId()?.let { storedItems.seasonOfServer(itemId, it) }
            serverOrStored(
                stored?.let { row -> suspend { row.load(currentUserId) } },
                log = fallbackLog,
            ) {
                server.items.season(itemId)
            }
        }

    override suspend fun getLibraries(): ItemsResult<Library> =
        withContext(Dispatchers.IO) {
            // CE (#775): without the server, a Movies and a Shows library of what is downloaded.
            // CE (#781): built only when Room answers, as they look for their artwork.
            val serverId = currentServerId()?.takeIf { storedLists.hasDownloads(it) }
            val stored = serverId?.let { id ->
                suspend { storedLists.libraries(id, storedLibraryNames, downloadedFiles::hasImage) }
            }
            serverOrStoredItems(stored) { server.itemLists.libraries() }
        }

    override suspend fun getItem(itemId: ItemId): Item? =
        withContext(Dispatchers.IO) {
            serverOrStored(storedItem(itemId), log = fallbackLog) { server.items.item(itemId) }
        }

    /**
     * CE (#281): builds [itemId] from Room, or null when Room doesn't have it for the current
     * server (#718).
     */
    private suspend fun storedItem(itemId: ItemId): (suspend () -> Item)? {
        val serverId = currentServerId() ?: return null
        storedItems.movieOfServer(itemId, serverId)?.let {
            return suspend { it.load(currentUserId) }
        }
        storedItems.episodeOfServer(itemId, serverId)?.let {
            return suspend { it.load(currentUserId) }
        }
        storedItems.showOfServer(itemId, serverId)?.let {
            return suspend { it.load(currentUserId) }
        }
        storedItems.seasonOfServer(itemId, serverId)?.let {
            return suspend { it.load(currentUserId) }
        }
        return null
    }

    override suspend fun getItems(
        parentId: ItemId?,
        includeTypes: List<ItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
        filters: Set<ItemFilter>,
        startIndex: Int?,
        limit: Int?,
    ): List<Item> =
        getItemsPage(
                parentId,
                includeTypes,
                recursive,
                sortBy,
                sortOrder,
                filters,
                startIndex,
                limit,
                includeTotal = false,
            )
            .items

    override suspend fun getItemsPage(
        parentId: ItemId?,
        includeTypes: List<ItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
        filters: Set<ItemFilter>,
        startIndex: Int?,
        limit: Int?,
        includeTotal: Boolean,
    ): ItemsPage =
        withContext(Dispatchers.IO) {
            // CE (#775): both pagers and the plain list come through here.
            if (StoredLibraries.isStored(parentId)) {
                return@withContext storedLibraryPage(
                    parentId!!,
                    sortBy,
                    sortOrder,
                    filters,
                    startIndex,
                    limit,
                    includeTotal,
                )
            }
            val page =
                server.itemLists.items(
                    ItemQuery(
                        parentId = parentId,
                        kinds = includeTypes,
                        recursive = recursive,
                        sortBy = sortBy,
                        sortOrder = sortOrder,
                        filters = filters,
                        startIndex = startIndex,
                        limit = limit,
                    ),
                    includeTotal = includeTotal,
                )
            page.copy(items = page.items.withLocalSources())
        }

    /**
     * CE (#775): a page of one of [StoredLibraries], always from Room: the server does not know
     * these ids. Only the page is mapped to items.
     */
    private suspend fun storedLibraryPage(
        libraryId: ItemId,
        sortBy: SortBy,
        sortOrder: SortOrder,
        filters: Set<ItemFilter>,
        startIndex: Int?,
        limit: Int?,
        includeTotal: Boolean,
    ): ItemsPage {
        val serverId = currentServerId() ?: return ItemsPage(emptyList(), 0)
        val userId = currentUserId
        val all =
            storedLists.libraryContents(serverId, userId, libraryId, sortBy, sortOrder, filters)
        val page = all.slice(startIndex ?: 0, limit)
        return ItemsPage(
            items = page.load(userId),
            totalRecordCount = if (includeTotal) all.size else null,
        )
    }

    override suspend fun getRandomItemsWithBackdrop(
        includeTypes: List<ItemKind>,
        limit: Int,
    ): List<Item> =
        withContext(Dispatchers.IO) {
            server.itemLists.randomItemsWithBackdrop(includeTypes, limit).withLocalSources()
        }

    override suspend fun getItemCount(
        parentId: ItemId?,
        includeTypes: List<ItemKind>?,
        recursive: Boolean,
        filters: Set<ItemFilter>,
        nameLessThan: String?,
    ): Int =
        withContext(Dispatchers.IO) {
            // CE (#775): the A–Z bar of a stored library counts what its grid holds.
            if (StoredLibraries.isStored(parentId)) {
                val serverId = currentServerId() ?: return@withContext 0
                return@withContext storedLists.libraryCount(
                    serverId,
                    currentUserId,
                    parentId!!,
                    filters,
                    nameLessThan,
                )
            }
            server.itemLists.itemCount(
                ItemQuery(
                    parentId = parentId,
                    kinds = includeTypes,
                    recursive = recursive,
                    filters = filters,
                ),
                nameLessThan = nameLessThan,
            )
        }

    override suspend fun getPerson(personId: ItemId): Person = server.items.person(personId)

    override suspend fun getPersonItems(
        personIds: List<ItemId>,
        includeTypes: List<ItemKind>?,
        recursive: Boolean,
        limit: Int?,
    ): List<Item> =
        withContext(Dispatchers.IO) {
            server.items.personItems(personIds, includeTypes, recursive, limit).withLocalSources()
        }

    override suspend fun getFavoriteItems(): ItemsResult<Item> =
        withContext(Dispatchers.IO) {
            // CE (#775): without the server, the downloaded favourites, also when there are none
            // but something else is downloaded: the screen says that none are downloaded. With
            // nothing downloaded at all (TV, a phone without downloads) the server's error comes
            // through, as before.
            val serverId = currentServerId()
            val stored = serverId?.let { storedLists.favorites(it, currentUserId) }
            val noneIsAnAnswer =
                serverId != null && stored?.size == 0 && storedLists.hasDownloads(serverId)
            serverOrStoredItems(storedItems(stored, emptyIsAnAnswer = noneIsAnAnswer)) {
                server.itemLists.favorites().withLocalSources()
            }
        }

    override suspend fun getSearchItems(
        query: String,
        itemKinds: List<ItemKind>?,
    ): ItemsResult<Item> =
        withContext(Dispatchers.IO) {
            // CE (#775): without the server, the downloads by name.
            val serverId = currentServerId()
            val stored = serverId?.let {
                storedLists.search(
                    it,
                    query,
                    itemKinds,
                    if (itemKinds == null) ItemListPort.SEARCH_LIMIT
                    else ItemListPort.SEARCH_LIMIT_PER_KIND,
                )
            }
            serverOrStoredItems(storedItems(stored)) {
                server.itemLists.search(query, itemKinds).withLocalSources()
            }
        }

    override suspend fun getSuggestions(): List<Item> =
        withContext(Dispatchers.IO) { server.itemLists.suggestions().withLocalSources() }

    override suspend fun getResumeItems(): List<Item> =
        withContext(Dispatchers.IO) { server.itemLists.resumeItems().withLocalSources() }

    override suspend fun getLatestMedia(
        parentId: ItemId,
        includeItemTypes: List<ItemKind>?,
    ): List<Item> =
        withContext(Dispatchers.IO) {
            server.itemLists.latest(parentId, includeItemTypes).withLocalSources()
        }

    override suspend fun getSeasons(seriesId: ItemId, offline: Boolean): List<Season> =
        withContext(Dispatchers.IO) {
            if (!offline) {
                server.items.seasons(seriesId)
            } else {
                // CE (#718): the current server's stored seasons only.
                val serverId = currentServerId() ?: return@withContext emptyList()
                storedItems.seasonsOfShow(seriesId, serverId).forCurrentUser()
            }
        }

    override suspend fun getNextUp(seriesId: ItemId?): List<Episode> =
        withContext(Dispatchers.IO) {
            server.items.nextUp(
                seriesId,
                dateCutoff =
                    nextUpDateCutoff(
                        seriesId = seriesId,
                        maxDays = nextUpMaxDays.get(),
                        now = LocalDateTime.now(),
                    ),
            )
        }

    override suspend fun getEpisodes(
        seriesId: ItemId,
        seasonId: ItemId?,
        fields: Set<EpisodeField>?,
        startItemId: ItemId?,
        limit: Int?,
        offline: Boolean,
    ): List<Episode> =
        withContext(Dispatchers.IO) {
            if (!offline) {
                val episodes = server.items.episodes(seriesId, seasonId, fields, startItemId, limit)
                val local = localSourcesFor(episodes.map { it.id })
                episodes.map { it.withLocalSources(local) }
            } else {
                // CE (#718): the current server's stored episodes only.
                val serverId = currentServerId() ?: return@withContext emptyList()
                val episodes =
                    if (seasonId != null) storedItems.episodesOfSeason(seasonId, serverId)
                    else storedItems.episodesOfSeries(seriesId, serverId)
                episodes.forCurrentUser()
            }
        }

    override suspend fun getMediaSources(
        itemId: ItemId,
        includePath: Boolean,
        transcodeDolbyVision: Boolean,
        maxBitrate: Int?,
    ): List<ItemSource> =
        withContext(Dispatchers.IO) {
            // The downloader's path: the original file, or a transcode for Dolby Vision or a
            // download quality cap (JellyfinPlayback).
            val sources = mutableListOf<ItemSource>()
            sources.addAll(
                server.playback.sources(
                    PlaybackRequest(
                        itemId = itemId,
                        target = PlaybackTarget.Download(transcodeDolbyVision),
                        maxBitrate = maxBitrate,
                        includePath = includePath,
                    )
                )
            )
            // CE (#718): the current server's download of the item, not another server's.
            currentServerId()?.let { serverId ->
                sources.addAll(storedSources.allSourcesOfServer(itemId, serverId))
            }
            sources
        }

    override suspend fun getPlaybackSources(
        itemId: ItemId,
        target: PlaybackTarget,
        mediaSourceId: MediaSourceId?,
        audioStreamIndex: Int?,
        maxBitrate: Int?,
    ): List<ItemSource> =
        withContext(Dispatchers.IO) {
            // A downloaded copy wins over any server source (see ResolvePlayback.pick), so do not
            // wait
            // for the server when it is the one that will play. Only a finished one: an unfinished
            // download's partial file would play as far as it got, or not at all (#454). CE (#718):
            // and only a download stored for the current server.
            val localSources =
                currentServerId()
                    ?.let { storedSources.completedSourcesOfServer(itemId, it) }
                    .orEmpty()
            if (
                localSources.isNotEmpty() &&
                    (mediaSourceId == null || localSources.any { it.id == mediaSourceId })
            ) {
                return@withContext localSources
            }

            val decoders =
                if (
                    target is PlaybackTarget.LocalPlayer &&
                        target.engine == PlayerEngineId.EXOPLAYER
                ) {
                    deviceCodecs.snapshot(checkDisplayDolbyVision = target.directPlayDolbyVision)
                } else {
                    DeviceDecoders()
                }
            val sources = mutableListOf<ItemSource>()
            sources.addAll(
                server.playback.sources(
                    PlaybackRequest(
                        itemId = itemId,
                        target = target,
                        mediaSourceId = mediaSourceId,
                        audioStreamIndex = audioStreamIndex,
                        maxBitrate = maxBitrate,
                        includePath = true,
                        decoders = decoders,
                    )
                )
            )
            sources.addAll(localSources)
            sources
        }

    override suspend fun getSeriesTrackPreferences(seriesId: ItemId): List<SeriesTrackPreference> =
        withContext(Dispatchers.IO) { trackPreferences.trackPreferences(currentUserId, seriesId) }

    override suspend fun saveSeriesTrackPreference(preference: SeriesTrackPreference) =
        withContext(Dispatchers.IO) {
            trackPreferences.saveTrackPreference(preference.copy(userId = currentUserId))
        }

    override suspend fun getSegments(itemId: ItemId): List<Segment> =
        withContext(Dispatchers.IO) {
            val databaseSegments = storedSegments.segments(itemId)

            if (databaseSegments.isNotEmpty()) {
                return@withContext databaseSegments
            }

            getServerSegments(itemId)
        }

    override suspend fun getServerSegments(itemId: ItemId): List<Segment> =
        withContext(Dispatchers.IO) {
            // #769: the player and the downloads ask here; without segments they get none.
            if (!capabilities.mediaSegments) return@withContext emptyList()
            try {
                return@withContext server.playback.segments(itemId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(e)
                return@withContext emptyList()
            }
        }

    override suspend fun getTrickplayData(
        itemId: ItemId,
        width: Int,
        index: Int,
        mediaSourceId: MediaSourceId?,
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                try {
                    // CE (#860): the sheets of the version asked for, not those of whichever
                    // version the folder lists first; a version not downloaded goes to the server.
                    downloadedFiles.trickplaySheet(itemId, mediaSourceId, index)?.let {
                        return@withContext it
                    }
                } catch (e: Exception) {
                    log.error(e, "Failed to read local trickplay data")
                }

                // #769: the player and the downloads ask here; without trickplay they get none.
                if (!capabilities.trickplay) return@withContext null
                return@withContext server.playback.trickplayTile(
                    itemId,
                    width,
                    index,
                    mediaSourceId,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withContext null
            }
        }

    override suspend fun getThemeSongUrl(itemId: ItemId): String? =
        withContext(Dispatchers.IO) {
            try {
                server.itemExtras.themeSongUrl(itemId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(e)
                null
            }
        }

    override suspend fun getSimilarItems(itemId: ItemId, limit: Int): List<Item> =
        withContext(Dispatchers.IO) { server.itemExtras.similar(itemId, limit).withLocalSources() }

    override suspend fun getExtras(
        itemId: ItemId,
        localTrailers: Boolean,
        specialFeatures: Boolean,
    ): List<Movie> = server.itemExtras.extras(itemId, localTrailers, specialFeatures)

    override suspend fun getItemCollections(itemId: ItemId): List<Item> =
        withContext(Dispatchers.IO) { server.itemExtras.collectionsOf(itemId).withLocalSources() }

    override fun setPlaySession(
        itemId: ItemId,
        playSessionId: String?,
        transcodes: Boolean,
        mediaSourceId: MediaSourceId?,
    ) = playSessions.onPlaybackInfo(itemId, playSessionId, transcodes, mediaSourceId)

    override suspend fun postPlaybackStart(
        itemId: ItemId,
        positionTicks: Long?,
        transcoded: Boolean,
    ) {
        // CE (#599): Transcode with the transcode's play session, see PlaySessions.playReport.
        val report = playSessions.playReport(itemId, transcoded)
        log.debug(
            "Sending start ${itemId.asString()} at $positionTicks, transcoded: ${report.transcoded}"
        )
        withContext(Dispatchers.IO) {
            // CE (#637): read alongside the start report, not before it, so a slow or failing read
            // does not hold up the start. The player waits for this call before it reports the
            // stop (up to 5 s), so the stop of this item still has them.
            coroutineScope {
                launch { readResumeThresholds() }
                server.playback.reportStart(
                    PlaybackReport(
                        itemId = itemId,
                        positionTicks = positionTicks,
                        isPaused = false,
                        transcoded = report.transcoded,
                        playSessionId = report.playSessionId,
                        mediaSourceId = report.mediaSourceId,
                    )
                )
            }
        }
    }

    override suspend fun postPlaybackStop(
        itemId: ItemId,
        positionTicks: Long,
        playedPercentage: Int?,
    ) {
        log.debug("Sending stop ${itemId.asString()}")
        withContext(Dispatchers.IO) {
            // CE (#637): the server applies its resume settings to this stop; the local copy
            // follows them.
            val playStop =
                PlayStop.of(
                    positionTicks,
                    playedPercentage,
                    runtimeTicks = storedItems.runtimeTicks(itemId),
                    thresholds = resumeThresholds.get(currentServerId()),
                )
            val stop = suspend {
                // CE (#909): the session and the version of one stream, read once.
                val stream = playSessions.streamOf(itemId)
                server.playback.reportStopped(
                    PlaybackReport(
                        itemId = itemId,
                        positionTicks = positionTicks,
                        isPaused = false,
                        transcoded = false,
                        // With it the server ends the item's transcode now, not a minute later.
                        playSessionId = stream?.playSessionId,
                        mediaSourceId = stream?.mediaSourceId,
                    )
                )
            }
            if (playStop == null) {
                // Nothing local changes without user data, so there is nothing to sync later.
                try {
                    stop()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
                return@withContext
            }
            reportUserData(itemId, report = stop) {
                it.stopped(playStop, at = System.currentTimeMillis())
            }
        }
    }

    override suspend fun postPlaybackProgress(
        itemId: ItemId,
        positionTicks: Long,
        isPaused: Boolean,
        transcoded: Boolean,
    ) {
        log.debug("Posting progress of ${itemId.asString()}, position: $positionTicks")
        val report = playSessions.playReport(itemId, transcoded)
        withContext(Dispatchers.IO) {
            reportUserData(
                itemId,
                report = {
                    server.playback.reportProgress(
                        PlaybackReport(
                            itemId = itemId,
                            positionTicks = positionTicks,
                            isPaused = isPaused,
                            transcoded = report.transcoded,
                            playSessionId = report.playSessionId,
                            mediaSourceId = report.mediaSourceId,
                        )
                    )
                },
            ) {
                it.progressed(positionTicks, at = System.currentTimeMillis())
            }
        }
    }

    override suspend fun markAsFavorite(itemId: ItemId) {
        withContext(Dispatchers.IO) {
            reportUserData(itemId, report = { server.userData.setFavorite(itemId, true) }) {
                it.withFavorite(true)
            }
        }
    }

    override suspend fun unmarkAsFavorite(itemId: ItemId) {
        withContext(Dispatchers.IO) {
            reportUserData(itemId, report = { server.userData.setFavorite(itemId, false) }) {
                it.withFavorite(false)
            }
        }
    }

    override suspend fun markAsPlayed(itemId: ItemId) {
        withContext(Dispatchers.IO) {
            reportUserData(itemId, report = { server.userData.setPlayed(itemId, true) }) {
                it.markedPlayed(at = System.currentTimeMillis())
            }
        }
    }

    override suspend fun markAsUnplayed(itemId: ItemId) {
        withContext(Dispatchers.IO) {
            // CE (#635): the time of the mark, taken before the request (see markedUnplayed).
            val markedAt = System.currentTimeMillis()
            // CE (#907): the user who marked it, read once: a profile switch while the request
            // waits must not clear the other user's date.
            val userId = currentUserId
            val reached =
                reportUserData(
                    itemId,
                    report = { server.userData.setPlayed(itemId, false) },
                    userId = userId,
                ) {
                    it.markedUnplayed(markedAt)
                }
            storedUserData.updatePlayState(userId, itemId) { it.afterUnplayedReport(reached) }
        }
    }

    /**
     * See the top-level [writeAndReport]: the play state [change] computes is written and marked
     * before [report]. #898: the rules are in [PlayState]; this reads the row and writes the
     * result. [userId] is the user the change is for, the current one unless the caller read it.
     */
    private suspend fun reportUserData(
        itemId: ItemId,
        report: suspend () -> Unit,
        userId: UserId = currentUserId,
        change: (PlayState) -> PlayState,
    ): Boolean {
        return writeAndReport(storedUserData, syncRequester, userId, itemId, change, report)
    }

    override fun chapterImageUrl(itemId: ItemId, chapterIndex: Int, tag: String) =
        server.playback.chapterImageUrl(itemId, chapterIndex, tag)

    override fun seasonBackdropUrl(seasonId: ItemId) = server.playback.seasonBackdropUrl(seasonId)

    override suspend fun refreshCurrentUserImage() {
        // The row of the user the server answered for, which is the one whose token went out: a
        // switch of user around the request cannot give the tag to another user's row.
        val image = server.session.currentUserImage()
        withContext(Dispatchers.IO) { storedUsers.setUserImageTag(image.userId, image.tag) }
    }

    // #769: the player asks here; a server without the setting has none, as the interface says.
    override suspend fun autoPlayNextEpisode(): Boolean? =
        if (capabilities.autoPlayNextSetting) server.session.autoPlayNextEpisode() else null

    override suspend fun getDownloads(): List<Item> =
        withContext(Dispatchers.IO) {
            val serverId = currentServer.get() ?: return@withContext emptyList()
            val items = mutableListOf<Item>()
            items.addAll(storedItems.moviesOfServer(serverId).forCurrentUser())
            items.addAll(storedItems.showsOfServer(serverId).forCurrentUser())
            items
        }

    override fun getUserId(): UserId {
        return currentUserId
    }

    /**
     * Whether any source (download) is stored at all. Kept up to date by a Room observer, so a
     * download that starts, finishes or is deleted while the app runs flips it. Null until the
     * first value arrives.
     */
    private val hasLocalSources: StateFlow<Boolean?> =
        storedSources
            .observeHasSources()
            .map<Boolean, Boolean?> { it }
            .catch { log.warn(it, "Couldn't observe the sources table") }
            .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Eagerly, null)

    /**
     * The downloaded sources of [itemIds], or nothing without a query when the app has no sources
     * at all (the usual case for a user who doesn't download). CE (#718): only the current server's
     * downloads.
     */
    private suspend fun localSourcesFor(itemIds: List<ItemId>): Map<ItemId, List<ItemSource>> {
        val serverId = currentServerId() ?: return emptyMap()
        val any = hasLocalSources.value ?: storedSources.hasSources()
        return if (any) storedSources.localSourcesOfServer(itemIds, serverId) else emptyMap()
    }

    /**
     * CE (#756): adds the downloaded sources of the movies and episodes the catalog gave, with one
     * batched lookup for the whole list instead of one query per item.
     */
    private suspend fun List<Item>.withLocalSources(): List<Item> {
        val local = localSourcesFor(filter { it is Movie || it is Episode }.map { it.id })
        if (local.isEmpty()) return this
        return map { item ->
            when (item) {
                is Movie -> item.withLocalSources(local)
                is Episode -> item.withLocalSources(local)
                else -> item
            }
        }
    }
}

/**
 * CE (#756): the movie with its downloads of [local] after the server's sources, the order the
 * mappers kept before the catalog moved out (the player picks the local one, not the first).
 */
private fun Movie.withLocalSources(local: Map<ItemId, List<ItemSource>>): Movie =
    local[id]?.let { copy(sources = sources + it) } ?: this

/** CE (#756): as for a movie, the server's sources first, then the downloads of [local]. */
private fun Episode.withLocalSources(local: Map<ItemId, List<ItemSource>>): Episode =
    local[id]?.let { copy(sources = sources + it) } ?: this

/**
 * The Home row only shows series watched in the last [maxDays] days, as the web client's "Max days
 * in Next Up" does. 0 is no limit. A single series has no cutoff: a show last watched years ago
 * still has a next episode.
 */
internal fun nextUpDateCutoff(seriesId: ItemId?, maxDays: Int, now: LocalDateTime): LocalDateTime? =
    if (seriesId == null && maxDays > 0) now.minusDays(maxDays.toLong()) else null
