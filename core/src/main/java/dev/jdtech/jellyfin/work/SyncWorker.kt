package dev.jdtech.jellyfin.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.core.presentation.downloader.SmartDownloads
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.FindroidUserDataDto
import dev.jdtech.jellyfin.repository.DataChangeEvents
import dev.jdtech.jellyfin.repository.PlaybackEvents
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import timber.log.Timber

@HiltWorker
class SyncWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    val database: ServerDatabaseDao,
    private val jellyfinApi: JellyfinApi,
    private val smartDownloads: SmartDownloads,
    private val playbackEvents: PlaybackEvents,
    private val dataChanges: DataChangeEvents,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            val servers = database.getServers()
            var hadFailures = false
            var reachedServer = false

            for (server in servers) {
                val serverWithAddressesAndUsers =
                    database.getServerWithAddressesAndUsers(server.id) ?: continue
                val serverAddress =
                    serverWithAddressesAndUsers.addresses.firstOrNull {
                        it.id == server.currentServerAddressId
                    } ?: continue
                for (user in serverWithAddressesAndUsers.users) {
                    // Only the rows still marked to be synced: most runs have none, and then no
                    // item is loaded, no client is built and no request is made (#250).
                    val pending = database.getAllUserDataToBeSynced(server.id, user.id)
                    if (pending.isEmpty()) continue

                    val api = apiFor(serverAddress.address, user.accessToken, user.id)
                    val outcome = syncUserData(api, user.id, pending)
                    if (!outcome.allOk) hadFailures = true
                    if (outcome.sent > 0) reachedServer = true
                    // A write that failed while this run was sending keeps its row marked, and
                    // asking for a sync during a run does nothing: run again for those rows.
                    val stillMarked = database.getAllUserDataToBeSynced(server.id, user.id)
                    if (needsAnotherRun(sent = pending, stillMarked = stillMarked)) {
                        hadFailures = true
                    }
                }
            }

            // CE (#633): what the server shows changed (Continue Watching, positions), so the screen
            // on display reloads, as after a playback stop. Also after a partly failed run.
            if (reachedServer) dataChanges.onSynced()

            if (hadFailures) {
                Result.retry()
            } else {
                // Progress watched offline has reached the server, so what to remove can be told.
                // Without completed downloads this is a single query and no requests.
                try {
                    smartDownloads.checkAll()
                } catch (e: Exception) {
                    Timber.e(e, "Smart Downloads check after sync failed")
                }
                Result.success()
            }
        }
    }

    /**
     * The app's own client when it is signed in as [userId] at [baseUrl], otherwise a client for
     * that user from the same Jellyfin instance. The app's client is never pointed at another user,
     * because the screens use it at the same time.
     */
    private fun apiFor(baseUrl: String, accessToken: String?, userId: UUID): ApiClient {
        val current = jellyfinApi.api
        if (
            jellyfinApi.userId == userId &&
                current.baseUrl == baseUrl &&
                current.accessToken == accessToken
        ) {
            return current
        }
        return jellyfinApi.jellyfin.createApi(
            baseUrl = baseUrl,
            accessToken = accessToken,
            httpClientOptions = current.httpClientOptions,
        )
    }

    /**
     * The item this device's session is playing according to the server, for the app's own client
     * only: another user's client has no session of its own to end.
     */
    private suspend fun itemLeftPlaying(api: ApiClient, userId: UUID): UUID? {
        if (api !== jellyfinApi.api) return null
        return api.sessionApi
            .getSessions(deviceId = api.deviceInfo.id)
            .content
            .firstOrNull { it.userId == userId }
            ?.nowPlayingItem
            ?.id
    }

    /**
     * CE (#633): the server's user data of [itemIds], one request per [USER_DATA_BATCH_SIZE]
     * items. Items the server does not return (deleted there) are missing from the map.
     */
    private suspend fun fetchServerUserData(
        api: ApiClient,
        userId: UUID,
        itemIds: List<UUID>,
    ): Map<UUID, ServerUserData> =
        itemIds.chunked(USER_DATA_BATCH_SIZE).fold(emptyMap()) { found, batch ->
            val items =
                api.libraryApi.getItems(userId = userId, ids = batch, enableUserData = true).content.items
            found + serverUserDataOf(items)
        }

    /** CE (#633): how a run for one user went: whether to retry, and how many rows were sent. */
    private data class SyncOutcome(val allOk: Boolean, val sent: Int)

    private suspend fun syncUserData(
        api: ApiClient,
        userId: UUID,
        items: List<FindroidUserDataDto>,
    ): SyncOutcome {
        // The item the server still has "playing" for this device: its stop report did not get
        // through. Asked first, so an unreachable server ends the run after one request.
        val leftPlaying =
            try {
                itemLeftPlaying(api, userId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidStatusException) {
                Timber.w(e, "Could not read the playback session")
                null
            } catch (e: Exception) {
                Timber.e(e, "Failed to sync user data: the server was not reached")
                return SyncOutcome(allOk = false, sent = 0)
            }

        // CE (#633): what the server has now, so a row only sends its LastPlayedDate when it is
        // newer: another device that watched the item later keeps it at the top of Continue
        // Watching. Read before anything is sent, also before a stale session's stop below, which
        // sets the server's date to now. Without the answer nothing is sent: the run is retried,
        // rather than sending dates that may be older than the server's.
        val onServer =
            try {
                fetchServerUserData(api, userId, items.map { it.itemId })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to sync user data: the server's user data could not be read")
                return SyncOutcome(allOk = false, sent = 0)
            }

        var allOk = true
        var sent = 0
        for (userData in items) {
            try {
                if (
                    endsStaleSession(
                        rowItemId = userData.itemId,
                        leftPlayingOnServer = leftPlaying,
                        playingHere = playbackEvents.playingItemId.value,
                    )
                ) {
                    // End it with the real position, or the server's idle check ends it minutes
                    // later with the last position it heard, over what is written below (#584).
                    Timber.d("Ending the server's playback session of ${userData.itemId}")
                    api.sessionApi.reportPlaybackStopped(
                        PlaybackStopInfo(
                            itemId = userData.itemId,
                            positionTicks = userData.playbackPositionTicks,
                            failed = false,
                        )
                    )
                }

                api.userDataApi.updateItemUserData(
                    itemId = userData.itemId,
                    userId = userId,
                    data =
                        UpdateUserItemDataDto(
                            playbackPositionTicks = userData.playbackPositionTicks,
                            isFavorite = userData.favorite,
                            played = userData.played,
                            // CE (#633): moves the item up in Continue Watching on every device.
                            // Null leaves the server's date as it is.
                            lastPlayedDate =
                                userData.lastPlayedDate
                                    ?.takeIf {
                                        isNewerThanServer(it, onServer[userData.itemId]?.lastPlayedDate)
                                    }
                                    ?.let(::localDateTimeOf),
                        ),
                )
                sent++

                database.clearUserDataToBeSynced(
                    userId = userId,
                    itemId = userData.itemId,
                    played = userData.played,
                    favorite = userData.favorite,
                    playbackPositionTicks = userData.playbackPositionTicks,
                    lastPlayedDate = userData.lastPlayedDate,
                )
                // A row kept only for this sync, because its download was deleted first.
                database.deleteUserDataIfOrphaned(userId, userData.itemId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidStatusException) {
                when (afterStatus(e.status)) {
                    AfterStatus.DROP -> {
                        // The item is gone from the server: there is nothing to send, ever.
                        // Without this the row of a deleted download would be retried for good.
                        Timber.w("Item ${userData.itemId} is gone from the server, dropping it")
                        database.setUserDataToBeSynced(userId, userData.itemId, false)
                        database.deleteUserDataIfOrphaned(userId, userData.itemId)
                    }
                    AfterStatus.LEAVE ->
                        // Refused, and a retry in 30 seconds would be refused again. The row
                        // stays marked for the next sync: app start, foreground, the next write.
                        Timber.w("The server refused ${userData.itemId} with ${e.status}")
                    AfterStatus.RETRY -> {
                        Timber.e(e, "Failed to sync user data")
                        allOk = false
                    }
                }
            } catch (e: Exception) {
                // The server was not reached: the other rows would fail the same way, each after
                // its own timeout.
                Timber.e(e, "Failed to sync user data, leaving the rest for the next run")
                return SyncOutcome(allOk = false, sent = sent)
            }
        }
        return SyncOutcome(allOk = allOk, sent = sent)
    }
}

/** CE (#633): the server's user data of one item, as the sync compares it with the local row. */
internal data class ServerUserData(
    val itemId: UUID,
    val played: Boolean,
    val favorite: Boolean,
    val playbackPositionTicks: Long,
    /** Epoch milliseconds, null when the server has none. */
    val lastPlayedDate: Long?,
)

/** CE (#633): the user data of [items] by item id; items without user data are left out. */
internal fun serverUserDataOf(items: List<BaseItemDto>): Map<UUID, ServerUserData> =
    items
        .mapNotNull { item ->
            val userData = item.userData ?: return@mapNotNull null
            ServerUserData(
                itemId = item.id,
                played = userData.played,
                favorite = userData.isFavorite,
                playbackPositionTicks = userData.playbackPositionTicks,
                lastPlayedDate = userData.lastPlayedDate?.let { epochMillisOf(it) },
            )
        }
        .associateBy { it.itemId }

/**
 * CE (#633): whether a local last-played time [local] is newer than the server's [server], both in
 * epoch milliseconds. Unknown locally is never newer; unknown on the server is always older. An
 * equal time is not newer: there is nothing to move.
 */
internal fun isNewerThanServer(local: Long?, server: Long?): Boolean =
    local != null && (server == null || local > server)

/**
 * CE (#633): the SDK reads and writes dates as local date-times of this device's time zone, and
 * the database keeps epoch milliseconds, which do not depend on it.
 */
internal fun localDateTimeOf(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)

/** CE (#633): the reverse of [localDateTimeOf]. */
internal fun epochMillisOf(dateTime: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): Long =
    dateTime.atZone(zone).toInstant().toEpochMilli()

/**
 * Whether the sync of [rowItemId] has to end a playback session first: the server still has the
 * item playing for this device ([leftPlayingOnServer]) while the player here has closed it. With
 * the item open in the player here ([playingHere]) the session is alive and reports for itself.
 */
internal fun endsStaleSession(rowItemId: UUID, leftPlayingOnServer: UUID?, playingHere: UUID?): Boolean =
    leftPlayingOnServer == rowItemId && playingHere != rowItemId

internal enum class AfterStatus {
    DROP,
    LEAVE,
    RETRY,
}

/** What an error status from the server means for the row that was sent. */
internal fun afterStatus(status: Int): AfterStatus =
    when {
        status == HTTP_NOT_FOUND -> AfterStatus.DROP
        status >= HTTP_SERVER_ERROR -> AfterStatus.RETRY
        else -> AfterStatus.LEAVE
    }

/**
 * Whether rows were marked or changed while the run was sending [sent]: those were not sent, or
 * not in their current state. A row that is still marked exactly as it was sent failed in this
 * run, and that failure decides for itself.
 */
internal fun needsAnotherRun(
    sent: List<FindroidUserDataDto>,
    stillMarked: List<FindroidUserDataDto>,
): Boolean = stillMarked.any { it !in sent }

/** CE (#633): items per request when reading the server's user data, to keep the URL short. */
private const val USER_DATA_BATCH_SIZE = 100

private const val HTTP_NOT_FOUND = 404
private const val HTTP_SERVER_ERROR = 500
