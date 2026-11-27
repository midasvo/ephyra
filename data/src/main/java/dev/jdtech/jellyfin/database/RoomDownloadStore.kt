package dev.jdtech.jellyfin.database

import dev.jdtech.jellyfin.domain.userdata.PlayState
import dev.jdtech.jellyfin.models.AccountKey
import dev.jdtech.jellyfin.models.DownloadFiles
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.ItemSourceEntity
import dev.jdtech.jellyfin.models.ItemTrickplayInfo
import dev.jdtech.jellyfin.models.MediaSourceId
import dev.jdtech.jellyfin.models.MediaStream
import dev.jdtech.jellyfin.models.MediaStreamEntity
import dev.jdtech.jellyfin.models.MediaStreamId
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.PendingDownloadDto
import dev.jdtech.jellyfin.models.Season
import dev.jdtech.jellyfin.models.SeasonEpisodeCounts
import dev.jdtech.jellyfin.models.Segment
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.Show
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.getLocalSourcesForItems
import dev.jdtech.jellyfin.models.toEpisode
import dev.jdtech.jellyfin.models.toEpisodeEntity
import dev.jdtech.jellyfin.models.toItemSource
import dev.jdtech.jellyfin.models.toItemSourceEntity
import dev.jdtech.jellyfin.models.toItemTrickplayInfo
import dev.jdtech.jellyfin.models.toItemTrickplayInfoEntity
import dev.jdtech.jellyfin.models.toMediaStreamEntity
import dev.jdtech.jellyfin.models.toMovie
import dev.jdtech.jellyfin.models.toMovieEntity
import dev.jdtech.jellyfin.models.toPlayState
import dev.jdtech.jellyfin.models.toSeason
import dev.jdtech.jellyfin.models.toSeasonEntity
import dev.jdtech.jellyfin.models.toSegment
import dev.jdtech.jellyfin.models.toSegmentEntity
import dev.jdtech.jellyfin.models.toShow
import dev.jdtech.jellyfin.models.toShowEntity
import dev.jdtech.jellyfin.models.toUserDataEntity
import dev.jdtech.jellyfin.repository.DownloadList
import dev.jdtech.jellyfin.repository.DownloadMediaStreams
import dev.jdtech.jellyfin.repository.DownloadSegmentsAndTrickplay
import dev.jdtech.jellyfin.repository.DownloadSources
import dev.jdtech.jellyfin.repository.DownloadUserData
import dev.jdtech.jellyfin.repository.DownloadedItemRows
import dev.jdtech.jellyfin.repository.MarkedUserData
import dev.jdtech.jellyfin.repository.QueuedDownloads
import dev.jdtech.jellyfin.repository.StoredItemLists
import dev.jdtech.jellyfin.repository.StoredItemSources
import dev.jdtech.jellyfin.repository.StoredItems
import dev.jdtech.jellyfin.repository.StoredMediaStream
import dev.jdtech.jellyfin.repository.StoredPendingDownload
import dev.jdtech.jellyfin.repository.StoredRow
import dev.jdtech.jellyfin.repository.StoredRows
import dev.jdtech.jellyfin.repository.StoredServer
import dev.jdtech.jellyfin.repository.StoredSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The roles of the download store on Room (#899; split by role in #967: [DownloadList],
 * [DownloadedItemRows], [DownloadSources], [DownloadMediaStreams], [DownloadSegmentsAndTrickplay],
 * [DownloadUserData], [QueuedDownloads]; and, #966, the reads of the repository's stored fallback:
 * [StoredItems], [StoredItemSources], [StoredItemLists] with [RoomStoredLists]). Each method makes
 * the queries its callers made on [database] before, in the same order: the queries, their
 * transactions and the rows are unchanged.
 */
