package dev.jdtech.jellyfin.testing

import dev.jdtech.jellyfin.models.DownloadState
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemFilter
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.Library
import dev.jdtech.jellyfin.models.MediaSourceId
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.Season
import dev.jdtech.jellyfin.models.SeriesTrackPreference
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.Show
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.downloadState
import dev.jdtech.jellyfin.repository.DownloadedFiles
import dev.jdtech.jellyfin.repository.StoredItemLists
import dev.jdtech.jellyfin.repository.StoredItemSources
import dev.jdtech.jellyfin.repository.StoredItems
import dev.jdtech.jellyfin.repository.StoredLibraryNames
import dev.jdtech.jellyfin.repository.StoredRow
import dev.jdtech.jellyfin.repository.StoredRows
import dev.jdtech.jellyfin.repository.StoredTrackPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * #966: [StoredRows] over models a test already has. Loading makes each one for the user it is
 * loaded for with [forUser] (as Room adds that user's data), and rows read for one user ([readFor],
 * the favourites and the stored libraries) fail when they are loaded for another: a repository that
 * mixes up users does not pass. [loads] counts the loads, so a test sees whether the rows were
 * mapped at all.
 */
class FakeStoredRows<T>(
    private val items: List<T>,
    private val readFor: UserId? = null,
    private val forUser: (T, UserId) -> T = { item, _ -> item },
) : StoredRows<T> {
    var loads = 0
        private set

    override val size: Int
        get() = items.size

    override fun slice(startIndex: Int, limit: Int?): StoredRows<T> =
        FakeStoredRows(
            items.drop(startIndex).let { if (limit != null) it.take(limit) else it },
            readFor,
            forUser,
        )

    override suspend fun load(userId: UserId): List<T> {
        check(readFor == null || readFor == userId) {
            "Rows read for user ${readFor?.value} were loaded for user ${userId.value}"
        }
        loads++
        return items.map { forUser(it, userId) }
    }
}

/**
 * #966: [StoredItems] in memory, per server: a test puts the models a download would have stored,
 * and with [putForUser] how one user sees one of them (its user data); loaded for another user, an
 * item is as [put]. The reads order as Room's queries do.
 */
class FakeStoredItems : StoredItems {
    private val items = mutableMapOf<Pair<ServerId, ItemId>, Item>()
    private val ofUser = mutableMapOf<Pair<UserId, ItemId>, Item>()

    fun put(serverId: ServerId, vararg stored: Item) {
        stored.forEach { items[serverId to it.id] = it }
    }

