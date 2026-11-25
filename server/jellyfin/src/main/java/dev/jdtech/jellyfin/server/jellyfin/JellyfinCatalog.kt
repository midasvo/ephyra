package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.domain.ports.ActiveServer
import dev.jdtech.jellyfin.domain.ports.ItemExtrasPort
import dev.jdtech.jellyfin.domain.ports.ItemListPort
import dev.jdtech.jellyfin.domain.ports.ItemListPort.Companion.SEARCH_LIMIT
import dev.jdtech.jellyfin.domain.ports.ItemListPort.Companion.SEARCH_LIMIT_PER_KIND
import dev.jdtech.jellyfin.domain.ports.ItemPort
import dev.jdtech.jellyfin.domain.ports.ItemQuery
import dev.jdtech.jellyfin.domain.ports.ServerInfoPort
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.EpisodeField
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.Library
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.Person
import dev.jdtech.jellyfin.models.Season
import dev.jdtech.jellyfin.models.ServerInfo
import dev.jdtech.jellyfin.models.Show
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.repository.ItemsPage
import dev.jdtech.jellyfin.server.jellyfin.mappers.toEpisode
import dev.jdtech.jellyfin.server.jellyfin.mappers.toItem
import dev.jdtech.jellyfin.server.jellyfin.mappers.toLibrary
import dev.jdtech.jellyfin.server.jellyfin.mappers.toMovie
import dev.jdtech.jellyfin.server.jellyfin.mappers.toPerson
import dev.jdtech.jellyfin.server.jellyfin.mappers.toSeason
import dev.jdtech.jellyfin.server.jellyfin.mappers.toShow
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.jellyfin.sdk.api.client.extensions.audioApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter as SdkItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder as SdkSortOrder

/**
 * The catalog's ports over the Jellyfin SDK (#756): [ItemPort], [ItemListPort], [ItemExtrasPort]
 * and [ServerInfoPort], one class because they share the client, the pin and the mapping (#967). No
 * Room: movies and episodes carry only the server's sources, the repository adds the downloaded
 * ones. Throws [ServerException] for every failure the SDK reports as one (see
 * [asServerException]); anything else leaves as it is.
 *
 * #899: with a [pin], every call talks to that value's server as its user, whatever is switched to
 * meanwhile; without one, to the holder's value at each read.
 */