class RoomDownloadStore(private val database: ServerDatabaseDao) :
    DownloadList,
    DownloadedItemRows,
    DownloadSources,
    DownloadMediaStreams,
    DownloadSegmentsAndTrickplay,
    DownloadUserData,
    QueuedDownloads,
    StoredItems,
    StoredItemSources,
    StoredItemLists by RoomStoredLists(database) {

    override suspend fun sources(itemId: ItemId): List<StoredSource> =
        database.getSources(itemId).map { it.toStoredSource() }

    override suspend fun sourcesOfServer(itemId: ItemId, serverId: ServerId): List<StoredSource> =
        database.getSourcesOfServer(itemId, serverId).map { it.toStoredSource() }

    override suspend fun sourceByDownloadId(downloadId: Long): StoredSource? =
        database.getSourceByDownloadId(downloadId)?.toStoredSource()

    override suspend fun activeDownloadSources(): List<StoredSource> =
        database.getActiveDownloadSources().map { it.toStoredSource() }

    override suspend fun completedDownloadSources(): List<StoredSource> =
        database.getCompletedDownloadSources().map { it.toStoredSource() }

    override suspend fun insertSource(
        itemId: ItemId,
        source: ItemSource,
        path: String,
        downloadId: Long,
        downloadMaxBitrate: Int?,
        smartQueued: Boolean,
    ) {
        database.insertSource(
            source
                .toItemSourceEntity(itemId, path)
                .copy(
                    downloadId = downloadId,
                    downloadMaxBitrate = downloadMaxBitrate,
                    smartQueued = smartQueued,
                )
        )
    }

    override suspend fun setSourceDownloaded(id: MediaSourceId, path: String, downloadedAt: Long) {
        database.setSourceDownloaded(id, path, downloadedAt)
    }

    override suspend fun deleteSource(id: MediaSourceId) {
        database.deleteSource(id)
    }

    override suspend fun itemSource(source: StoredSource): ItemSource =
        source.toEntity().toItemSource(database)

    override suspend fun localSourcesOfItems(
        itemIds: Collection<ItemId>
    ): Map<ItemId, List<ItemSource>> = database.getLocalSourcesForItems(itemIds)

    override suspend fun clearSmartQueued(itemId: ItemId) {
        database.clearPendingDownloadSmartQueued(itemId)
        database.clearSourceSmartQueued(itemId)
    }

    override suspend fun seriesIdsWithCompletedDownloads(serverId: ServerId): List<ItemId> =
        database.getSeriesIdsWithCompletedDownloads(serverId)

    override suspend fun mediaStreamsOfSource(sourceId: MediaSourceId): List<StoredMediaStream> =
        database.getMediaStreamsBySourceId(sourceId).map { it.toStoredMediaStream() }

    override suspend fun mediaStreamByDownloadId(downloadId: Long): StoredMediaStream? =
        database.getMediaStreamByDownloadId(downloadId)?.toStoredMediaStream()

    override suspend fun insertMediaStream(
        id: MediaStreamId,
        sourceId: MediaSourceId,
        stream: MediaStream,
        path: String,
    ) {
        database.insertMediaStream(stream.toMediaStreamEntity(id, sourceId, path))
    }

    override suspend fun setMediaStreamDownloadId(id: MediaStreamId, downloadId: Long) {
        database.setMediaStreamDownloadId(id, downloadId)
    }

    override suspend fun setMediaStreamPath(id: MediaStreamId, path: String) {
        database.setMediaStreamPath(id, path)
    }

    override suspend fun deleteMediaStream(id: MediaStreamId) {
        database.deleteMediaStream(id)
    }

    override suspend fun deleteMediaStreamsOfSource(sourceId: MediaSourceId) {
        database.deleteMediaStreamsBySourceId(sourceId)
    }

    override suspend fun insertMovie(movie: Movie, serverId: ServerId?) {
        database.insertMovie(movie.toMovieEntity(serverId))
    }

    override suspend fun insertEpisode(
        episode: Episode,
        show: Show,
        season: Season,
        serverId: ServerId?,
    ) {
        database.insertShow(show.toShowEntity(serverId))
        database.insertSeason(season.toSeasonEntity())
        database.insertEpisode(episode.toEpisodeEntity(serverId))
    }

    override suspend fun storedServerOfMovie(id: ItemId): StoredServer? =
        database.getMovieOrNull(id)?.let { StoredServer(it.serverId) }

    override suspend fun storedServerOfEpisode(id: ItemId): StoredServer? =
        database.getEpisodeOrNull(id)?.let { StoredServer(it.serverId) }

    override suspend fun movie(id: ItemId, userId: UserId): Movie =
        database.getMovie(id).toMovie(database, userId)

    override suspend fun episode(id: ItemId, userId: UserId): Episode =
        database.getEpisode(id).toEpisode(database, userId)

    override suspend fun movieOrNull(id: ItemId, userId: UserId): Movie? =
        database.getMovieOrNull(id)?.toMovie(database, userId)

    override suspend fun episodeOrNull(id: ItemId, userId: UserId): Episode? =
        database.getEpisodeOrNull(id)?.toEpisode(database, userId)

    override suspend fun deleteMovie(id: ItemId) {
        database.deleteMovie(id)
    }

    override suspend fun deleteEpisode(id: ItemId) {
        database.deleteEpisode(id)
    }

    override suspend fun episodeIdsOfSeason(seasonId: ItemId): List<ItemId> =
        database.getEpisodesBySeasonId(seasonId).map { it.id }

    override suspend fun seasonIdsOfShow(showId: ItemId): List<ItemId> =
        database.getSeasonsByShowId(showId).map { it.id }

    override suspend fun deleteSeasonAndUserData(id: ItemId) {
        database.deleteSeason(id)
        database.deleteUserData(id)
    }

    override suspend fun deleteShowAndUserData(id: ItemId) {
        database.deleteShow(id)
        database.deleteUserData(id)
    }

    override suspend fun allItemIds(): List<ItemId> = database.getAllItemIds()

    override suspend fun episodeIdsOfShow(showId: ItemId, serverId: ServerId): List<ItemId> =
        database.getEpisodesByShowIdOfServer(showId, serverId).map { it.id }

    override fun episodesOfShow(account: AccountKey, showId: ItemId): Flow<Episode> = flow {
        for (entity in database.getEpisodesByShowIdOfServer(showId, account.serverId)) {
            emit(entity.toEpisode(database, account.userId))
        }
    }

    override suspend fun downloadedEpisodesOfShow(
        account: AccountKey,
        showId: ItemId,
    ): List<Episode> {
        val allEpisodes = database.getEpisodesByShowIdOfServer(showId, account.serverId)
        // One query for every episode's sources, reused for the filter and the conversion.
        val sourcesByEpisode = database.getLocalSourcesForItems(allEpisodes.map { it.id })
        return allEpisodes
            .filter { ep ->
                sourcesByEpisode[ep.id].orEmpty().any { !DownloadFiles.isPartial(it.path) }
            }
            .map { it.toEpisode(database, account.userId, sourcesByEpisode) }
    }

    override suspend fun seasonEpisodeCounts(
        showId: ItemId,
        serverId: ServerId,
    ): List<SeasonEpisodeCounts> = database.getSeasonEpisodeCounts(showId, serverId)

    override suspend fun replaceSegments(itemId: ItemId, segments: List<Segment>) {
        database.replaceSegments(itemId, segments.map { it.toSegmentEntity(itemId) })
    }

    override suspend fun deleteSegments(itemId: ItemId) {
        database.deleteSegments(itemId)
    }

    override suspend fun trickplayInfo(sourceId: MediaSourceId): ItemTrickplayInfo? =
        database.getTrickplayInfo(sourceId)?.toItemTrickplayInfo()

    override suspend fun insertTrickplayInfo(sourceId: MediaSourceId, info: ItemTrickplayInfo) {
        database.insertTrickplayInfo(info.toItemTrickplayInfoEntity(sourceId))
    }

    override suspend fun storeUserDataOfDownload(userId: UserId, item: Item) {
        if (database.getUserDataToBeSynced(userId, item.id) == null) {
            database.insertUserData(item.toUserDataEntity(userId))
        }
    }

    override suspend fun hasUserDataToBeSynced(userId: UserId, itemId: ItemId): Boolean =
        database.getUserDataToBeSynced(userId, itemId) != null

    override suspend fun deleteSyncedUserData(itemId: ItemId) {
        database.deleteSyncedUserData(itemId)
    }

    override suspend fun writeAndMarkToBeSynced(
        userId: UserId,
        itemId: ItemId,
        change: (PlayState) -> PlayState,
    ): MarkedUserData {
        var written: PlayState? = null
        val marked =
            // The DAO the transaction runs on is [database]; named so, not this class's own
            // updatePlayState.
            database.writeAndMarkToBeSynced(userId, itemId) {
                database.updatePlayState(userId, itemId, change)
                written = database.getUserData(itemId, userId)?.toPlayState()
            }
        return MarkedUserData(marked = marked > 0, written = written)
    }

    override suspend fun markToBeSynced(userId: UserId, itemId: ItemId): Boolean =
        database.markUserDataToBeSynced(userId, itemId) > 0

    override suspend fun clearToBeSynced(userId: UserId, itemId: ItemId, written: PlayState) {
        database.clearUserDataToBeSynced(
            userId = userId,
            itemId = itemId,
            played = written.played,
            favorite = written.favorite,
            playbackPositionTicks = written.positionTicks,
            lastPlayedDate = written.lastPlayedMillis,
        )
    }

    override suspend fun updatePlayState(
        userId: UserId,
        itemId: ItemId,
        change: (PlayState) -> PlayState?,
    ) {
        database.updatePlayState(userId, itemId, change)
    }

    override suspend fun segments(itemId: ItemId): List<Segment> =
        database.getSegments(itemId).map { it.toSegment() }

    override suspend fun movieOfServer(id: ItemId, serverId: ServerId): StoredRow<Movie>? =
        database.getMovieOfServer(id, serverId)?.let { row ->
            StoredRow { userId -> row.toMovie(database, userId) }
        }

    override suspend fun episodeOfServer(id: ItemId, serverId: ServerId): StoredRow<Episode>? =
        database.getEpisodeOfServer(id, serverId)?.let { row ->
            StoredRow { userId -> row.toEpisode(database, userId) }
        }

    override suspend fun showOfServer(id: ItemId, serverId: ServerId): StoredRow<Show>? =
        database.getShowOfServer(id, serverId)?.let { row ->
            StoredRow { userId -> row.toShow(database, userId) }
        }

    override suspend fun seasonOfServer(id: ItemId, serverId: ServerId): StoredRow<Season>? =
        database.getSeasonOfServer(id, serverId)?.let { row ->
            StoredRow { userId -> row.toSeason(database, userId) }
        }

    override suspend fun seasonsOfShow(showId: ItemId, serverId: ServerId): StoredRows<Season> =
        RoomStoredRows(database.getSeasonsByShowIdOfServer(showId, serverId)) { rows, userId ->
            rows.map { it.toSeason(database, userId) }
        }

    override suspend fun episodesOfSeason(
        seasonId: ItemId,
        serverId: ServerId,
    ): StoredRows<Episode> =
        RoomStoredRows(database.getEpisodesBySeasonIdOfServer(seasonId, serverId)) { rows, userId ->
            rows.map { it.toEpisode(database, userId) }
        }

    override suspend fun episodesOfSeries(
        seriesId: ItemId,
        serverId: ServerId,
    ): StoredRows<Episode> =
        RoomStoredRows(database.getEpisodesByShowIdOfServer(seriesId, serverId)) { rows, userId ->
            rows.map { it.toEpisode(database, userId) }
        }

    override suspend fun moviesOfServer(serverId: ServerId): StoredRows<Movie> =
        RoomStoredRows(database.getMoviesByServerId(serverId)) { rows, userId ->
            rows.map { it.toMovie(database, userId) }
        }

    override suspend fun showsOfServer(serverId: ServerId): StoredRows<Show> =
        RoomStoredRows(database.getShowsByServerId(serverId)) { rows, userId ->
            rows.map { it.toShow(database, userId) }
        }

    override suspend fun runtimeTicks(itemId: ItemId): Long? = database.getRuntimeTicks(itemId)

    override suspend fun allSourcesOfServer(itemId: ItemId, serverId: ServerId): List<ItemSource> =
        database.getSourcesOfServer(itemId, serverId).map { it.toItemSource(database) }

    override suspend fun completedSourcesOfServer(
        itemId: ItemId,
        serverId: ServerId,
    ): List<ItemSource> =
        database.getCompletedSources(itemId, serverId).map { it.toItemSource(database) }

    override suspend fun localSourcesOfServer(
        itemIds: Collection<ItemId>,
        serverId: ServerId,
    ): Map<ItemId, List<ItemSource>> = database.getLocalSourcesForItems(itemIds, serverId)

    override suspend fun hasSources(): Boolean = database.hasSources()

    override fun observeHasSources(): Flow<Boolean> = database.observeHasSources()

    override suspend fun insertPendingDownload(pending: StoredPendingDownload) {
        database.insertPendingDownload(
            PendingDownloadDto(
                itemId = pending.itemId,
                itemKind = pending.itemKind,
                addedAt = pending.addedAt,
                maxBitrate = pending.maxBitrate,
                smartQueued = pending.smartQueued,
            )
        )
    }

    override suspend fun deletePendingDownload(itemId: ItemId) {
        database.deletePendingDownload(itemId)
    }

    override suspend fun pendingDownloads(): List<StoredPendingDownload> =
        database.getPendingDownloads().map {
            StoredPendingDownload(
                itemId = it.itemId,
                itemKind = it.itemKind,
                addedAt = it.addedAt,
                maxBitrate = it.maxBitrate,
                smartQueued = it.smartQueued,
            )
        }
}

private fun ItemSourceEntity.toStoredSource() =
    StoredSource(
        id = id,
        itemId = itemId,
        name = name,
        type = type,
        path = path,
        downloadId = downloadId,
        downloadMaxBitrate = downloadMaxBitrate,
        downloadedAt = downloadedAt,
        smartQueued = smartQueued,
    )

private fun StoredSource.toEntity() =
    ItemSourceEntity(
        id = id,
        itemId = itemId,
        name = name,
        type = type,
        path = path,
        downloadId = downloadId,
        downloadMaxBitrate = downloadMaxBitrate,
        downloadedAt = downloadedAt,
        smartQueued = smartQueued,
    )

private fun MediaStreamEntity.toStoredMediaStream() =
    StoredMediaStream(
        id = id,
        sourceId = sourceId,
        title = title,
        language = language,
        type = type,
        codec = codec,
        path = path,
        downloadId = downloadId,
    )
