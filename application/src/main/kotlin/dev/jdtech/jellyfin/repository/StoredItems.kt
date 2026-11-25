package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.AccountKey
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.Season
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.Show

/**
 * #966: the stored items the repository answers with when the server is away (#281): one item of a
 * server by its kind, a show's stored seasons and episodes, the downloads of a server. Each read
 * gives the rows, made into models with a user's data and their local sources only when they are
 * loaded ([StoredRow], [StoredRows]).
 *
 * Downloads are stored per server (#718): a query that is of one server takes its [ServerId], one
 * of a user on a server an [AccountKey]. A row is otherwise keyed as Room keys it. One of the roles
 * of the download store; `RoomDownloadStore` in `data` implements them all.
 */
interface StoredItems {
    /** The movie [id] stored for [serverId], or null when that server has not stored it. */
    suspend fun movieOfServer(id: ItemId, serverId: ServerId): StoredRow<Movie>?

    /** As [movieOfServer], for an episode. */
    suspend fun episodeOfServer(id: ItemId, serverId: ServerId): StoredRow<Episode>?

    /** As [movieOfServer], for a show (stored with a downloaded episode). */
    suspend fun showOfServer(id: ItemId, serverId: ServerId): StoredRow<Show>?

    /** As [movieOfServer], for a season (stored with a downloaded episode). */
    suspend fun seasonOfServer(id: ItemId, serverId: ServerId): StoredRow<Season>?

    /** The stored seasons of show [showId] of [serverId]. */
    suspend fun seasonsOfShow(showId: ItemId, serverId: ServerId): StoredRows<Season>

    /** The stored episodes of season [seasonId] of [serverId]. */
    suspend fun episodesOfSeason(seasonId: ItemId, serverId: ServerId): StoredRows<Episode>

    /** The stored episodes of show [seriesId] of [serverId], season by season. */
    suspend fun episodesOfSeries(seriesId: ItemId, serverId: ServerId): StoredRows<Episode>

    /** The stored movies of [serverId], by name. */
    suspend fun moviesOfServer(serverId: ServerId): StoredRows<Movie>

    /** The stored shows of [serverId], by name. */
    suspend fun showsOfServer(serverId: ServerId): StoredRows<Show>

    /** The runtime of the stored movie or episode [itemId], or null when none is stored. */
    suspend fun runtimeTicks(itemId: ItemId): Long?
}
