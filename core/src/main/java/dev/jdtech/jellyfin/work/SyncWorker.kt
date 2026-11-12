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
import dev.jdtech.jellyfin.repository.PlaybackEvents
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
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
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            val servers = database.getServers()
            var hadFailures = false

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
                    if (!syncUserData(api, user.id, pending)) hadFailures = true
                    // A write that failed while this run was sending keeps its row marked, and
                    // asking for a sync during a run does nothing: run again for those rows.
                    val stillMarked = database.getAllUserDataToBeSynced(server.id, user.id)
                    if (needsAnotherRun(sent = pending, stillMarked = stillMarked)) {
                        hadFailures = true
                    }
                }
            }

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

    private suspend fun syncUserData(
        api: ApiClient,
        userId: UUID,
        items: List<FindroidUserDataDto>,
    ): Boolean {
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
                return false
            }

        var allOk = true
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
                        ),
                )

                database.clearUserDataToBeSynced(
                    userId = userId,
                    itemId = userData.itemId,
                    played = userData.played,
                    favorite = userData.favorite,
                    playbackPositionTicks = userData.playbackPositionTicks,
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
                return false
            }
        }
        return allOk
    }
}

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

private const val HTTP_NOT_FOUND = 404
private const val HTTP_SERVER_ERROR = 500
