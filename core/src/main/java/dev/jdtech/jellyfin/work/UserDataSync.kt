package dev.jdtech.jellyfin.work

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.core.reachability.ServerReachability
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.repository.UserDataSyncRequester
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Enqueues [SyncWorker], which sends the watch progress, played state and favourites that could
 * not reach the server (#552).
 * - [request]: at app start and whenever a write is marked to be synced. The work is unique and
 *   kept, so asking while a run is waiting for the network or for a retry does nothing.
 * - [start] watches for signs that the server is reachable again: a request of the app's own
 *   reaching the server after failed ones, and [ServerReachability] finding it there when the app
 *   comes to the foreground or the device changes network (home Wi-Fi after mobile data). With
 *   rows waiting, each of those starts the sync over, so it does not sit out the delay of a retry.
 *
 * With the process gone only the retries are left, which is why they back off linearly: after a
 * day without the server the next try is at most about 40 minutes away, not 5 hours.
 *
 * Call [request] off the main thread, because the first call sets up WorkManager (#250).
 */
@Singleton
class UserDataSync
@Inject
constructor(
    private val workManager: Provider<WorkManager>,
    private val database: ServerDatabaseDao,
    private val jellyfinApi: JellyfinApi,
    private val serverReachability: ServerReachability,
) : UserDataSyncRequester {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    override fun request() = enqueue(ExistingWorkPolicy.KEEP, initialDelaySeconds = 0)

    fun request(initialDelaySeconds: Long) = enqueue(ExistingWorkPolicy.KEEP, initialDelaySeconds)

    /** Starts watching for the server coming back. Safe to call more than once. */
    fun start() {
        if (started) return
        started = true

        scope.launch { jellyfinApi.serverReturned.collect { restartIfWaiting("server answered") } }

        // CE (#281): the foreground and the network are watched in one place. It probes the server
        // on those signs, so the sync starts over when the server is there, not on the sign alone.
        scope.launch {
            serverReachability.serverConfirmed.collect { restartIfWaiting("server reachable") }
        }
    }

    private suspend fun restartIfWaiting(reason: String) {
        try {
            // First, and without WorkManager: most of the time nothing is waiting.
            if (!database.hasUserDataToBeSynced()) return
            val state =
                withContext(Dispatchers.IO) {
                    workManager
                        .get()
                        .getWorkInfosForUniqueWork(UNIQUE_WORK_NAME)
                        .get()
                        .firstOrNull()
                        ?.state
                }
            val policy = restartPolicy(hasPending = true, state = state) ?: return
            Timber.d("User data sync: %s, starting over", reason)
            enqueue(policy, RESTART_DELAY_SECONDS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to restart the user data sync")
        }
    }

    private fun enqueue(policy: ExistingWorkPolicy, initialDelaySeconds: Long) {
        val constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        val syncWorkRequest =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
                .build()

        workManager
            .get()
            .enqueueUniqueWork(
                uniqueWorkName = UNIQUE_WORK_NAME,
                existingWorkPolicy = policy,
                request = syncWorkRequest,
            )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "syncUserData"
        private const val BACKOFF_SECONDS = 30L

        /**
         * Long enough for a burst of signs (a network change comes with several) to end in one
         * run, and for a cold start to leave the first seconds to the Home screen (#250).
         */
        private const val RESTART_DELAY_SECONDS = 5L
    }
}

/**
 * What to do with the sync when the server may be reachable again: nothing without rows that are
 * waiting ([hasPending]) or while a run is busy, otherwise start it over, which also drops the
 * delay of a pending retry. [state] is that of the current unique work, null when there is none.
 */
internal fun restartPolicy(hasPending: Boolean, state: WorkInfo.State?): ExistingWorkPolicy? =
    when {
        !hasPending -> null
        state == WorkInfo.State.RUNNING -> null
        else -> ExistingWorkPolicy.REPLACE
    }
