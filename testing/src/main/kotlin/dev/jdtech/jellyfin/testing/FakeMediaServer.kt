package dev.jdtech.jellyfin.testing

import dev.jdtech.jellyfin.domain.ports.ItemExtrasPort
import dev.jdtech.jellyfin.domain.ports.ItemListPort
import dev.jdtech.jellyfin.domain.ports.ItemListPort.Companion.SEARCH_LIMIT
import dev.jdtech.jellyfin.domain.ports.ItemListPort.Companion.SEARCH_LIMIT_PER_KIND
import dev.jdtech.jellyfin.domain.ports.ItemPort
import dev.jdtech.jellyfin.domain.ports.ItemQuery
import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.domain.ports.PlaybackPort
import dev.jdtech.jellyfin.domain.ports.PlaybackReport
import dev.jdtech.jellyfin.domain.ports.PlaybackRequest
import dev.jdtech.jellyfin.domain.ports.RequestWatcher
import dev.jdtech.jellyfin.domain.ports.ServerInfoPort
import dev.jdtech.jellyfin.domain.ports.ServerSession
import dev.jdtech.jellyfin.domain.ports.UserDataPort
import dev.jdtech.jellyfin.domain.ports.UserImage
import dev.jdtech.jellyfin.domain.userdata.PlayStop
import dev.jdtech.jellyfin.models.BoxSet
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.EpisodeField
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemFilter
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.Library
import dev.jdtech.jellyfin.models.MediaSourceId
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.Person
import dev.jdtech.jellyfin.models.PlaybackTarget
import dev.jdtech.jellyfin.models.Season
import dev.jdtech.jellyfin.models.Segment
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.models.ServerInfo
import dev.jdtech.jellyfin.models.ServerType
import dev.jdtech.jellyfin.models.Show
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.repository.ItemsPage
import dev.jdtech.jellyfin.repository.ResumeThresholds
import java.time.LocalDateTime
import java.util.Collections
import kotlin.uuid.Uuid
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A media server in memory (#771), for the tests of anything above the ports: it presents [library]
 * as an adapter would, and passes the same contract suites as the Jellyfin adapter
 * (`FakeMediaServerContractTest`), which is what keeps it honest.
 *
 * Like an adapter, it knows nothing about downloads: items carry only their remote sources. A test
 * that needs a downloaded item puts the fake under the repository together with a database.
 *
 * Every port call is listed in [calls] by its name (`item`, `search`, `sources`, …). Thread-safe
 * enough for a repository that calls on the IO dispatcher.
 */
class FakeMediaServer(
    private val library: FakeLibrary = ContractLibrary,
    override val capabilities: ServerCapabilities = ServerCapabilities.ALL,
    override val type: ServerType = ServerType.JELLYFIN,
) : MediaServer {
    /** The user data the server keeps, which [userData] and the reports change. */
    private data class Marks(val played: Boolean, val favorite: Boolean, val positionTicks: Long)

    private val marks: MutableMap<ItemId, Marks> =
        Collections.synchronizedMap(
            library.items.associateTo(mutableMapOf()) {
                it.id to Marks(it.played, it.favorite, it.playbackPositionTicks)
            }
        )

    @Volatile private var failure: ServerException? = null
    @Volatile private var failingCalls: Set<String>? = null

    private val callLog: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val reportLog: MutableList<Pair<ReportKind, PlaybackReport>> =
        Collections.synchronizedList(mutableListOf())

    /** The names of the port calls made, in order. */
    val calls: List<String>
        get() = synchronized(callLog) { callLog.toList() }

    enum class ReportKind {
        START,
        PROGRESS,
        STOPPED,
    }

    /** What was reported, in order, with its kind. */
    val reported: List<Pair<ReportKind, PlaybackReport>>
        get() = synchronized(reportLog) { reportLog.toList() }

    /** What was reported, in order. */
    val reports: List<PlaybackReport>
        get() = reported.map { it.second }

    /**
     * Make the next calls that reach the server (the suspending ones) fail the way a server does:
     * every such call, or only those named in [only] (the names of [calls]). Null makes them
     * succeed again. A call that only builds a URL or sets a field never fails, as with an adapter.
     * A [ServerException.HttpStatus] 401 also reaches [ServerSession.unauthorized] with the
     * session's token, as a real client reports a refused token.
     */
    fun failWith(error: ServerException?, only: Set<String>? = null) {
        failure = error
        failingCalls = only
    }

    @Volatile private var stalled = false

    /** With [stall], the calls that reach the server wait for an answer that never comes. */
    fun stall(stall: Boolean = true) {
        stalled = stall
    }

    /** A call that only builds a URL or sets a field: listed, never failing. */
    private fun <T> answer(name: String, block: () -> T): T {
        callLog += name
        return block()
    }

    /** A call that reaches the server. */
    private suspend fun <T> call(name: String, block: suspend () -> T): T {
        callLog += name
        if (stalled) awaitCancellation()
        val error = failure
        if (error != null && failingCalls.let { it == null || name in it }) {
            if (error is ServerException.HttpStatus && error.status == 401) {
                session.accessToken?.let { unauthorizedTokens.tryEmit(it) }
            }
            throw error
        }
        return block()
    }

    private val unauthorizedTokens = MutableSharedFlow<String>(extraBufferCapacity = 64)

    // The library as the user sees it now.

    private fun notFound(): Nothing = throw ServerException.HttpStatus(404)

    private fun stored(id: ItemId): Item? =
        library.items.firstOrNull { it.id == id } ?: library.libraries.firstOrNull { it.id == id }

    private fun episodesOf(seriesId: ItemId): List<Episode> =
        library.items
            .filterIsInstance<Episode>()
            .filter { it.seriesId == seriesId }
            .sortedWith(compareBy({ it.parentIndexNumber }, { it.indexNumber }))
            .map { current(it) as Episode }

    private fun current(item: Item): Item {
        val mark = marks[item.id]
        return when (item) {
            is Movie ->
                mark?.let {
                    item.copy(
                        played = it.played,
                        favorite = it.favorite,
                        playbackPositionTicks = it.positionTicks,
                    )
                } ?: item
            is Episode ->
                mark?.let {
                    item.copy(
                        played = it.played,
                        favorite = it.favorite,
                        playbackPositionTicks = it.positionTicks,
                    )
                } ?: item
            is Show -> {
                val unplayed = episodesOf(item.id).count { !it.played }
                item.copy(
                    played = unplayed == 0,
                    favorite = mark?.favorite ?: item.favorite,
                    unplayedItemCount = unplayed,
                )
            }
            is Season -> {
                val unplayed =
                    library.items.filterIsInstance<Episode>().count {
                        it.seasonId == item.id && marks[it.id]?.played != true
                    }
                item.copy(
                    played = unplayed == 0,
                    favorite = mark?.favorite ?: item.favorite,
                    unplayedItemCount = unplayed,
                )
            }
            is BoxSet -> mark?.let { item.copy(favorite = it.favorite) } ?: item
            else -> item
        }
    }

    private fun currentItem(id: ItemId): Item = current(stored(id) ?: notFound())

    private fun kindOf(item: Item): ItemKind =
        when (item) {
            is Movie -> ItemKind.MOVIE
            is Show -> ItemKind.SERIES
            is Season -> ItemKind.SEASON
            is Episode -> ItemKind.EPISODE
            is BoxSet -> ItemKind.BOX_SET
            is Library -> ItemKind.COLLECTION_FOLDER
            else -> ItemKind.UNKNOWN
        }

    /** The direct children of [id], or of the user's root (the libraries) for null. */
    private fun childrenOf(id: ItemId?): List<Item> {
        if (id == null) return library.libraries
        library.children[id]?.let { ids ->
            return ids.mapNotNull(::stored)
        }
        val parent = stored(id) ?: notFound()
        return when (parent) {
            is Show ->
                library.items
                    .filterIsInstance<Season>()
                    .filter { it.seriesId == id }
                    .sortedBy { it.indexNumber }
            is Season ->
                library.items
                    .filterIsInstance<Episode>()
                    .filter { it.seasonId == id }
                    .sortedBy { it.indexNumber }
            else -> emptyList()
        }
    }

    private fun descendantsOf(id: ItemId?): List<Item> =
        childrenOf(id).flatMap { listOf(it) + descendantsOf(it.id) }.distinctBy { it.id }

    private fun select(query: ItemQuery): List<Item> {
        val candidates =
            when {
                query.parentId == null && query.recursive -> library.items
                query.recursive -> descendantsOf(query.parentId)
                else -> childrenOf(query.parentId)
            }
        val comparator: Comparator<Item> =
            when (query.sortBy) {
                SortBy.NAME -> compareBy { it.name.lowercase() }
                else -> compareBy<Item> { it.name.lowercase() }
            }
        return candidates
            .map(::current)
            .filter { query.kinds == null || kindOf(it) in query.kinds!! }
            .filter { ItemFilter.IS_UNPLAYED !in query.filters || !it.played }
            .sortedWith(
                if (query.sortOrder == SortOrder.DESCENDING) comparator.reversed() else comparator
            )
    }

    /** #967: all four parts of the catalog, as the Jellyfin adapter has them. */
    private inner class Catalog : ItemPort, ItemListPort, ItemExtrasPort, ServerInfoPort {
        override suspend fun serverInfo(): ServerInfo = call("serverInfo") { library.server }

        override suspend fun libraries(): List<Library> = call("libraries") { library.libraries }

        override suspend fun userViews(): List<Library> = call("userViews") { library.libraries }

        /** A person is a kind the app does not handle as an item: null, as from an adapter. */
        override suspend fun item(id: ItemId): Item? =
            call("item") { if (library.people.any { it.id == id }) null else currentItem(id) }

        override suspend fun movie(id: ItemId): Movie =
            call("movie") { currentItem(id) as? Movie ?: notFound() }

        override suspend fun show(id: ItemId): Show =
            call("show") { currentItem(id) as? Show ?: notFound() }

        override suspend fun season(id: ItemId): Season =
            call("season") { currentItem(id) as? Season ?: notFound() }

        override suspend fun episode(id: ItemId): Episode =
            call("episode") { currentItem(id) as? Episode ?: notFound() }

        override suspend fun items(query: ItemQuery, includeTotal: Boolean): ItemsPage =
            call("items") {
                val all = select(query)
                val page = all.drop(query.startIndex ?: 0).take(query.limit ?: Int.MAX_VALUE)
                ItemsPage(page, totalRecordCount = if (includeTotal) all.size else null)
            }

        override suspend fun itemCount(query: ItemQuery, nameLessThan: String?): Int =
            call("itemCount") {
                select(query).count { nameLessThan == null || it.name < nameLessThan }
            }

        override suspend fun randomItemsWithBackdrop(
            kinds: List<ItemKind>,
            limit: Int,
        ): List<Item> =
            call("randomItemsWithBackdrop") {
                library.items
                    .filter { kindOf(it) in kinds && it.images.backdrop != null }
                    .map(::current)
                    .shuffled()
                    .take(limit)
            }

        override suspend fun person(id: ItemId): Person =
            call("person") { library.people.firstOrNull { it.id == id } ?: notFound() }

        override suspend fun personItems(
            personIds: List<ItemId>,
            kinds: List<ItemKind>?,
            recursive: Boolean,
            limit: Int?,
        ): List<Item> =
            call("personItems") {
                personIds
                    .flatMap { library.personItems[it].orEmpty() }
                    .distinct()
                    .mapNotNull(::stored)
                    .map(::current)
                    .filter { kinds == null || kindOf(it) in kinds }
                    .take(limit ?: Int.MAX_VALUE)
            }

        override suspend fun favorites(): List<Item> =
            call("favorites") {
                library.items.map(::current).filter {
                    it.favorite &&
                        kindOf(it) in listOf(ItemKind.MOVIE, ItemKind.SERIES, ItemKind.EPISODE)
                }
            }

        override suspend fun search(query: String, kinds: List<ItemKind>?): List<Item> =
            call("search") {
                fun matching(kind: ItemKind, limit: Int) =
                    library.items
                        .filter { kindOf(it) == kind && it.name.contains(query, ignoreCase = true) }
                        .map(::current)
                        .take(limit)
                if (kinds == null) {
                    (matching(ItemKind.MOVIE, SEARCH_LIMIT) +
                            matching(ItemKind.SERIES, SEARCH_LIMIT))
                        .take(SEARCH_LIMIT)
                } else {
                    kinds.flatMap { matching(it, SEARCH_LIMIT_PER_KIND) }
                }
            }

        override suspend fun suggestions(): List<Item> =
            call("suggestions") {
                library.items
                    .filter { kindOf(it) == ItemKind.MOVIE || kindOf(it) == ItemKind.SERIES }
                    .map(::current)
                    .take(6)
            }

        override suspend fun resumeItems(): List<Item> =
            call("resumeItems") {
                library.items
                    .map(::current)
                    .filter {
                        (it is Movie || it is Episode) && !it.played && it.playbackPositionTicks > 0
                    }
                    .take(12)
            }

        override suspend fun latest(parentId: ItemId, kinds: List<ItemKind>?): List<Item> =
            call("latest") {
                childrenOf(parentId)
                    .map(::current)
                    .filter { kinds == null || kindOf(it) in kinds }
                    .take(16)
            }

        override suspend fun seasons(seriesId: ItemId): List<Season> =
            call("seasons") {
                if (stored(seriesId) !is Show) notFound()
                childrenOf(seriesId).map { current(it) as Season }
            }

        /**
         * Per series, the first unplayed episode after the last played one, specials left out; for
         * a series asked for by id also when none is played. As Jellyfin sends them: without
         * trickplay and chapters, which the player fetches with the episode itself.
         */
        override suspend fun nextUp(seriesId: ItemId?, dateCutoff: LocalDateTime?): List<Episode> =
            call("nextUp") {
                val series =
                    if (seriesId != null) listOf(seriesId)
                    else library.items.filterIsInstance<Show>().map { it.id }
                series
                    .mapNotNull { id ->
                        val episodes = episodesOf(id).filter { it.parentIndexNumber > 0 }
                        val lastPlayed = episodes.indexOfLast { it.played }
                        if (lastPlayed < 0 && seriesId == null) return@mapNotNull null
                        episodes.drop(lastPlayed + 1).firstOrNull { !it.played }
                    }
                    .map { it.copy(trickplayInfo = null, chapters = emptyList()) }
            }

        override suspend fun episodes(
            seriesId: ItemId,
            seasonId: ItemId?,
            fields: Set<EpisodeField>?,
            startItemId: ItemId?,
            limit: Int?,
        ): List<Episode> =
            call("episodes") {
                if (stored(seriesId) !is Show) notFound()
                val episodes =
                    episodesOf(seriesId).filter { seasonId == null || it.seasonId == seasonId }
                val start = startItemId?.let { id -> episodes.indexOfFirst { it.id == id } } ?: 0
                episodes.drop(start.coerceAtLeast(0)).take(limit ?: Int.MAX_VALUE)
            }

        override suspend fun similar(itemId: ItemId, limit: Int): List<Item> =
            call("similar") {
                if (!capabilities.similarItems) return@call emptyList()
                val item = stored(itemId) ?: notFound()
                library.items
                    .filter { it.id != itemId && kindOf(it) == kindOf(item) }
                    .map(::current)
                    .take(limit)
            }

        override suspend fun extras(
            itemId: ItemId,
            localTrailers: Boolean,
            specialFeatures: Boolean,
        ): List<Movie> =
            call("extras") {
                if (capabilities.extras) stored(itemId) ?: notFound()
                emptyList()
            }

        override suspend fun collectionsOf(itemId: ItemId): List<Item> =
            call("collectionsOf") {
                if (!capabilities.itemCollections) return@call emptyList()
                library.children
                    .filter { (id, children) -> itemId in children && stored(id) is BoxSet }
                    .keys
                    .mapNotNull(::stored)
                    .map(::current)
            }

        override suspend fun themeSongUrl(itemId: ItemId): String? =
            call("themeSongUrl") {
                if (capabilities.themeSongs) library.themeSongs[itemId] else null
            }
    }

    private inner class UserData : UserDataPort {
        override suspend fun setFavorite(itemId: ItemId, favorite: Boolean) {
            call("setFavorite") { mark(itemId) { it.copy(favorite = favorite) } }
        }

        override suspend fun setPlayed(itemId: ItemId, played: Boolean) {
            call("setPlayed") { mark(itemId) { it.copy(played = played, positionTicks = 0L) } }
        }
    }

    private fun mark(itemId: ItemId, change: (Marks) -> Marks) {
        stored(itemId) ?: notFound()
        synchronized(marks) { marks[itemId] = change(marks[itemId] ?: Marks(false, false, 0L)) }
    }

    private inner class Playback : PlaybackPort {
        override suspend fun sources(request: PlaybackRequest): List<ItemSource> =
            call("sources") {
                val item = stored(request.itemId) ?: notFound()
                val target = request.target
                // As the adapter does for a local player: the source and the audio index go
                // together or not at all.
                val chosen =
                    if (target is PlaybackTarget.LocalPlayer) {
                        request.mediaSourceId.takeIf { request.audioStreamIndex != null }
                    } else request.mediaSourceId
                val audioIndex =
                    if (target is PlaybackTarget.LocalPlayer) {
                        request.audioStreamIndex.takeIf { request.mediaSourceId != null }
                    } else request.audioStreamIndex
                item.sources
                    .filter { chosen == null || it.id == chosen }
                    .map { source ->
                        val cap = request.maxBitrate
                        val transcoded =
                            cap != null && source.bitrate != null && source.bitrate!! > cap
                        val session =
                            if (transcoded && target !is PlaybackTarget.Download) {
                                Uuid.random().toHexString()
                            } else null
                        source.copy(
                            path =
                                when {
                                    transcoded ->
                                        "${library.address}/videos/${item.id.asString()}/master.m3u8" +
                                            "?MediaSourceId=${source.id}"
                                    request.includePath || target is PlaybackTarget.CastReceiver ->
                                        streamUrlOf(item.id, source.id)
                                    else -> ""
                                },
                            transcoded = transcoded,
                            playSessionId = session,
                            defaultAudioStreamIndex = audioIndex ?: source.defaultAudioStreamIndex,
                        )
                    }
            }

        override suspend fun segments(itemId: ItemId): List<Segment> =
            call("segments") {
                stored(itemId) ?: notFound()
                if (capabilities.mediaSegments) library.segments[itemId].orEmpty() else emptyList()
            }

        override suspend fun trickplayTile(
            itemId: ItemId,
            width: Int,
            index: Int,
            mediaSourceId: MediaSourceId?,
        ): ByteArray? =
            call("trickplayTile") {
                val item = stored(itemId) ?: notFound()
                val info = (item as? dev.jdtech.jellyfin.models.Playable)?.trickplayInfo
                // #860: a version has the images of its own entry; without one, any entry.
                val entries =
                    if (mediaSourceId == null) info?.values
                    else listOfNotNull(info?.get(mediaSourceId))
                val has = entries?.any { it.width == width } == true
                if (capabilities.trickplay && has) TILE.copyOf() else null
            }

        override fun chapterImageUrl(itemId: ItemId, chapterIndex: Int, tag: String): String? =
            answer("chapterImageUrl") {
                "${library.address}/Items/${itemId.asString()}/Images/Chapter/$chapterIndex?tag=$tag"
            }

        override fun seasonBackdropUrl(seasonId: ItemId): String? =
            answer("seasonBackdropUrl") {
                "${library.address}/Items/${seasonId.asString()}/Images/Backdrop"
            }

        override suspend fun reportStart(report: PlaybackReport) {
            call("reportStart") { reportLog += ReportKind.START to report }
        }

        /** Only a start may leave its position out (see [PlaybackReport]). */
        private fun PlaybackReport.position(): Long =
            checkNotNull(positionTicks) { "a progress or stop report without a position" }

        override suspend fun reportProgress(report: PlaybackReport) {
            call("reportProgress") {
                reportLog += ReportKind.PROGRESS to report
                mark(report.itemId) { it.copy(positionTicks = report.position()) }
            }
        }

        /** Applies the stop as a server does, with [ResumeThresholds.DEFAULT]. */
        override suspend fun reportStopped(report: PlaybackReport) {
            call("reportStopped") {
                reportLog += ReportKind.STOPPED to report
                val item = stored(report.itemId) ?: notFound()
                val stop =
                    PlayStop.of(
                        report.position(),
                        playedPercentage = 0,
                        runtimeTicks = item.runtimeTicks,
                    )
                if (stop != null) {
                    mark(report.itemId) {
                        it.copy(
                            positionTicks = stop.positionTicks,
                            played = stop.played ?: it.played,
                        )
                    }
                }
            }
        }

        override suspend fun resumeThresholds(): ResumeThresholds? =
            call("resumeThresholds") {
                if (capabilities.serverResumeThresholds) ResumeThresholds.DEFAULT else null
            }
    }

    private fun streamUrlOf(itemId: ItemId, mediaSourceId: MediaSourceId) =
        "${library.address}/Videos/${itemId.asString()}/stream?static=true&mediaSourceId=$mediaSourceId"

    /** #899: public for the fake's own tests, which switch it with [use]. */
    inner class Session : ServerSession {
        @Volatile override var baseUrl: String? = library.address
        @Volatile override var userId: UserId? = library.userId
        @Volatile override var accessToken: String? = TOKEN
        override val unauthorized: Flow<String> = unauthorizedTokens
        override val serverReturned = MutableSharedFlow<Unit>()
        @Volatile override var requestWatcher: RequestWatcher? = null
        override val probeTimeoutMs: Long = 1_000L

        fun use(baseUrl: String?, accessToken: String?, userId: UserId?) {
            answer("use") {
                this.baseUrl = baseUrl
                this.accessToken = accessToken
                this.userId = userId
            }
        }

        override suspend fun probe(): Boolean? =
            if (baseUrl == null) null
            else
                try {
                    call("probe") { true }
                } catch (_: ServerException) {
                    false
                }

        override suspend fun isTokenValid(): Boolean = call("isTokenValid") { accessToken != null }

        override fun keepAlive(): Flow<Nothing> = emptyFlow()

        override fun userImageUrl(userId: UserId, tag: String): String? = baseUrl?.let {
            "$it/Users/${userId.asString()}/Images/Primary?tag=$tag"
        }

        override suspend fun autoPlayNextEpisode(): Boolean? =
            call("autoPlayNextEpisode") { if (capabilities.autoPlayNextSetting) true else null }

        override suspend fun currentUserImage(): UserImage =
            call("currentUserImage") { UserImage(userId ?: library.userId, tag = null) }
    }

    override val session: Session = Session()
    private val catalog = Catalog()
    override val items: ItemPort = catalog
    override val itemLists: ItemListPort = catalog
    override val itemExtras: ItemExtrasPort = catalog
    override val info: ServerInfoPort = catalog
    override val userData: UserDataPort = UserData()
    override val playback: PlaybackPort = Playback()

    companion object {
        /** The access token of the fake's session. */
        const val TOKEN = "token"

        /** What a trickplay tile is: the start of a JPEG. */
        private val TILE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
    }
}