    /** [item] as [userId] sees it: the same stored item with that user's data. */
    fun putForUser(userId: UserId, item: Item) {
        ofUser[userId to item.id] = item
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Item> forUser(item: T, userId: UserId): T =
        (ofUser[userId to item.id] as? T) ?: item

    private fun <T : Item> rows(list: List<T>): StoredRows<T> =
        FakeStoredRows(list, forUser = ::forUser)

    private inline fun <reified T : Item> of(serverId: ServerId): List<T> =
        items.filterKeys { it.first == serverId }.values.filterIsInstance<T>()

    private inline fun <reified T : Item> row(id: ItemId, serverId: ServerId): StoredRow<T>? =
        (items[serverId to id] as? T)?.let { item -> StoredRow { forUser(item, it) } }

    override suspend fun movieOfServer(id: ItemId, serverId: ServerId): StoredRow<Movie>? =
        row(id, serverId)

    override suspend fun episodeOfServer(id: ItemId, serverId: ServerId): StoredRow<Episode>? =
        row(id, serverId)

    override suspend fun showOfServer(id: ItemId, serverId: ServerId): StoredRow<Show>? =
        row(id, serverId)

    override suspend fun seasonOfServer(id: ItemId, serverId: ServerId): StoredRow<Season>? =
        row(id, serverId)

    override suspend fun seasonsOfShow(showId: ItemId, serverId: ServerId): StoredRows<Season> =
        // By index number, as Room orders them.
        rows(of<Season>(serverId).filter { it.seriesId == showId }.sortedBy { it.indexNumber })

    override suspend fun episodesOfSeason(
        seasonId: ItemId,
        serverId: ServerId,
    ): StoredRows<Episode> =
        rows(of<Episode>(serverId).filter { it.seasonId == seasonId }.sortedBy { it.indexNumber })

    override suspend fun episodesOfSeries(
        seriesId: ItemId,
        serverId: ServerId,
    ): StoredRows<Episode> =
        rows(
            of<Episode>(serverId)
                .filter { it.seriesId == seriesId }
                // Season by season, as Room orders them.
                .sortedWith(compareBy({ it.parentIndexNumber }, { it.indexNumber }))
        )

    override suspend fun moviesOfServer(serverId: ServerId): StoredRows<Movie> =
        rows(of<Movie>(serverId).sortedBy { it.name })

    override suspend fun showsOfServer(serverId: ServerId): StoredRows<Show> =
        rows(of<Show>(serverId).sortedBy { it.name })

    override suspend fun runtimeTicks(itemId: ItemId): Long? =
        items.entries
            .firstOrNull { it.key.second == itemId && (it.value is Movie || it.value is Episode) }
            ?.value
            ?.runtimeTicks
}

/**
 * #966: [StoredItemSources] in memory: the local sources a test puts per server and item. A source
 * whose path is still partial is not a completed one ([DownloadState]).
 */
class FakeStoredItemSources : StoredItemSources {
    private val sources = mutableMapOf<Pair<ServerId, ItemId>, List<ItemSource>>()
    private val any = MutableStateFlow(false)

    fun put(serverId: ServerId, itemId: ItemId, vararg local: ItemSource) {
        sources[serverId to itemId] = local.toList()
        any.value = sources.values.any { it.isNotEmpty() }
    }

    override suspend fun allSourcesOfServer(itemId: ItemId, serverId: ServerId): List<ItemSource> =
        sources[serverId to itemId].orEmpty()

    override suspend fun completedSourcesOfServer(
        itemId: ItemId,
        serverId: ServerId,
    ): List<ItemSource> =
        sources[serverId to itemId].orEmpty().filter { it.downloadState == DownloadState.FINISHED }

    override suspend fun localSourcesOfServer(
        itemIds: Collection<ItemId>,
        serverId: ServerId,
    ): Map<ItemId, List<ItemSource>> =
        itemIds.associateWith { sources[serverId to it].orEmpty() }.filterValues { it.isNotEmpty() }

    override suspend fun hasSources(): Boolean = any.value

    override fun observeHasSources(): Flow<Boolean> = any
}

/**
 * #966: [StoredItemLists] in memory: a test puts what each list answers, per server and, where Room
 * reads a user's data, per user: the favourites by (server, user), a stored library's contents by
 * (server, library) with [played] for the unplayed filter. A library is sorted by name only, either
 * way, as Room sorts it; another sort fails here rather than being ignored. Rows read for a user
 * fail when loaded for another ([FakeStoredRows]).
 */
class FakeStoredItemLists : StoredItemLists {
    val favorites = mutableMapOf<Pair<ServerId, UserId>, List<Item>>()
    val searchResults = mutableMapOf<ServerId, List<Item>>()
    val libraries = mutableMapOf<ServerId, List<Library>>()
    val libraryContents = mutableMapOf<Pair<ServerId, ItemId>, List<Item>>()

    /** What each user has played, for [ItemFilter.IS_UNPLAYED]. */
    val played = mutableSetOf<Pair<UserId, ItemId>>()

    override suspend fun favorites(serverId: ServerId, userId: UserId): StoredRows<Item> =
        FakeStoredRows(favorites[serverId to userId].orEmpty(), readFor = userId)

