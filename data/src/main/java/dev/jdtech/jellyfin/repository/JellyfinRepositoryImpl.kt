package dev.jdtech.jellyfin.repository

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.FindroidCollection
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidPerson
import dev.jdtech.jellyfin.models.FindroidSeason
import dev.jdtech.jellyfin.models.FindroidSegment
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.SeriesTrackPreferenceDto
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.models.getLocalSourcesForItems
import dev.jdtech.jellyfin.models.toFindroidCollection
import dev.jdtech.jellyfin.models.toFindroidEpisode
import dev.jdtech.jellyfin.models.toFindroidItem
import dev.jdtech.jellyfin.models.toFindroidMovie
import dev.jdtech.jellyfin.models.toFindroidPerson
import dev.jdtech.jellyfin.models.toFindroidSeason
import dev.jdtech.jellyfin.models.toFindroidSegment
import dev.jdtech.jellyfin.models.toFindroidShow
import dev.jdtech.jellyfin.models.toFindroidSource
import dev.jdtech.jellyfin.player.DeviceProfileBuilder
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.io.File
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.audioApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.DeviceOptionsDto
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.SortOrder as ItemSortOrder
import org.jellyfin.sdk.model.api.UserConfiguration
import timber.log.Timber

class JellyfinRepositoryImpl(
    private val context: Context,
    private val jellyfinApi: JellyfinApi,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
    private val deviceProfileBuilder: DeviceProfileBuilder,
    private val syncRequester: UserDataSyncRequester = UserDataSyncRequester.NONE,
) : JellyfinRepository {
    private val playSessions = PlaySessions()

    private val resumeThresholds = ResumeThresholdsStore(appPreferences.sharedPreferences)

    private fun currentServerId(): String? = appPreferences.getValue(appPreferences.currentServer)

    /**
     * CE (#637): reads the server's resume settings once per app run, so stops here and offline
     * follow them. A failure keeps the last ones read, or Jellyfin's defaults.
     */
    private suspend fun readResumeThresholds() {
        val serverId = currentServerId() ?: return
        if (resumeThresholds.isFresh(serverId)) return
        try {
            val configuration = jellyfinApi.systemApi.getConfiguration().content
            resumeThresholds.put(serverId, ResumeThresholds.of(configuration))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not read the server's resume settings")
        }
    }

    private val currentUserId: UUID
        get() =
            jellyfinApi.userId
                ?: throw IllegalStateException(
                    "No authenticated user. userId is null — session may have expired."
                )

    override suspend fun getPublicSystemInfo(): PublicSystemInfo =
        withContext(Dispatchers.IO) { jellyfinApi.systemApi.getPublicSystemInfo().content }

    override suspend fun getUserViews(): List<BaseItemDto> =
        withContext(Dispatchers.IO) {
            jellyfinApi.userViewApi.getUserViews(currentUserId).content.items
        }

    // CE (#281): the item getters fall back to the copy in Room (a download, or the show and season
    // of a downloaded episode) when the server fails or is slow, see serverOrStored. CE (#718):
    // only
    // a copy stored for the current server: another server can have the same item ids.
    override suspend fun getEpisode(itemId: UUID): FindroidEpisode =
        withContext(Dispatchers.IO) {
            val stored = currentServerId()?.let { database.getEpisodeOfServer(itemId, it) }
            serverOrStored(
                stored?.let { dto -> suspend { dto.toFindroidEpisode(database, currentUserId) } }
            ) {
                jellyfinApi.libraryApi
                    .getItem(itemId, currentUserId)
                    .content
                    .toFindroidEpisode(
                        this@JellyfinRepositoryImpl,
                        localSourcesFor(listOf(itemId)),
                    )!!
            }
        }

    override suspend fun getMovie(itemId: UUID): FindroidMovie =
        withContext(Dispatchers.IO) {
            val stored = currentServerId()?.let { database.getMovieOfServer(itemId, it) }
            serverOrStored(
                stored?.let { dto -> suspend { dto.toFindroidMovie(database, currentUserId) } }
            ) {
                jellyfinApi.libraryApi
                    .getItem(itemId, currentUserId)
                    .content
                    .toFindroidMovie(this@JellyfinRepositoryImpl, localSourcesFor(listOf(itemId)))
            }
        }

    override suspend fun getShow(itemId: UUID): FindroidShow =
        withContext(Dispatchers.IO) {
            val stored = currentServerId()?.let { database.getShowOfServer(itemId, it) }
            serverOrStored(
                stored?.let { dto -> suspend { dto.toFindroidShow(database, currentUserId) } }
            ) {
                jellyfinApi.libraryApi
                    .getItem(itemId, currentUserId)
                    .content
                    .toFindroidShow(this@JellyfinRepositoryImpl)
            }
        }

    override suspend fun getSeason(itemId: UUID): FindroidSeason =
        withContext(Dispatchers.IO) {
            val stored = currentServerId()?.let { database.getSeasonOfServer(itemId, it) }
            serverOrStored(
                stored?.let { dto -> suspend { dto.toFindroidSeason(database, currentUserId) } }
            ) {
                jellyfinApi.libraryApi
                    .getItem(itemId, currentUserId)
                    .content
                    .toFindroidSeason(this@JellyfinRepositoryImpl)
            }
        }

    override suspend fun getLibraries(): List<FindroidCollection> =
        withContext(Dispatchers.IO) {
            jellyfinApi.libraryApi.getItems(currentUserId).content.items.mapNotNull {
                it.toFindroidCollection(this@JellyfinRepositoryImpl)
            }
        }

    override suspend fun getItem(itemId: UUID): FindroidItem? =
        withContext(Dispatchers.IO) {
            serverOrStored(storedItem(itemId)) {
                jellyfinApi.libraryApi
                    .getItem(itemId = itemId, userId = currentUserId)
                    .content
                    .toFindroidItem(this@JellyfinRepositoryImpl)
            }
        }

    /**
     * CE (#281): builds [itemId] from Room, or null when Room doesn't have it for the current
     * server (#718).
     */
    private suspend fun storedItem(itemId: UUID): (suspend () -> FindroidItem)? {
        val serverId = currentServerId() ?: return null
        database.getMovieOfServer(itemId, serverId)?.let {
            return suspend { it.toFindroidMovie(database, currentUserId) }
        }
        database.getEpisodeOfServer(itemId, serverId)?.let {
            return suspend { it.toFindroidEpisode(database, currentUserId) }
        }
        database.getShowOfServer(itemId, serverId)?.let {
            return suspend { it.toFindroidShow(database, currentUserId) }
        }
        database.getSeasonOfServer(itemId, serverId)?.let {
            return suspend { it.toFindroidSeason(database, currentUserId) }
        }
        return null
    }

    override suspend fun getItems(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
        filters: List<ItemFilter>?,
        startIndex: Int?,
        limit: Int?,
    ): List<FindroidItem> =
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
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
        filters: List<ItemFilter>?,
        startIndex: Int?,
        limit: Int?,
        includeTotal: Boolean,
    ): ItemsPage =
        withContext(Dispatchers.IO) {
            val result =
                jellyfinApi.libraryApi
                    .getItems(
                        currentUserId,
                        parentId = parentId,
                        includeItemTypes = includeTypes,
                        recursive = recursive,
                        sortBy = listOf(ItemSortBy.fromName(sortBy.sortString)),
                        sortOrder = listOf(ItemSortOrder.fromName(sortOrder.sortString)),
                        filters = filters,
                        startIndex = startIndex,
                        limit = limit,
                        // Cards read the primary image and the first backdrop. The server counts
                        // only for the first page of a pager (see getItemsPagingIndexed).
                        imageTypeLimit = 1,
                        enableImageTypes = ROW_IMAGE_TYPES,
                        enableTotalRecordCount = includeTotal,
                    )
                    .content
            ItemsPage(
                items = result.items.toFindroidItems(),
                totalRecordCount = if (includeTotal) result.totalRecordCount else null,
            )
        }

    override suspend fun getRandomItemsWithBackdrop(
        includeTypes: List<BaseItemKind>,
        limit: Int,
    ): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.libraryApi
                .getItems(
                    currentUserId,
                    includeItemTypes = includeTypes,
                    recursive = true,
                    sortBy = listOf(ItemSortBy.RANDOM),
                    imageTypes = listOf(ImageType.BACKDROP),
                    limit = limit,
                )
                .content
                .items
                .toFindroidItems()
        }

    override suspend fun getItemsPaging(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
        filters: List<ItemFilter>?,
    ): Flow<PagingData<FindroidItem>> {
        return Pager(
                config =
                    PagingConfig(
                        pageSize = LIBRARY_PAGE_SIZE,
                        prefetchDistance = LIBRARY_PAGE_SIZE,
                        initialLoadSize = LIBRARY_PAGE_SIZE * 2,
                        enablePlaceholders = false,
                    ),
                pagingSourceFactory = {
                    ItemsPagingSource(
                        this,
                        parentId,
                        includeTypes,
                        recursive,
                        sortBy,
                        sortOrder,
                        filters,
                    )
                },
            )
            .flow
    }

    override suspend fun getItemCount(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        filters: List<ItemFilter>?,
        nameLessThan: String?,
    ): Int =
        withContext(Dispatchers.IO) {
            jellyfinApi.libraryApi
                .getItems(
                    currentUserId,
                    parentId = parentId,
                    includeItemTypes = includeTypes,
                    recursive = recursive,
                    filters = filters,
                    nameLessThan = nameLessThan,
                    limit = 0,
                    enableTotalRecordCount = true,
                    enableImages = false,
                    enableUserData = false,
                )
                .content
                .totalRecordCount
        }

    override suspend fun getPerson(personId: UUID): FindroidPerson =
        withContext(Dispatchers.IO) {
            jellyfinApi.libraryApi
                .getItem(personId, currentUserId)
                .content
                .toFindroidPerson(this@JellyfinRepositoryImpl)
        }

    override suspend fun getPersonItems(
        personIds: List<UUID>,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        limit: Int?,
    ): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.libraryApi
                .getItems(
                    currentUserId,
                    personIds = personIds,
                    includeItemTypes = includeTypes,
                    recursive = recursive,
                    limit = limit,
                    enableTotalRecordCount = false,
                )
                .content
                .items
                .toFindroidItems()
        }

    override suspend fun getFavoriteItems(): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.libraryApi
                .getItems(
                    currentUserId,
                    filters = listOf(ItemFilter.IS_FAVORITE),
                    includeItemTypes =
                        listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE),
                    recursive = true,
                    enableTotalRecordCount = false,
                )
                .content
                .items
                .toFindroidItems()
        }

    override suspend fun getSearchItems(
        query: String,
        itemKinds: List<BaseItemKind>?,
    ): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            if (itemKinds == null) {
                return@withContext searchItems(
                    query,
                    listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                    SEARCH_LIMIT,
                )
            }
            itemKinds
                .map { kind -> async { searchItems(query, listOf(kind), SEARCH_LIMIT_PER_KIND) } }
                .awaitAll()
                .flatten()
        }

    private suspend fun searchItems(
        query: String,
        itemKinds: List<BaseItemKind>,
        limit: Int,
    ): List<FindroidItem> =
        jellyfinApi.libraryApi
            .getItems(
                currentUserId,
                searchTerm = query,
                includeItemTypes = itemKinds,
                recursive = true,
                limit = limit,
                enableTotalRecordCount = false,
            )
            .content
            .items
            .toFindroidItems()

    override suspend fun getSuggestions(): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.suggestionApi
                .getSuggestions(
                    currentUserId,
                    limit = 6,
                    type = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                    enableTotalRecordCount = false,
                )
                .content
                .items
                .toFindroidItems()
        }

    override suspend fun getResumeItems(): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.libraryApi
                .getResumeItems(
                    currentUserId,
                    limit = 12,
                    mediaTypes = listOf(MediaType.VIDEO),
                    includeItemTypes =
                        listOf(
                            BaseItemKind.MOVIE,
                            BaseItemKind.EPISODE,
                            BaseItemKind.VIDEO,
                            BaseItemKind.MUSIC_VIDEO,
                        ),
                    // The TV hero shows it, and Jellyfin leaves it out of Resume, Latest and Next
                    // Up unless asked (#410).
                    fields = listOf(ItemFields.OVERVIEW),
                    imageTypeLimit = 1,
                    enableImageTypes = WIDE_ROW_IMAGE_TYPES,
                    enableTotalRecordCount = false,
                )
                .content
                .items
                .toFindroidItems()
        }

    override suspend fun getLatestMedia(
        parentId: UUID,
        includeItemTypes: List<BaseItemKind>?,
    ): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.libraryApi
                .getLatestMedia(
                    currentUserId,
                    parentId = parentId,
                    includeItemTypes = includeItemTypes,
                    fields = listOf(ItemFields.OVERVIEW),
                    limit = 16,
                    imageTypeLimit = 1,
                    enableImageTypes = ROW_IMAGE_TYPES,
                )
                .content
                .toFindroidItems()
        }

    override suspend fun getSeasons(seriesId: UUID, offline: Boolean): List<FindroidSeason> =
        withContext(Dispatchers.IO) {
            if (!offline) {
                jellyfinApi.showApi.getSeasons(seriesId, currentUserId).content.items.map {
                    it.toFindroidSeason(this@JellyfinRepositoryImpl)
                }
            } else {
                // CE (#718): the current server's stored seasons only.
                val serverId = currentServerId() ?: return@withContext emptyList()
                database.getSeasonsByShowIdOfServer(seriesId, serverId).map {
                    it.toFindroidSeason(database, currentUserId)
                }
            }
        }

    override suspend fun getNextUp(seriesId: UUID?): List<FindroidEpisode> =
        withContext(Dispatchers.IO) {
            jellyfinApi.showApi
                .getNextUp(
                    currentUserId,
                    limit = 24,
                    seriesId = seriesId,
                    fields = listOf(ItemFields.OVERVIEW),
                    imageTypeLimit = 1,
                    enableImageTypes = WIDE_ROW_IMAGE_TYPES,
                    nextUpDateCutoff =
                        nextUpDateCutoff(
                            seriesId = seriesId,
                            maxDays = appPreferences.getValue(appPreferences.homeNextUpMaxDays),
                            now = LocalDateTime.now(),
                        ),
                    enableTotalRecordCount = false,
                    enableResumable = false,
                )
                .content
                .items
                .mapNotNull { it.toFindroidEpisode(this@JellyfinRepositoryImpl) }
        }

    override suspend fun getEpisodes(
        seriesId: UUID,
        seasonId: UUID?,
        fields: List<ItemFields>?,
        startItemId: UUID?,
        limit: Int?,
        offline: Boolean,
    ): List<FindroidEpisode> =
        withContext(Dispatchers.IO) {
            if (!offline) {
                jellyfinApi.showApi
                    .getEpisodes(
                        seriesId,
                        currentUserId,
                        seasonId = seasonId,
                        fields = fields,
                        startItemId = startItemId,
                        limit = limit,
                    )
                    .content
                    .items
                    .let { items ->
                        val local = localSourcesFor(items.map { it.id })
                        items.mapNotNull {
                            it.toFindroidEpisode(this@JellyfinRepositoryImpl, local)
                        }
                    }
            } else {
                // CE (#718): the current server's stored episodes only.
                val serverId = currentServerId() ?: return@withContext emptyList()
                val episodes =
                    if (seasonId != null) database.getEpisodesBySeasonIdOfServer(seasonId, serverId)
                    else database.getEpisodesByShowIdOfServer(seriesId, serverId)
                episodes.map { it.toFindroidEpisode(database, currentUserId) }
            }
        }

    override suspend fun getMediaSources(
        itemId: UUID,
        includePath: Boolean,
        transcodeDolbyVision: Boolean,
        maxBitrate: Int?,
    ): List<FindroidSource> =
        withContext(Dispatchers.IO) {
            // The downloader's path. The profile direct-plays everything (→ original
            // file) unless transcodeDolbyVision is set (Dolby Vision routed through a
            // progressive H.264 transcode) or the user picked a download quality cap
            // (files over the cap transcoded down to it). Everything else stays the
            // original file.
            val sources = mutableListOf<FindroidSource>()
            sources.addAll(
                jellyfinApi.mediaInfoApi
                    .getPostedPlaybackInfo(
                        itemId,
                        PlaybackInfoDto(
                            userId = currentUserId,
                            deviceProfile =
                                deviceProfileBuilder.getDownloadProfile(
                                    transcodeDolbyVision,
                                    bitrateCapped = maxBitrate != null,
                                ),
                            maxStreamingBitrate = maxBitrate ?: 1_000_000_000,
                            enableTranscoding = transcodeDolbyVision || maxBitrate != null,
                            // Force a real video re-encode when a transcode happens —
                            // never let the server copy the DV bitstream into the
                            // container, and never let a stream copy defeat the cap.
                            allowVideoStreamCopy = false,
                            allowAudioStreamCopy = true,
                        ),
                    )
                    .content
                    .mediaSources
                    .map { it.toFindroidSource(this@JellyfinRepositoryImpl, itemId, includePath) }
            )
            // CE (#718): the current server's download of the item, not another server's.
            currentServerId()?.let { serverId ->
                sources.addAll(
                    database.getSourcesOfServer(itemId, serverId).map {
                        it.toFindroidSource(database)
                    }
                )
            }
            sources
        }

    override suspend fun getPlaybackSources(
        itemId: UUID,
        mediaSourceId: String?,
        audioStreamIndex: Int?,
    ): List<FindroidSource> =
        withContext(Dispatchers.IO) {
            // A downloaded copy wins over any server source (see PlaylistManager), so do not wait
            // for the server when it is the one that will play. Only a finished one: an unfinished
            // download's partial file would play as far as it got, or not at all (#454). CE (#718):
            // and only a download stored for the current server.
            val localSources =
                currentServerId()
                    ?.let { database.getCompletedSources(itemId, it) }
                    .orEmpty()
                    .map { it.toFindroidSource(database) }
            if (
                localSources.isNotEmpty() &&
                    (mediaSourceId == null || localSources.any { it.id == mediaSourceId })
            ) {
                return@withContext localSources
            }

            // mpv software-decodes practically everything, so keep it on the permissive
            // direct-play profile. ExoPlayer gets the honest, hardware-probed profile so
            // the server transcodes anything it cannot direct-play, such as Dolby Vision
            // without a DV decoder.
            val useDirectPlay = appPreferences.getValue(appPreferences.playerBackend) == "mpv"
            // User-selected bitrate cap (player quality selector). 0 = original quality.
            // Files above the cap get a server-side transcode; files below it still
            // direct-play. mpv's plain profile has no transcoding profiles, so a cap
            // switches it to the variant that carries the HLS fallback.
            val maxBitrate =
                appPreferences.getValue(appPreferences.playerMaxBitrate).takeIf { it > 0 }
            val deviceProfile =
                when {
                    useDirectPlay && maxBitrate != null ->
                        deviceProfileBuilder.getDirectPlayTranscodeFallbackProfile()
                    useDirectPlay -> deviceProfileBuilder.getDirectPlayProfile()
                    else ->
                        deviceProfileBuilder.getDeviceProfile(
                            appPreferences.getValue(appPreferences.playerDirectPlayDolbyVision)
                        )
                }
            val enableTranscoding = !useDirectPlay || maxBitrate != null
            val sources = mutableListOf<FindroidSource>()
            val playbackInfo =
                jellyfinApi.mediaInfoApi
                    .getPostedPlaybackInfo(
                        itemId,
                        PlaybackInfoDto(
                            userId = currentUserId,
                            deviceProfile = deviceProfile,
                            maxStreamingBitrate = maxBitrate ?: deviceProfile.maxStreamingBitrate,
                            enableTranscoding = enableTranscoding,
                            allowVideoStreamCopy = true,
                            allowAudioStreamCopy = true,
                            autoOpenLiveStream = enableTranscoding,
                            // CE (#580): the server ignores the index without the source's id.
                            mediaSourceId = mediaSourceId.takeIf { audioStreamIndex != null },
                            audioStreamIndex = audioStreamIndex.takeIf { mediaSourceId != null },
                        ),
                    )
                    .content
            // CE (#728): each transcode carries its play session; the player records it when it
            // loads the stream (setPlaySession), not here: a switch that fails keeps the old one.
            val playSessionId = playbackInfo.playSessionId?.takeIf { it.isNotBlank() }
            sources.addAll(
                playbackInfo.mediaSources.map {
                    val source =
                        it.toFindroidSource(this@JellyfinRepositoryImpl, itemId, includePath = true)
                    if (source.transcoded) source.copy(playSessionId = playSessionId) else source
                }
            )
            sources.addAll(localSources)
            sources
        }

    override suspend fun getSeriesTrackPreferences(seriesId: UUID): List<SeriesTrackPreferenceDto> =
        withContext(Dispatchers.IO) { database.getSeriesTrackPreferences(currentUserId, seriesId) }

    override suspend fun getStreamUrl(itemId: UUID, mediaSourceId: String): String =
        withContext(Dispatchers.IO) {
            try {
                jellyfinApi.videoApi.getVideoStreamUrl(
                    itemId,
                    static = true,
                    mediaSourceId = mediaSourceId,
                )
            } catch (e: Exception) {
                Timber.e(e)
                ""
            }
        }

    override suspend fun getSegments(itemId: UUID): List<FindroidSegment> =
        withContext(Dispatchers.IO) {
            val databaseSegments = database.getSegments(itemId).map { it.toFindroidSegment() }

            if (databaseSegments.isNotEmpty()) {
                return@withContext databaseSegments
            }

            getServerSegments(itemId)
        }

    override suspend fun getServerSegments(itemId: UUID): List<FindroidSegment> =
        withContext(Dispatchers.IO) {
            try {
                val apiSegments =
                    jellyfinApi.mediaSegmentApi.getItemSegments(itemId).content.items.map {
                        it.toFindroidSegment()
                    }

                return@withContext apiSegments
            } catch (e: Exception) {
                Timber.e(e)
                return@withContext emptyList()
            }
        }

    override suspend fun getTrickplayData(itemId: UUID, width: Int, index: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                try {
                    val sources = File(context.filesDir, "trickplay/$itemId").listFiles()
                    if (sources != null) {
                        return@withContext File(sources.first(), index.toString()).readBytes()
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to read local trickplay data")
                }

                return@withContext jellyfinApi.trickPlayApi
                    .getTrickplayTileImage(itemId, width, index)
                    .content
            } catch (_: Exception) {
                return@withContext null
            }
        }

    override suspend fun getThemeSongUrl(itemId: UUID): String? =
        withContext(Dispatchers.IO) {
            try {
                val song =
                    jellyfinApi.libraryApi
                        .getThemeSongs(itemId, currentUserId, inheritFromParent = true)
                        .content
                        .items
                        .firstOrNull() ?: return@withContext null
                jellyfinApi.api.audioApi.getAudioStreamUrl(song.id, static = true)
            } catch (e: Exception) {
                Timber.e(e)
                null
            }
        }

    override suspend fun getSimilarItems(itemId: UUID, limit: Int): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.libraryApi
                .getSimilarItems(itemId, userId = currentUserId, limit = limit)
                .content
                .items
                .filter { it.id != itemId }
                .toFindroidItems()
        }

    override suspend fun getExtras(
        itemId: UUID,
        localTrailers: Boolean,
        specialFeatures: Boolean,
    ): List<FindroidMovie> =
        withContext(Dispatchers.IO) {
            val trailersAsync = async {
                if (localTrailers) {
                    jellyfinApi.libraryApi.getLocalTrailers(itemId, currentUserId).content
                } else emptyList()
            }
            val featuresAsync = async {
                if (specialFeatures) {
                    jellyfinApi.libraryApi.getSpecialFeatures(itemId, currentUserId).content
                } else emptyList()
            }
            (trailersAsync.await() + featuresAsync.await())
                .distinctBy { it.id }
                .map { it.toFindroidMovie(this@JellyfinRepositoryImpl, emptyMap()) }
        }

    override suspend fun getItemCollections(itemId: UUID): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.libraryApi
                .getItemCollections(itemId, userId = currentUserId)
                .content
                .items
                .toFindroidItems()
        }

    override suspend fun postCapabilities() {
        Timber.d("Sending capabilities")
        withContext(Dispatchers.IO) {
            jellyfinApi.sessionApi.postCapabilities(
                playableMediaTypes = listOf(MediaType.VIDEO),
                supportedCommands =
                    listOf(
                        GeneralCommandType.VOLUME_UP,
                        GeneralCommandType.VOLUME_DOWN,
                        GeneralCommandType.TOGGLE_MUTE,
                        GeneralCommandType.SET_AUDIO_STREAM_INDEX,
                        GeneralCommandType.SET_SUBTITLE_STREAM_INDEX,
                        GeneralCommandType.MUTE,
                        GeneralCommandType.UNMUTE,
                        GeneralCommandType.SET_VOLUME,
                        GeneralCommandType.DISPLAY_MESSAGE,
                        GeneralCommandType.PLAY,
                        GeneralCommandType.PLAY_STATE,
                        GeneralCommandType.PLAY_NEXT,
                        GeneralCommandType.PLAY_MEDIA_SOURCE,
                    ),
                supportsMediaControl = true,
            )
        }
    }

    override fun setPlaySession(itemId: UUID, playSessionId: String?, transcodes: Boolean) =
        playSessions.onPlaybackInfo(itemId, playSessionId, transcodes)

    override suspend fun postPlaybackStart(itemId: UUID, transcoded: Boolean) {
        // CE (#599): Transcode with the transcode's play session, see PlaySessions.playReport.
        val report = playSessions.playReport(itemId, transcoded)
        Timber.d("Sending start $itemId, ${report.playMethod}")
        withContext(Dispatchers.IO) {
            // CE (#637): read alongside the start report, not before it, so a slow or failing read
            // does not hold up the start. The player waits for this call before it reports the
            // stop (up to 5 s), so the stop of this item still has them.
            coroutineScope {
                launch { readResumeThresholds() }
                jellyfinApi.sessionApi.reportPlaybackStart(
                    PlaybackStartInfo(
                        itemId = itemId,
                        canSeek = true,
                        isPaused = false,
                        isMuted = false,
                        playMethod = report.playMethod,
                        playSessionId = report.playSessionId,
                        repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT,
                    )
                )
            }
        }
    }

    override suspend fun postPlaybackStop(
        itemId: UUID,
        positionTicks: Long,
        playedPercentage: Int?,
    ) {
        Timber.d("Sending stop $itemId")
        withContext(Dispatchers.IO) {
            // CE (#637): the server applies its resume settings to this stop; the local copy
            // follows them.
            val userData =
                stopUserData(
                    positionTicks,
                    playedPercentage,
                    runtimeTicks = database.getRuntimeTicks(itemId),
                    thresholds = resumeThresholds.get(currentServerId()),
                )
            val stop = suspend {
                jellyfinApi.sessionApi.reportPlaybackStopped(
                    PlaybackStopInfo(
                        itemId = itemId,
                        positionTicks = positionTicks,
                        failed = false,
                        // With it the server ends the item's transcode now, not a minute later.
                        playSessionId = playSessions.sessionOf(itemId),
                    )
                )
                Unit
            }
            if (userData == null) {
                // Nothing local changes without user data, so there is nothing to sync later.
                try {
                    stop()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
                return@withContext
            }
            reportUserData(itemId, report = stop) { userId ->
                setPlaybackPositionTicks(itemId, userId, userData.positionTicks)
                userData.played?.let { setPlayed(userId, itemId, it) }
                // CE (#633): sent along if this stop has to be synced later.
                setLastPlayedDate(userId, itemId, System.currentTimeMillis())
            }
        }
    }

    override suspend fun postPlaybackProgress(
        itemId: UUID,
        positionTicks: Long,
        isPaused: Boolean,
        transcoded: Boolean,
    ) {
        Timber.d("Posting progress of $itemId, position: $positionTicks")
        val report = playSessions.playReport(itemId, transcoded)
        withContext(Dispatchers.IO) {
            reportUserData(
                itemId,
                report = {
                    jellyfinApi.sessionApi.reportPlaybackProgress(
                        PlaybackProgressInfo(
                            itemId = itemId,
                            canSeek = true,
                            isPaused = isPaused,
                            isMuted = false,
                            playMethod = report.playMethod,
                            playSessionId = report.playSessionId,
                            repeatMode = RepeatMode.REPEAT_NONE,
                            playbackOrder = PlaybackOrder.DEFAULT,
                            positionTicks = positionTicks,
                        )
                    )
                },
            ) { userId ->
                setPlaybackPositionTicks(itemId, userId, positionTicks)
                // CE (#633): sent along if this progress has to be synced later.
                setLastPlayedDate(userId, itemId, System.currentTimeMillis())
            }
        }
    }

    override suspend fun markAsFavorite(itemId: UUID) {
        withContext(Dispatchers.IO) {
            reportUserData(itemId, report = { jellyfinApi.userDataApi.markFavoriteItem(itemId) }) {
                userId ->
                setFavorite(userId, itemId, true)
            }
        }
    }

    override suspend fun unmarkAsFavorite(itemId: UUID) {
        withContext(Dispatchers.IO) {
            reportUserData(
                itemId,
                report = { jellyfinApi.userDataApi.unmarkFavoriteItem(itemId) },
            ) { userId ->
                setFavorite(userId, itemId, false)
            }
        }
    }

    override suspend fun markAsPlayed(itemId: UUID) {
        withContext(Dispatchers.IO) {
            reportUserData(itemId, report = { jellyfinApi.userDataApi.markPlayedItem(itemId) }) {
                userId ->
                setPlayed(userId, itemId, true)
                // CE (#633): as the server does for a played mark without a date.
                setLastPlayedDateIfUnknown(userId, itemId, System.currentTimeMillis())
            }
        }
    }

    override suspend fun markAsUnplayed(itemId: UUID) {
        withContext(Dispatchers.IO) {
            // CE (#635): the time of the mark, taken before the request: a request that fails only
            // after a timeout must not make the mark look later than a play elsewhere meanwhile.
            val markedAt = System.currentTimeMillis()
            val reached =
                reportUserData(
                    itemId,
                    report = { jellyfinApi.userDataApi.markUnplayedItem(itemId) },
                ) { userId ->
                    setPlayed(userId, itemId, false)
                    // CE (#635): kept until the sync sends the mark, which cannot clear the
                    // server's; with it the sync sees the mark is not older than the play it
                    // undoes. A row without one gets the time of the mark, or it would always count
                    // as older.
                    setLastPlayedDateIfUnknown(userId, itemId, markedAt)
                }
            // CE (#633): the server clears it for an unplayed mark too.
            if (reached) database.setLastPlayedDate(currentUserId, itemId, null)
        }
    }

    /** See the top-level [writeAndReport]: the change is written and marked before [report]. */
    private suspend fun reportUserData(
        itemId: UUID,
        report: suspend () -> Unit,
        write: suspend ServerDatabaseDao.(userId: UUID) -> Unit,
    ): Boolean {
        val userId = currentUserId
        return writeAndReport(database, syncRequester, userId, itemId, { write(userId) }, report)
    }

    override fun getBaseUrl() = jellyfinApi.api.baseUrl.orEmpty()

    override suspend fun updateDeviceName(name: String) {
        withContext(Dispatchers.IO) {
            jellyfinApi.jellyfin.deviceInfo?.id?.let { id ->
                jellyfinApi.deviceApi.updateDeviceOptions(
                    id,
                    DeviceOptionsDto(0, customName = name),
                )
            }
        }
    }

    override suspend fun refreshCurrentUserImage() {
        withContext(Dispatchers.IO) {
            val user = jellyfinApi.userApi.getCurrentUser().content
            database.updateUserPrimaryImageTag(user.id, user.primaryImageTag)
        }
    }

    override suspend fun getUserConfiguration(): UserConfiguration =
        withContext(Dispatchers.IO) { jellyfinApi.userApi.getCurrentUser().content.configuration!! }

    override suspend fun getDownloads(): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            val serverId =
                appPreferences.getValue(appPreferences.currentServer)
                    ?: return@withContext emptyList()
            val items = mutableListOf<FindroidItem>()
            items.addAll(
                database.getMoviesByServerId(serverId).map {
                    it.toFindroidMovie(database, currentUserId)
                }
            )
            items.addAll(
                database.getShowsByServerId(serverId).map {
                    it.toFindroidShow(database, currentUserId)
                }
            )
            items
        }

    override fun getUserId(): UUID {
        return currentUserId
    }

    /**
     * Whether any source (download) is stored at all. Kept up to date by a Room observer, so a
     * download that starts, finishes or is deleted while the app runs flips it. Null until the
     * first value arrives.
     */
    private val hasLocalSources: StateFlow<Boolean?> =
        database
            .observeHasSources()
            .map<Boolean, Boolean?> { it }
            .catch { Timber.w(it, "Couldn't observe the sources table") }
            .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Eagerly, null)

    /**
     * The downloaded sources of [itemIds], or nothing without a query when the app has no sources
     * at all (the usual case for a user who doesn't download). CE (#718): only the current server's
     * downloads.
     */
    private suspend fun localSourcesFor(itemIds: List<UUID>): Map<UUID, List<FindroidSource>> {
        val serverId = currentServerId() ?: return emptyMap()
        val any = hasLocalSources.value ?: database.hasSources()
        return if (any) database.getLocalSourcesForItems(itemIds, serverId) else emptyMap()
    }

    /** Maps a list with one batched lookup of downloaded sources instead of one query per item. */
    private suspend fun List<BaseItemDto>.toFindroidItems(): List<FindroidItem> {
        val local =
            localSourcesFor(
                filter { it.type == BaseItemKind.MOVIE || it.type == BaseItemKind.EPISODE }
                    .map { it.id }
            )
        return mapNotNull { it.toFindroidItem(this@JellyfinRepositoryImpl, local) }
    }

    private companion object {
        /**
         * The only images row cards (home rows, next up) render: `FindroidImages` reads the primary
         * tag, the first backdrop and the series primary tag. Together with `imageTypeLimit = 1`
         * this stops the server from resolving (and sending blurhashes for) every backdrop, logo,
         * thumb and banner of each item.
         */
        val ROW_IMAGE_TYPES = listOf(ImageType.PRIMARY, ImageType.BACKDROP)

        /** CE (#608): Continue Watching and Next Up also show the thumb, the art with the title. */
        val WIDE_ROW_IMAGE_TYPES = ROW_IMAGE_TYPES + ImageType.THUMB

        /** Library grid page; a fling on a 2-4 column grid outruns anything smaller. */
        private const val LIBRARY_PAGE_SIZE = 40

        private const val SEARCH_LIMIT = 50
        private const val SEARCH_LIMIT_PER_KIND = 20
    }
}

/**
 * The Home row only shows series watched in the last [maxDays] days, as the web client's "Max days
 * in Next Up" does. 0 is no limit. A single series has no cutoff: a show last watched years ago
 * still has a next episode.
 */
internal fun nextUpDateCutoff(seriesId: UUID?, maxDays: Int, now: LocalDateTime): LocalDateTime? =
    if (seriesId == null && maxDays > 0) now.minusDays(maxDays.toLong()) else null
