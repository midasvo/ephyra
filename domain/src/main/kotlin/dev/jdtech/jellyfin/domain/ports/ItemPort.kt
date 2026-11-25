package dev.jdtech.jellyfin.domain.ports

import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.EpisodeField
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.Person
import dev.jdtech.jellyfin.models.Season
import dev.jdtech.jellyfin.models.Show
import java.time.LocalDateTime

/**
 * One item of the server and its children (#967): an item by its id as its kind, a show's seasons
 * and episodes, next up, a person and their items. Knows nothing about downloads: an item has only
 * its remote sources. Failures are thrown as `ServerException` (#756).
 */
interface ItemPort {
    suspend fun item(id: ItemId): Item?

    suspend fun movie(id: ItemId): Movie

    suspend fun show(id: ItemId): Show

    suspend fun season(id: ItemId): Season

    suspend fun episode(id: ItemId): Episode

    suspend fun seasons(seriesId: ItemId): List<Season>

    /** [dateCutoff] comes from a preference; the repository computes it. */
    suspend fun nextUp(seriesId: ItemId?, dateCutoff: LocalDateTime?): List<Episode>

    suspend fun episodes(
        seriesId: ItemId,
        seasonId: ItemId?,
        fields: Set<EpisodeField>?,
        startItemId: ItemId?,
        limit: Int?,
    ): List<Episode>

    suspend fun person(id: ItemId): Person

    suspend fun personItems(
        personIds: List<ItemId>,
        kinds: List<ItemKind>?,
        recursive: Boolean,
        limit: Int?,
    ): List<Item>
}
