package dev.jdtech.jellyfin.database

import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.EpisodeEntity
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemFilter
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemImages
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.Library
import dev.jdtech.jellyfin.models.MovieEntity
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.ShowEntity
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.models.UserDataEntity
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.getLocalSourcesForItems
import dev.jdtech.jellyfin.models.localImagePath
import dev.jdtech.jellyfin.models.toEpisode
import dev.jdtech.jellyfin.models.toMovie
import dev.jdtech.jellyfin.models.toShow
import dev.jdtech.jellyfin.repository.StoredItemLists
import dev.jdtech.jellyfin.repository.StoredLibraries
import dev.jdtech.jellyfin.repository.StoredLibraryNames
import dev.jdtech.jellyfin.repository.StoredRows
import java.time.LocalDateTime
import java.util.Locale

/**
 * CE (#775): the lists Room answers while the server is away ([StoredItemLists]). The lists are
 * what is downloaded, so they are sorted and filtered in memory, on the rows before they are
 * mapped. #966: was `StoredLists`, which the repository built over the DAO itself;
 * [RoomDownloadStore] implements the role with it.
 */
internal class RoomStoredLists(private val database: ServerDatabaseDao) : StoredItemLists {
    override suspend fun favorites(serverId: ServerId, userId: UserId): StoredRows<Item> =
        rows(
            serverId,
            database.getFavoriteMoviesOfServer(serverId, userId) +
                database.getFavoriteShowsOfServer(serverId, userId) +
                database.getFavoriteEpisodesOfServer(serverId, userId),
        )

    override suspend fun search(
        serverId: ServerId,
        query: String,
        kinds: List<ItemKind>?,
        limitPerKind: Int,
    ): StoredRows<Item> {
        val pattern = "%" + query.trim().replace(LIKE_SPECIAL) { "\\" + it.value } + "%"
        val wanted = kinds ?: listOf(ItemKind.MOVIE, ItemKind.SERIES)
        val found = buildList {
            if (ItemKind.MOVIE in wanted) {
                addAll(database.searchMoviesOfServer(serverId, pattern).take(limitPerKind))
            }
            if (ItemKind.SERIES in wanted) {
                addAll(database.searchShowsOfServer(serverId, pattern).take(limitPerKind))
            }
            if (ItemKind.EPISODE in wanted) {
                addAll(database.searchEpisodesOfServer(serverId, pattern).take(limitPerKind))
            }
        }
        return rows(serverId, found)
    }

    override suspend fun hasDownloads(serverId: ServerId): Boolean =
        database.countDownloadedMoviesOfServer(serverId) > 0 ||
            database.countDownloadedShowsOfServer(serverId) > 0

    /**
     * As the wide card of a real library shows, a backdrop before a poster. Without any, the blank
     * card as before.
     */
    override suspend fun libraries(
        serverId: ServerId,
        names: StoredLibraryNames,
        storedImage: (itemId: ItemId, image: String) -> Boolean,
    ): List<Library> {
        val movies = database.getDownloadedMoviesOfServer(serverId).map { it.id }
        val shows = database.getDownloadedShowsOfServer(serverId).map { it.id }
        if (movies.isEmpty() && shows.isEmpty()) return emptyList()
        val downloaded =
            database.getDownloadDatesOfServer(serverId).associate { it.itemId to it.date }
        fun artwork(ids: List<ItemId>): ItemImages {
            val newestFirst = ids.sortedByDescending { downloaded[it] ?: Long.MIN_VALUE }
            val image = STORED_IMAGES.firstNotNullOfOrNull { image ->
                newestFirst
                    .firstOrNull { storedImage(it, image) }
                    ?.let { localImagePath(it, image) }
            }
            return ItemImages(primary = image)
        }
        return buildList {
            if (movies.isNotEmpty()) {
                add(library(StoredLibraries.MOVIES, CollectionType.Movies, names, artwork(movies)))
            }
            if (shows.isNotEmpty()) {
                add(library(StoredLibraries.SHOWS, CollectionType.TvShows, names, artwork(shows)))
            }
        }
    }

    private fun library(
        id: ItemId,
        type: CollectionType,
        names: StoredLibraryNames,
        images: ItemImages,
    ) = Library(id = id, name = names.nameOf(type), type = type, images = images)

    override suspend fun libraryContents(
        serverId: ServerId,
        userId: UserId,
        libraryId: ItemId,
        sortBy: SortBy,
        sortOrder: SortOrder,
        filters: Set<ItemFilter>,
    ): StoredRows<Item> =
        rows(
            serverId,
            sorted(entries(serverId, userId, libraryId, filters), serverId, userId, sortBy)
                .let { if (sortOrder == SortOrder.DESCENDING) it.asReversed() else it }
                .map { it.dto },
        )