    override suspend fun search(
        serverId: ServerId,
        query: String,
        kinds: List<ItemKind>?,
        limitPerKind: Int,
    ): StoredRows<Item> = FakeStoredRows(searchResults[serverId].orEmpty())

    override suspend fun hasDownloads(serverId: ServerId): Boolean =
        libraries[serverId].orEmpty().isNotEmpty()

    override suspend fun libraries(
        serverId: ServerId,
        names: StoredLibraryNames,
        storedImage: (itemId: ItemId, image: String) -> Boolean,
    ): List<Library> = libraries[serverId].orEmpty()

    override suspend fun libraryContents(
        serverId: ServerId,
        userId: UserId,
        libraryId: ItemId,
        sortBy: SortBy,
        sortOrder: SortOrder,
        filters: Set<ItemFilter>,
    ): StoredRows<Item> {
        check(sortBy == SortBy.NAME) { "FakeStoredItemLists sorts a library by name only: $sortBy" }
        val sorted = entries(serverId, userId, libraryId, filters)
        return FakeStoredRows(
            if (sortOrder == SortOrder.DESCENDING) sorted.asReversed() else sorted,
            readFor = userId,
        )
    }

    override suspend fun libraryCount(
        serverId: ServerId,
        userId: UserId,
        libraryId: ItemId,
        filters: Set<ItemFilter>,
        nameLessThan: String?,
    ): Int =
        entries(serverId, userId, libraryId, filters).count {
            nameLessThan == null || it.name.lowercase() < nameLessThan.lowercase()
        }

    /** The library's items of [serverId], filtered for [userId], by name as Room sorts them. */
    private fun entries(
        serverId: ServerId,
        userId: UserId,
        libraryId: ItemId,
        filters: Set<ItemFilter>,
    ): List<Item> =
        libraryContents[serverId to libraryId]
            .orEmpty()
            .filter { item ->
                filters.all { filter ->
                    when (filter) {
                        ItemFilter.IS_UNPLAYED -> (userId to item.id) !in played
                    }
                }
            }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.id.value }))
}

/** #966: [StoredTrackPreferences] in memory, one per user, series and track type. */
class FakeStoredTrackPreferences : StoredTrackPreferences {
    private val stored = mutableMapOf<Triple<UserId, ItemId, Int>, SeriesTrackPreference>()

    override suspend fun trackPreferences(
        userId: UserId,
        seriesId: ItemId,
    ): List<SeriesTrackPreference> =
        stored.values.filter { it.userId == userId && it.seriesId == seriesId }

    override suspend fun saveTrackPreference(preference: SeriesTrackPreference) {
        stored[Triple(preference.userId, preference.seriesId, preference.trackType)] = preference
    }
}

/**
 * #966: [DownloadedFiles] in memory: the images and the trickplay sheets a test puts, by item,
 * version and index. Asked for no version, the first version with sheets is read. A version with
 * none gives null; a version with sheets but not the one asked for fails, as a read on disk does.
 */
class FakeDownloadedFiles : DownloadedFiles {
    val images = mutableSetOf<Pair<ItemId, String>>()
    val sheets = mutableMapOf<Triple<ItemId, MediaSourceId?, Int>, ByteArray>()

    override fun hasImage(itemId: ItemId, image: String): Boolean = (itemId to image) in images

    override fun trickplaySheet(
        itemId: ItemId,
        mediaSourceId: MediaSourceId?,
        index: Int,
    ): ByteArray? {
        // The version whose sheets are read, as on disk: the one asked for, or the first stored.
        val version = mediaSourceId ?: sheets.keys.firstOrNull { it.first == itemId }?.second
        val stored = sheets.keys.any { it.first == itemId && it.second == version }
        if (!stored) return null
        // Sheets are stored for that version, but not this one: a read that fails, as on disk.
        return sheets[Triple(itemId, version, index)]
            ?: throw java.io.FileNotFoundException("No sheet $index of ${itemId.value}")
    }
}
