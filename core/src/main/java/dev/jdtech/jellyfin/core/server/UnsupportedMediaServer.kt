package dev.jdtech.jellyfin.core.server

import dev.jdtech.jellyfin.core.presentation.session.NoServerSession
import dev.jdtech.jellyfin.domain.ports.ItemExtrasPort
import dev.jdtech.jellyfin.domain.ports.ItemListPort
import dev.jdtech.jellyfin.domain.ports.ItemPort
import dev.jdtech.jellyfin.domain.ports.ItemQuery
import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.domain.ports.PlaybackPort
import dev.jdtech.jellyfin.domain.ports.PlaybackReport
import dev.jdtech.jellyfin.domain.ports.PlaybackRequest
import dev.jdtech.jellyfin.domain.ports.ServerInfoPort
import dev.jdtech.jellyfin.domain.ports.ServerSession
import dev.jdtech.jellyfin.domain.ports.UnsupportedServerTypeException
import dev.jdtech.jellyfin.domain.ports.UserDataPort
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.EpisodeField
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.Library
import dev.jdtech.jellyfin.models.MediaSourceId
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.Person
import dev.jdtech.jellyfin.models.Season
import dev.jdtech.jellyfin.models.Segment
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.ServerInfo
import dev.jdtech.jellyfin.models.ServerType
import dev.jdtech.jellyfin.models.Show
import dev.jdtech.jellyfin.repository.ItemsPage
import dev.jdtech.jellyfin.repository.ResumeThresholds
import java.time.LocalDateTime

/**
 * #770: a server of a type this build has no adapter for, [storedType] as saved. It offers nothing
 * ([ServerCapabilities.NONE]), has no session ([NoServerSession]), and every request throws
 * [UnsupportedServerTypeException], which the screens show as a load error the way they show any
 * other failed load. The URLs, which are not requests, are null as without a server. Downloads
 * still open from Room, and the server can be switched or removed in the settings.
 */
internal class UnsupportedMediaServer(private val storedType: String) : MediaServer {
    private fun unsupported(): Nothing = throw UnsupportedServerTypeException(storedType)

    /** There is no [ServerType] for it. Nothing outside the selector asks. */
    override val type: ServerType
        get() = unsupported()

    override val capabilities: ServerCapabilities = ServerCapabilities.NONE

    override val session: ServerSession = NoServerSession

    /** Every part of the catalog, failing alike. */
    private val catalog =
        object : ItemPort, ItemListPort, ItemExtrasPort, ServerInfoPort {
            override suspend fun serverInfo(): ServerInfo = unsupported()

            override suspend fun libraries(): List<Library> = unsupported()

            override suspend fun userViews(): List<Library> = unsupported()

            override suspend fun item(id: ItemId): Item? = unsupported()

            override suspend fun movie(id: ItemId): Movie = unsupported()

            override suspend fun show(id: ItemId): Show = unsupported()

            override suspend fun season(id: ItemId): Season = unsupported()

            override suspend fun episode(id: ItemId): Episode = unsupported()

            override suspend fun items(query: ItemQuery, includeTotal: Boolean): ItemsPage =
                unsupported()

            override suspend fun itemCount(query: ItemQuery, nameLessThan: String?): Int =
                unsupported()

            override suspend fun randomItemsWithBackdrop(
                kinds: List<ItemKind>,
                limit: Int,
            ): List<Item> = unsupported()

            override suspend fun person(id: ItemId): Person = unsupported()

            override suspend fun personItems(
                personIds: List<ItemId>,
                kinds: List<ItemKind>?,
                recursive: Boolean,
                limit: Int?,
            ): List<Item> = unsupported()

            override suspend fun favorites(): List<Item> = unsupported()

            override suspend fun search(query: String, kinds: List<ItemKind>?): List<Item> =
                unsupported()

            override suspend fun suggestions(): List<Item> = unsupported()

            override suspend fun resumeItems(): List<Item> = unsupported()

            override suspend fun latest(parentId: ItemId, kinds: List<ItemKind>?): List<Item> =
                unsupported()

            override suspend fun seasons(seriesId: ItemId): List<Season> = unsupported()

            override suspend fun nextUp(
                seriesId: ItemId?,
                dateCutoff: LocalDateTime?,
            ): List<Episode> = unsupported()

            override suspend fun episodes(
                seriesId: ItemId,
                seasonId: ItemId?,
                fields: Set<EpisodeField>?,
                startItemId: ItemId?,
                limit: Int?,
            ): List<Episode> = unsupported()

            override suspend fun similar(itemId: ItemId, limit: Int): List<Item> = unsupported()

            override suspend fun extras(
                itemId: ItemId,
                localTrailers: Boolean,
                specialFeatures: Boolean,
            ): List<Movie> = unsupported()

            override suspend fun collectionsOf(itemId: ItemId): List<Item> = unsupported()

            override suspend fun themeSongUrl(itemId: ItemId): String? = unsupported()
        }

    override val items: ItemPort = catalog
    override val itemLists: ItemListPort = catalog
    override val itemExtras: ItemExtrasPort = catalog
    override val info: ServerInfoPort = catalog

    override val userData: UserDataPort =
        object : UserDataPort {
            override suspend fun setFavorite(itemId: ItemId, favorite: Boolean) = unsupported()

            override suspend fun setPlayed(itemId: ItemId, played: Boolean) = unsupported()
        }

    override val playback: PlaybackPort =
        object : PlaybackPort {
            override suspend fun sources(request: PlaybackRequest): List<ItemSource> = unsupported()

            override suspend fun segments(itemId: ItemId): List<Segment> = unsupported()

            override suspend fun trickplayTile(
                itemId: ItemId,
                width: Int,
                index: Int,
                mediaSourceId: MediaSourceId?,
            ): ByteArray? = unsupported()

            override fun chapterImageUrl(itemId: ItemId, chapterIndex: Int, tag: String): String? =
                null

            override fun seasonBackdropUrl(seasonId: ItemId): String? = null

            override suspend fun reportStart(report: PlaybackReport) = unsupported()

            override suspend fun reportProgress(report: PlaybackReport) = unsupported()

            override suspend fun reportStopped(report: PlaybackReport) = unsupported()

            override suspend fun resumeThresholds(): ResumeThresholds? = unsupported()
        }
}
