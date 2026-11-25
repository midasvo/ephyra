package dev.jdtech.jellyfin.testing

import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.domain.userdata.PlayState
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemTrickplayInfo
import dev.jdtech.jellyfin.models.MediaSourceId
import dev.jdtech.jellyfin.models.Segment
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.repository.CurrentServerId
import dev.jdtech.jellyfin.repository.DownloadSegmentsAndTrickplay
import dev.jdtech.jellyfin.repository.DownloadUserData
import dev.jdtech.jellyfin.repository.DownloadedFiles
import dev.jdtech.jellyfin.repository.FallbackLog
import dev.jdtech.jellyfin.repository.KnownServerState
import dev.jdtech.jellyfin.repository.MarkedUserData
import dev.jdtech.jellyfin.repository.MediaRepositoryImpl
import dev.jdtech.jellyfin.repository.NextUpMaxDays
import dev.jdtech.jellyfin.repository.ResumeThresholdsStore
import dev.jdtech.jellyfin.repository.StoredLibraryNames
import dev.jdtech.jellyfin.repository.StoredUsers
import dev.jdtech.jellyfin.repository.UserDataSyncRequester

/**
 * #966: [DownloadUserData] in memory: a row per user and item, with its play state and whether it
 * is still to be sent. Only an item with a row is written, as in Room; [put] makes one.
 */
class FakeDownloadUserData : DownloadUserData {
    data class Row(val state: PlayState, val toBeSynced: Boolean = false)

    val rows = mutableMapOf<Pair<UserId, ItemId>, Row>()

    fun put(
        userId: UserId,
        itemId: ItemId,
        state: PlayState = PlayState(false, false, 0L, null),
        toBeSynced: Boolean = false,
    ) {
        rows[userId to itemId] = Row(state, toBeSynced)
    }

    fun row(userId: UserId, itemId: ItemId): Row? = rows[userId to itemId]

    override suspend fun storeUserDataOfDownload(userId: UserId, item: Item) {
        if (rows[userId to item.id]?.toBeSynced == true) return
        rows[userId to item.id] =
            Row(PlayState(item.played, item.favorite, item.playbackPositionTicks, null))
    }

    override suspend fun hasUserDataToBeSynced(userId: UserId, itemId: ItemId): Boolean =
        rows[userId to itemId]?.toBeSynced == true

    override suspend fun deleteSyncedUserData(itemId: ItemId) {
        rows.keys.removeAll { it.second == itemId && rows[it]?.toBeSynced == false }
    }

    override suspend fun writeAndMarkToBeSynced(
        userId: UserId,
        itemId: ItemId,
        change: (PlayState) -> PlayState?,
    ): MarkedUserData {
        updatePlayState(userId, itemId, change)
        val row = rows[userId to itemId] ?: return MarkedUserData(marked = false, written = null)
        rows[userId to itemId] = row.copy(toBeSynced = true)
        return MarkedUserData(marked = true, written = row.state)
    }

    override suspend fun markToBeSynced(userId: UserId, itemId: ItemId): Boolean {
        val row = rows[userId to itemId] ?: return false
        rows[userId to itemId] = row.copy(toBeSynced = true)
        return true
    }

    override suspend fun clearToBeSynced(userId: UserId, itemId: ItemId, written: PlayState) {
        val row = rows[userId to itemId] ?: return
        if (row.state == written) rows[userId to itemId] = row.copy(toBeSynced = false)
    }

    override suspend fun updatePlayState(
        userId: UserId,
        itemId: ItemId,
        change: (PlayState) -> PlayState?,
    ) {
        val row = rows[userId to itemId] ?: return
        val next = change(row.state) ?: return
        rows[userId to itemId] = row.copy(state = next)
    }
}

/** #966: [DownloadSegmentsAndTrickplay] in memory: the segments per item; trickplay is not kept. */
class FakeStoredSegments : DownloadSegmentsAndTrickplay {
    val segments = mutableMapOf<ItemId, List<Segment>>()

    override suspend fun segments(itemId: ItemId): List<Segment> = segments[itemId].orEmpty()

    override suspend fun replaceSegments(itemId: ItemId, segments: List<Segment>) {
        this.segments[itemId] = segments
    }

    override suspend fun deleteSegments(itemId: ItemId) {
        segments.remove(itemId)
    }

    override suspend fun insertTrickplayInfo(sourceId: MediaSourceId, info: ItemTrickplayInfo) =
        Unit
}

/**
 * #966: the stores of a [MediaRepositoryImpl] in memory, for a test of the repository or of a class
 * that takes its roles: nothing is stored until the test puts it. [userImageTags] is what
 * `refreshCurrentUserImage` wrote; no other call of [StoredUsers] is expected.
 */
class FakeRepositoryStores {
    val items = FakeStoredItems()
    val lists = FakeStoredItemLists()
    val sources = FakeStoredItemSources()
    val segments = FakeStoredSegments()
    val userData = FakeDownloadUserData()
    val trackPreferences = FakeStoredTrackPreferences()
    val files = FakeDownloadedFiles()
    val userImageTags = mutableMapOf<UserId, String?>()

    // A value class id comes to the proxy as its string (the pitfall in
    // docs/areas/architecture.md).
    val users: StoredUsers = fakeRole { _, method, args ->
        check(method.kotlinName == "setUserImageTag") { "Not faked: StoredUsers.${method.name}" }
        userImageTags[(args!![0] as String).toUserId()] = args[1] as String?
    }

    /** The repository over [server] and these stores, with the settings at their defaults. */
    fun repository(
        server: MediaServer,
        currentServer: CurrentServerId = CurrentServerId { null },
        nextUpMaxDays: NextUpMaxDays = NextUpMaxDays { 0 },
        resumeThresholds: ResumeThresholdsStore = FakeResumeThresholdsStore(),
        syncRequester: UserDataSyncRequester = UserDataSyncRequester.NONE,
        knownServerState: KnownServerState = KnownServerState.NONE,
        storedLibraryNames: StoredLibraryNames = StoredLibraryNames.ENGLISH,
        fallbackLog: FallbackLog = FallbackLog.NONE,
        /** [files], or the real files of a test that downloaded some. */
        downloadedFiles: DownloadedFiles = files,
    ): MediaRepositoryImpl =
        MediaRepositoryImpl(
            server = server,
            storedItems = items,
            storedLists = lists,
            storedSources = sources,
            storedSegments = segments,
            storedUserData = userData,
            trackPreferences = trackPreferences,
            storedUsers = users,
            downloadedFiles = downloadedFiles,
            currentServer = currentServer,
            nextUpMaxDays = nextUpMaxDays,
            resumeThresholds = resumeThresholds,
            syncRequester = syncRequester,
            knownServerState = knownServerState,
            storedLibraryNames = storedLibraryNames,
            fallbackLog = fallbackLog,
        )
}
