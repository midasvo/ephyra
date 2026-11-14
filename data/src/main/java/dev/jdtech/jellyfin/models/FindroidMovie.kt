package dev.jdtech.jellyfin.models

import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.time.LocalDateTime
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.PlayAccess

data class FindroidMovie(
    override val id: UUID,
    override val name: String,
    override val originalTitle: String?,
    override val overview: String,
    override val sources: List<FindroidSource>,
    override val played: Boolean,
    override val favorite: Boolean,
    override val canPlay: Boolean,
    override val canDownload: Boolean,
    override val runtimeTicks: Long,
    override val playbackPositionTicks: Long,
    val premiereDate: LocalDateTime?,
    val people: List<FindroidItemPerson>,
    val genres: List<String>,
    val communityRating: Float?,
    val officialRating: String?,
    val status: String,
    val productionYear: Int?,
    val endDate: LocalDateTime?,
    val trailer: String?,
    override val unplayedItemCount: Int? = null,
    /** When the server last saw this movie played; null if never, or when read offline. */
    val lastPlayedDate: LocalDateTime? = null,
    // CE (#332): what the item response already carries. Not stored offline, so empty there.
    /** Every remote trailer; [trailer] is the first. */
    val trailers: List<FindroidTrailer> = emptyList(),
    val tagline: String? = null,
    /** The Rotten Tomatoes score (0–100) from the server's OMDb provider. */
    val criticRating: Float? = null,
    val studios: List<String> = emptyList(),
    val externalLinks: List<FindroidExternalLink> = emptyList(),
    /** How often the server saw this movie played to the end. */
    val playCount: Int = 0,
    val is3D: Boolean = false,
    val localTrailerCount: Int = 0,
    /** Null when the server didn't say, which is no reason not to ask for the extras. */
    val specialFeatureCount: Int? = null,
    override val images: FindroidImages,
    override val chapters: List<FindroidChapter>,
    override val trickplayInfo: Map<String, FindroidTrickplayInfo>?,
) : FindroidItem, FindroidSources

suspend fun BaseItemDto.toFindroidMovie(
    jellyfinRepository: JellyfinRepository,
    serverDatabase: ServerDatabaseDao? = null,
): FindroidMovie =
    toFindroidMovie(
        jellyfinRepository,
        serverDatabase?.getLocalSourcesForItems(listOf(id)).orEmpty(),
    )

/** For lists: [localSources] comes from one [getLocalSourcesForItems] call for all items. */
suspend fun BaseItemDto.toFindroidMovie(
    jellyfinRepository: JellyfinRepository,
    localSources: Map<UUID, List<FindroidSource>>,
): FindroidMovie {
    val sources = mutableListOf<FindroidSource>()
    sources.addAll(mediaSources?.map { it.toFindroidSource(jellyfinRepository, id) } ?: emptyList())
    sources.addAll(localSources[id].orEmpty())
    return FindroidMovie(
        id = id,
        name = name.orEmpty(),
        originalTitle = originalTitle,
        overview = overview.orEmpty(),
        sources = sources,
        played = userData?.played == true,
        favorite = userData?.isFavorite == true,
        canPlay = playAccess != PlayAccess.NONE,
        canDownload = canDownload == true,
        runtimeTicks = runTimeTicks ?: 0,
        playbackPositionTicks = userData?.playbackPositionTicks ?: 0,
        premiereDate = premiereDate,
        communityRating = communityRating,
        genres = genres ?: emptyList(),
        people = people?.map { it.toFindroidPerson(jellyfinRepository) } ?: emptyList(),
        officialRating = officialRating,
        status = status ?: "Ended",
        productionYear = productionYear,
        endDate = endDate,
        trailer = remoteTrailers?.getOrNull(0)?.url,
        lastPlayedDate = userData?.lastPlayedDate,
        trailers = remoteTrailers.toFindroidTrailers(),
        tagline = taglines.firstTagline(),
        criticRating = criticRating,
        studios = studioNames(),
        externalLinks = toExternalLinks(),
        playCount = userData?.playCount ?: 0,
        is3D = video3dFormat != null,
        localTrailerCount = localTrailerCount ?: 0,
        specialFeatureCount = specialFeatureCount,
        images = toFindroidImages(jellyfinRepository),
        chapters = toFindroidChapters(),
        trickplayInfo =
            trickplay
                ?.mapNotNull { (width, info) ->
                    info
                        ?.keys
                        ?.maxOrNull()
                        ?.let { info[it] }
                        ?.toFindroidTrickplayInfo()
                        ?.let { width to it }
                }
                ?.toMap(),
    )
}

suspend fun FindroidMovieDto.toFindroidMovie(
    database: ServerDatabaseDao,
    userId: UUID,
): FindroidMovie = toFindroidMovie(database, userId, database.getLocalSourcesForItems(listOf(id)))

/** For lists: [localSources] comes from one [getLocalSourcesForItems] call for all items. */
suspend fun FindroidMovieDto.toFindroidMovie(
    database: ServerDatabaseDao,
    userId: UUID,
    localSources: Map<UUID, List<FindroidSource>>,
): FindroidMovie {
    val userData = database.getUserDataOrCreateNew(id, userId)
    val sources = localSources[id].orEmpty()
    val trickplayInfos = mutableMapOf<String, FindroidTrickplayInfo>()
    for (source in sources) {
        database.getTrickplayInfo(source.id)?.toFindroidTrickplayInfo()?.let {
            trickplayInfos[source.id] = it
        }
    }
    return FindroidMovie(
        id = id,
        name = name,
        originalTitle = originalTitle,
        overview = overview,
        played = userData.played,
        favorite = userData.favorite,
        runtimeTicks = runtimeTicks,
        playbackPositionTicks = userData.playbackPositionTicks,
        premiereDate = premiereDate,
        genres = genres ?: emptyList(),
        people = emptyList(),
        communityRating = communityRating,
        officialRating = officialRating,
        status = status,
        productionYear = productionYear,
        endDate = endDate,
        canDownload = false,
        canPlay = true,
        sources = sources,
        trailer = null,
        images = toLocalFindroidImages(itemId = id),
        chapters = chapters ?: emptyList(),
        trickplayInfo = trickplayInfos,
    )
}