class JellyfinCatalog(private val api: JellyfinApi, private val pin: ActiveServer? = null) :
    ItemPort, ItemListPort, ItemExtrasPort, ServerInfoPort {
    /** #899: every call of this port goes through here, held to [pin]. */
    private suspend fun <T> call(block: suspend CoroutineScope.() -> T): T =
        api.pinnedCall(pin, block)

    private val currentUserId: UUID
        get() = api.currentUserId

    private fun baseUrl() = api.baseUrl()

    private fun mapperContext() = api.mapperContext()

    override suspend fun serverInfo(): ServerInfo = call {
        val info = api.systemApi.getPublicSystemInfo().content
        ServerInfo(
            id = info.id.orEmpty().toServerId(),
            name = info.serverName.orEmpty(),
            version = info.version,
            product = info.productName,
        )
    }

    override suspend fun libraries(): List<Library> = call {
        api.libraryApi.getItems(currentUserId).content.items.mapNotNull { it.toLibrary(baseUrl()) }
    }

    override suspend fun userViews(): List<Library> = call {
        api.userViewApi.getUserViews(currentUserId).content.items.mapNotNull {
            it.toLibrary(baseUrl())
        }
    }

    override suspend fun item(id: ItemId): Item? = call {
        api.libraryApi
            .getItem(itemId = id.toUuid(), userId = currentUserId)
            .content
            .toItem(mapperContext(), emptyMap())
    }

    override suspend fun movie(id: ItemId): Movie = call {
        api.libraryApi
            .getItem(id.toUuid(), currentUserId)
            .content
            .toMovie(mapperContext(), emptyMap())
    }

    override suspend fun show(id: ItemId): Show = call {
        api.libraryApi.getItem(id.toUuid(), currentUserId).content.toShow(baseUrl())
    }

    override suspend fun season(id: ItemId): Season = call {
        api.libraryApi.getItem(id.toUuid(), currentUserId).content.toSeason(baseUrl())
    }

    override suspend fun episode(id: ItemId): Episode = call {
        api.libraryApi
            .getItem(id.toUuid(), currentUserId)
            .content
            .toEpisode(mapperContext(), emptyMap())!!
    }

    override suspend fun items(query: ItemQuery, includeTotal: Boolean): ItemsPage = call {
        val result =
            api.libraryApi
                .getItems(
                    currentUserId,
                    parentId = query.parentId?.toUuid(),
                    includeItemTypes = query.kinds?.toSdk(),
                    recursive = query.recursive,
                    sortBy = listOf(ItemSortBy.fromName(query.sortBy.sortString)),
                    sortOrder = listOf(SdkSortOrder.fromName(query.sortOrder.sortString)),
                    filters = query.filters.toSdkFilters(),
                    startIndex = query.startIndex,
                    limit = query.limit,
                    // Cards read the primary image and the first backdrop. The server counts
                    // only for the first page of a pager (see indexedItemsPager in modes/film).
                    imageTypeLimit = 1,
                    enableImageTypes = ROW_IMAGE_TYPES,
                    enableTotalRecordCount = includeTotal,
                )
                .content
        ItemsPage(
            items = result.items.toItems(),
            totalRecordCount = if (includeTotal) result.totalRecordCount else null,
        )
    }

    override suspend fun itemCount(query: ItemQuery, nameLessThan: String?): Int = call {
        api.libraryApi
            .getItems(
                currentUserId,
                parentId = query.parentId?.toUuid(),
                includeItemTypes = query.kinds?.toSdk(),
                recursive = query.recursive,
                filters = query.filters.toSdkFilters(),
                nameLessThan = nameLessThan,
                limit = 0,
                enableTotalRecordCount = true,
                enableImages = false,
                enableUserData = false,
            )
            .content
            .totalRecordCount
    }

    override suspend fun randomItemsWithBackdrop(kinds: List<ItemKind>, limit: Int): List<Item> =
        call {
            api.libraryApi
                .getItems(
                    currentUserId,
                    includeItemTypes = kinds.toSdk(),
                    recursive = true,
                    sortBy = listOf(ItemSortBy.RANDOM),
                    imageTypes = listOf(ImageType.BACKDROP),
                    limit = limit,
                )
                .content
                .items
                .toItems()
        }

    override suspend fun person(id: ItemId): Person = call {
        api.libraryApi.getItem(id.toUuid(), currentUserId).content.toPerson(baseUrl())
    }

    override suspend fun personItems(
        personIds: List<ItemId>,
        kinds: List<ItemKind>?,
        recursive: Boolean,
        limit: Int?,
    ): List<Item> = call {
        api.libraryApi
            .getItems(
                currentUserId,
                personIds = personIds.map { it.toUuid() },
                includeItemTypes = kinds?.toSdk(),
                recursive = recursive,
                limit = limit,
                enableTotalRecordCount = false,
            )
            .content
            .items
            .toItems()
    }

    override suspend fun favorites(): List<Item> = call {
        api.libraryApi
            .getItems(
                currentUserId,
                filters = listOf(SdkItemFilter.IS_FAVORITE),
                includeItemTypes =
                    listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE),
                recursive = true,
                enableTotalRecordCount = false,
            )
            .content
            .items
            .toItems()
    }

    override suspend fun search(query: String, kinds: List<ItemKind>?): List<Item> = call {
        if (kinds == null) {
            searchItems(query, listOf(ItemKind.MOVIE, ItemKind.SERIES), SEARCH_LIMIT)
        } else {
            kinds
                .map { kind -> async { searchItems(query, listOf(kind), SEARCH_LIMIT_PER_KIND) } }
                .awaitAll()
                .flatten()
        }
    }

    private suspend fun searchItems(
        query: String,
        itemKinds: List<ItemKind>,
        limit: Int,
    ): List<Item> =
        api.libraryApi
            .getItems(
                currentUserId,
                searchTerm = query,
                includeItemTypes = itemKinds.toSdk(),
                recursive = true,
                limit = limit,
                enableTotalRecordCount = false,
            )
            .content
            .items
            .toItems()

    override suspend fun suggestions(): List<Item> = call {
        api.suggestionApi
            .getSuggestions(
                currentUserId,
                limit = 6,
                type = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                enableTotalRecordCount = false,
            )
            .content
            .items
            .toItems()
    }

    override suspend fun resumeItems(): List<Item> = call {
        api.libraryApi
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
            .toItems()
    }

    override suspend fun latest(parentId: ItemId, kinds: List<ItemKind>?): List<Item> = call {
        api.libraryApi
            .getLatestMedia(
                currentUserId,
                parentId = parentId.toUuid(),
                includeItemTypes = kinds?.toSdk(),
                fields = listOf(ItemFields.OVERVIEW),
                limit = 16,
                imageTypeLimit = 1,
                enableImageTypes = ROW_IMAGE_TYPES,
            )
            .content
            .toItems()
    }

    override suspend fun seasons(seriesId: ItemId): List<Season> = call {
        api.showApi.getSeasons(seriesId.toUuid(), currentUserId).content.items.map {
            it.toSeason(baseUrl())
        }
    }

    override suspend fun nextUp(seriesId: ItemId?, dateCutoff: LocalDateTime?): List<Episode> =
        call {
            api.showApi
                .getNextUp(
                    currentUserId,
                    limit = 24,
                    seriesId = seriesId?.toUuid(),
                    fields = listOf(ItemFields.OVERVIEW),
                    imageTypeLimit = 1,
                    enableImageTypes = WIDE_ROW_IMAGE_TYPES,
                    nextUpDateCutoff = dateCutoff,
                    enableTotalRecordCount = false,
                    enableResumable = false,
                )
                .content
                .items
                .mapNotNull { it.toEpisode(mapperContext(), emptyMap()) }
        }

    override suspend fun episodes(
        seriesId: ItemId,
        seasonId: ItemId?,
        fields: Set<EpisodeField>?,
        startItemId: ItemId?,
        limit: Int?,
    ): List<Episode> = call {
        api.showApi
            .getEpisodes(
                seriesId.toUuid(),
                currentUserId,
                seasonId = seasonId?.toUuid(),
                fields = fields?.map { it.toSdk() },
                startItemId = startItemId?.toUuid(),
                limit = limit,
            )
            .content
            .items
            .mapNotNull { it.toEpisode(mapperContext(), emptyMap()) }
    }

    override suspend fun similar(itemId: ItemId, limit: Int): List<Item> = call {
        api.libraryApi
            .getSimilarItems(itemId.toUuid(), userId = currentUserId, limit = limit)
            .content
            .items
            .filter { it.id.toItemId() != itemId }
            .toItems()
    }

    override suspend fun extras(
        itemId: ItemId,
        localTrailers: Boolean,
        specialFeatures: Boolean,
    ): List<Movie> = call {
        val trailersAsync = async {
            if (localTrailers) {
                api.libraryApi.getLocalTrailers(itemId.toUuid(), currentUserId).content
            } else emptyList()
        }
        val featuresAsync = async {
            if (specialFeatures) {
                api.libraryApi.getSpecialFeatures(itemId.toUuid(), currentUserId).content
            } else emptyList()
        }
        (trailersAsync.await() + featuresAsync.await())
            .distinctBy { it.id }
            .map { it.toMovie(mapperContext(), emptyMap()) }
    }

    override suspend fun collectionsOf(itemId: ItemId): List<Item> = call {
        api.libraryApi
            .getItemCollections(itemId.toUuid(), userId = currentUserId)
            .content
            .items
            .toItems()
    }

    override suspend fun themeSongUrl(itemId: ItemId): String? = call {
        val song =
            api.libraryApi
                .getThemeSongs(itemId.toUuid(), currentUserId, inheritFromParent = true)
                .content
                .items
                .firstOrNull() ?: return@call null
        api.api.audioApi.getAudioStreamUrl(song.id, static = true)
    }

    private suspend fun List<BaseItemDto>.toItems(): List<Item> {
        val context = mapperContext()
        return mapNotNull { it.toItem(context, emptyMap()) }
    }

    private companion object {
        /**
         * The only images row cards (home rows, next up) render: `ItemImages` reads the primary
         * tag, the first backdrop and the series primary tag. Together with `imageTypeLimit = 1`
         * this stops the server from resolving (and sending blurhashes for) every backdrop, logo,
         * thumb and banner of each item.
         */
        val ROW_IMAGE_TYPES = listOf(ImageType.PRIMARY, ImageType.BACKDROP)

        /** CE (#608): Continue Watching and Next Up also show the thumb, the art with the title. */
        val WIDE_ROW_IMAGE_TYPES = ROW_IMAGE_TYPES + ImageType.THUMB
    }
}