    override suspend fun libraryCount(
        serverId: ServerId,
        userId: UserId,
        libraryId: ItemId,
        filters: Set<ItemFilter>,
        nameLessThan: String?,
    ): Int {
        val entries = entries(serverId, userId, libraryId, filters)
        if (nameLessThan == null) return entries.size
        val boundary = sortName(nameLessThan)
        return entries.count { it.sortName < boundary }
    }

    /** [dtos] of [serverId], made into items only when they are loaded. */
    private fun rows(serverId: ServerId, dtos: List<Any>): StoredRows<Item> =
        RoomStoredRows(dtos) { page, userId -> toItems(page, serverId, userId) }

    private suspend fun toItems(dtos: List<Any>, serverId: ServerId, userId: UserId): List<Item> {
        val local =
            database.getLocalSourcesForItems(
                dtos.mapNotNull {
                    when (it) {
                        is MovieEntity -> it.id
                        is EpisodeEntity -> it.id
                        else -> null
                    }
                },
                serverId,
            )
        return dtos.map {
            when (it) {
                is MovieEntity -> it.toMovie(database, userId, local)
                is ShowEntity -> it.toShow(database, userId)
                is EpisodeEntity -> it.toEpisode(database, userId, local)
                else -> error("Not a stored item: $it")
            }
        }
    }

    /** A row with what sorting and filtering read. */
    private class Entry(
        val dto: Any,
        val id: ItemId,
        name: String,
        val rating: Float?,
        val released: LocalDateTime?,
        val userData: UserDataEntity?,
    ) {
        val sortName = sortName(name)
    }

    private suspend fun entries(
        serverId: ServerId,
        userId: UserId,
        libraryId: ItemId,
        filters: Set<ItemFilter>,
    ): List<Entry> {
        val userData = database.getUserDataOfUser(userId).associateBy { it.itemId }
        val entries =
            if (libraryId == StoredLibraries.MOVIES) {
                database.getDownloadedMoviesOfServer(serverId).map {
                    Entry(it, it.id, it.name, it.communityRating, it.premiereDate, userData[it.id])
                }
            } else {
                database.getDownloadedShowsOfServer(serverId).map {
                    Entry(
                        it,
                        it.id,
                        it.name,
                        it.communityRating,
                        it.productionYear?.let { year -> LocalDateTime.of(year, 1, 1, 0, 0) },
                        userData[it.id],
                    )
                }
            }
        if (filters.isEmpty()) return entries
        return entries.filter { entry -> filters.all { entry.userData.matches(it) } }
    }

    private fun UserDataEntity?.matches(filter: ItemFilter): Boolean =
        when (filter) {
            ItemFilter.IS_UNPLAYED -> this?.played != true
        }

    /** Ascending; a missing value comes first, as on the server. Ties go by name. */
    private suspend fun sorted(
        entries: List<Entry>,
        serverId: ServerId,
        userId: UserId,
        sortBy: SortBy,
    ): List<Entry> {
        val byName = compareBy<Entry>({ it.sortName }, { it.id.value })
        val comparator =
            when (sortBy) {
                SortBy.IMDB_RATING -> compareBy<Entry> { it.rating }
                SortBy.RELEASE_DATE -> compareBy { it.released }
                SortBy.DATE_ADDED -> {
                    val dates =
                        database.getDownloadDatesOfServer(serverId).associate {
                            it.itemId to it.date
                        }
                    compareBy { dates[it.id] }
                }
                SortBy.DATE_PLAYED,
                SortBy.SERIES_DATE_PLAYED -> {
                    // A show's own row has no date: the last of its episodes played is its date.
                    val shows =
                        database.getShowPlayedDatesOfServer(serverId, userId).associate {
                            it.itemId to it.date
                        }
                    compareBy { shows[it.id] ?: it.userData?.lastPlayedDate }
                }
                SortBy.NAME,
                SortBy.PARENTAL_RATING -> byName
            }
        return entries.sortedWith(comparator.then(byName))
    }

    private companion object {
        val LIKE_SPECIAL = Regex("""[\\%_]""")

        /** Case does not count, as with the server's sort names. */
        fun sortName(name: String) = name.lowercase(Locale.ROOT)
    }
}

/**
 * CE (#781): the images a download stores (ImagesDownloaderWorker), in the order a stored library
 * card takes them: the wide backdrop, then the poster.
 */
private val STORED_IMAGES = listOf("backdrop", "primary")

/**
 * #966: rows Room has read, mapped by [toModels] only when they are loaded ([StoredRows]); a slice
 * keeps the mapping and maps only its own rows.
 */
internal class RoomStoredRows<E, T>(
    private val rows: List<E>,
    private val toModels: suspend (rows: List<E>, userId: UserId) -> List<T>,
) : StoredRows<T> {
    override val size: Int
        get() = rows.size

    override fun slice(startIndex: Int, limit: Int?): StoredRows<T> =
        RoomStoredRows(
            rows.drop(startIndex).let { if (limit != null) it.take(limit) else it },
            toModels,
        )

    override suspend fun load(userId: UserId): List<T> = toModels(rows, userId)
}
