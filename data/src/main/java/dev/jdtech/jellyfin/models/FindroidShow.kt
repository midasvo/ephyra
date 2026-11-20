package dev.jdtech.jellyfin.models

import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.time.DayOfWeek
import java.util.UUID
import org.jellyfin.sdk.model.DateTime
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.PlayAccess

data class FindroidShow(
    override val id: UUID,
    override val name: String,
    override val originalTitle: String?,
    override val overview: String,
    override val sources: List<FindroidSource>,
    val seasons: List<FindroidSeason>,
    override val played: Boolean,
    override val favorite: Boolean,
    override val canPlay: Boolean,
    override val canDownload: Boolean,
    override val playbackPositionTicks: Long = 0L,
    override val unplayedItemCount: Int?,
    val genres: List<String>,
    val people: List<FindroidItemPerson>,
    override val runtimeTicks: Long,
    val communityRating: Float?,
    val officialRating: String?,
    val status: String,
    val productionYear: Int?,
    val endDate: DateTime?,
    val trailer: String?,
    // CE (#332): what the item response already carries. Not stored offline, so empty there.
    /** Every remote trailer; [trailer] is the first. */
    val trailers: List<FindroidTrailer> = emptyList(),
    val tagline: String? = null,
    /** The Rotten Tomatoes score (0–100) from the server's OMDb provider. */
    val criticRating: Float? = null,
    /** The networks that air the show. */
    val studios: List<String> = emptyList(),
    val externalLinks: List<FindroidExternalLink> = emptyList(),
    val airDays: List<DayOfWeek> = emptyList(),
    /** As the server has it, such as "21:00" or "9:00 PM". */
    val airTime: String? = null,
    val localTrailerCount: Int = 0,
    val specialFeatureCount: Int? = null,
    /** CE (#331): ids at outside sites, see [toProviderIds]. Empty offline. */
    val providerIds: Map<String, String> = emptyMap(),
    override val images: FindroidImages,
    override val chapters: List<FindroidChapter> = emptyList(),
) : FindroidItem

fun BaseItemDto.toFindroidShow(jellyfinRepository: JellyfinRepository): FindroidShow {
    return FindroidShow(
        id = id,
        name = name.orEmpty(),
        originalTitle = originalTitle,
        overview = overview.orEmpty(),
        played = userData?.played == true,
        favorite = userData?.isFavorite == true,
        canPlay = playAccess != PlayAccess.NONE,
        canDownload = canDownload == true,
        unplayedItemCount = userData?.unplayedItemCount,
        sources = emptyList(),
        seasons = emptyList(),
        genres = genres ?: emptyList(),
        people = people?.map { it.toFindroidPerson(jellyfinRepository) } ?: emptyList(),
        runtimeTicks = runTimeTicks ?: 0,
        communityRating = communityRating,
        officialRating = officialRating,
        status = status ?: "Ended",
        productionYear = productionYear,
        endDate = endDate,
        trailer = remoteTrailers?.getOrNull(0)?.url,
        trailers = remoteTrailers.toFindroidTrailers(),
        tagline = taglines.firstTagline(),
        criticRating = criticRating,
        studios = studioNames(),
        externalLinks = toExternalLinks(),
        airDays = airDays.toDaysOfWeek(),
        airTime = airTime?.takeIf { it.isNotBlank() }?.trim(),
        localTrailerCount = localTrailerCount ?: 0,
        specialFeatureCount = specialFeatureCount,
        providerIds = providerIds.toProviderIds(),
        images = toFindroidImages(jellyfinRepository),
    )
}

suspend fun FindroidShowDto.toFindroidShow(
    database: ServerDatabaseDao,
    userId: UUID,
): FindroidShow {
    val userData = database.getUserDataOrCreateNew(id, userId)
    return FindroidShow(
        id = id,
        name = name,
        originalTitle = originalTitle,
        overview = overview,
        played = userData.played,
        favorite = userData.favorite,
        canPlay = true,
        canDownload = false,
        unplayedItemCount = null,
        sources = emptyList(),
        seasons = emptyList(),
        genres = genres ?: emptyList(),
        people = emptyList(),
        runtimeTicks = runtimeTicks,
        communityRating = communityRating,
        officialRating = officialRating,
        status = status,
        productionYear = productionYear,
        endDate = endDate,
        trailer = null,
        images = toLocalFindroidImages(itemId = id),
    )
}
