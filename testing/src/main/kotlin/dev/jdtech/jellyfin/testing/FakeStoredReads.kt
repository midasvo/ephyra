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
 * #966: [StoredRows] over models a test already has: loading gives them as they are, whatever the
 * user. [loads] counts the loads, so a test sees whether the rows were mapped at all.
 */
class FakeStoredRows<T>(private val items: List<T>) : StoredRows<T> {
    var loads = 0
        private set

    override val size: Int
        get() = items.size

    override fun slice(startIndex: Int, limit: Int?): StoredRows<T> =
        FakeStoredRows(items.drop(startIndex).let { if (limit != null) it.take(limit) else it })

    override suspend fun load(userId: UserId): List<T> {
        loads++
        return items
    }
}

/**
 * #966: [StoredItems] in memory, per server: a test puts the models a download would have stored.
 * The seasons and episodes of a show are those put with its id, in the order they were put.
 */
class FakeStoredItems : StoredItems {
    private val items = mutableMapOf<Pair<ServerId, ItemId>, Item>()

    fun put(serverId: ServerId, vararg stored: Item) {
        stored.forEach { items[serverId to it.id] = it }
    }

    private inline fun <reified T : Item> of(serverId: ServerId): List<T> =
        items.filterKeys { it.first == serverId }.values.filterIsInstance<T>()

    private inline fun <reified T : Item> row(id: ItemId, serverId: ServerId): StoredRow<T>? =
        (items[serverId to id] as? T)?.let { item -> StoredRow { item } }

    override suspend fun movieOfServer(id: ItemId, serverId: ServerId): StoredRow<Movie>? =
        row(id, serverId)

    override suspend fun episodeOfServer(id: ItemId, serverId: ServerId): StoredRow<Episode>? =
        row(id, serverId)

    override suspend fun showOfServer(id: ItemId, serverId: ServerId): StoredRow<Show>? =
        row(id, serverId)

    override suspend fun seasonOfServer(id: ItemId, serverId: ServerId): StoredRow<Season>? =
        row(id, serverId)

    override suspend fun seasonsOfShow(showId: ItemId, serverId: ServerId): StoredRows<Season> =
        FakeStoredRows(of<Season>(serverId).filter { it.seriesId == showId })

    override suspend fun episodesOfSeason(
        seasonId: ItemId,
        serverId: ServerId,
    ): StoredRows<Episode> =
        FakeStoredRows(of<Episode>(serverId).filter { it.seasonId == seasonId })

    override suspend fun episodesOfSeries(
        seriesId: ItemId,
        serverId: ServerId,
    ): StoredRows<Episode> =
        FakeStoredRows(of<Episode>(serverId).filter { it.seriesId == seriesId })

    override suspend fun moviesOfServer(serverId: ServerId): StoredRows<Movie> =
        FakeStoredRows(of<Movie>(serverId).sortedBy { it.name })

    override suspend fun showsOfServer(serverId: ServerId): StoredRows<Show> =
        FakeStoredRows(of<Show>(serverId).sortedBy { it.name })

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
 * #966: [StoredItemLists] in memory: a test puts what each list answers for a server. A stored
 * library's contents are its items as put, a count is of those whose name sorts before the bound.
 */
class FakeStoredItemLists : StoredItemLists {
    val favorites = mutableMapOf<ServerId, List<Item>>()
    val searchResults = mutableMapOf<ServerId, List<Item>>()
    val libraries = mutableMapOf<ServerId, List<Library>>()
    val libraryContents = mutableMapOf<ItemId, List<Item>>()

    override suspend fun favorites(serverId: ServerId, userId: UserId): StoredRows<Item> =
        FakeStoredRows(favorites[serverId].orEmpty())

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
    ): StoredRows<Item> = FakeStoredRows(libraryContents[libraryId].orEmpty())

    override suspend fun libraryCount(
        serverId: ServerId,
        userId: UserId,
        libraryId: ItemId,
        filters: Set<ItemFilter>,
        nameLessThan: String?,
    ): Int =
        libraryContents[libraryId].orEmpty().count {
            nameLessThan == null || it.name.lowercase() < nameLessThan.lowercase()
        }
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
 * #966: [DownloadedFiles] in memory: the images and the trickplay sheets a test puts. A sheet put
 * without a version is found for any version asked for none.
 */
class FakeDownloadedFiles : DownloadedFiles {
    val images = mutableSetOf<Pair<ItemId, String>>()
    val sheets = mutableMapOf<Triple<ItemId, MediaSourceId?, Int>, ByteArray>()

    override fun hasImage(itemId: ItemId, image: String): Boolean = (itemId to image) in images

    override fun trickplaySheet(
        itemId: ItemId,
        mediaSourceId: MediaSourceId?,
        index: Int,
    ): ByteArray? =
        sheets[Triple(itemId, mediaSourceId, index)]
            ?: if (mediaSourceId == null) {
                sheets.entries
                    .firstOrNull { it.key.first == itemId && it.key.third == index }
                    ?.value
            } else null
}
