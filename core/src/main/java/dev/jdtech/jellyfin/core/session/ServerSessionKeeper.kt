package dev.jdtech.jellyfin.core.session

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.core.R
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.PlaystateMessage
import timber.log.Timber

/**
 * CE (#334): keeps the app's session on the server in step with the app.
 *
 * Jellyfin removes a session from its list (`/Sessions`, the dashboard's devices) when the last
 * WebSocket of that session closes, and otherwise only on sign-out or a server restart: there is no
 * idle timeout. So while the app is in the foreground and online, a WebSocket stays open, and when
 * the app goes to the background or its process dies the socket closes and the session goes with
 * it. The token stays valid; the next request or socket opens a new session.
 *
 * It also notices when the server has revoked the current token (signed out from the dashboard, the
 * device deleted): after a 401 it checks `/Users/Me`, and when that is refused too it signs the
 * user out here, so the app shows the user selection instead of failing on every screen.
 */
@Singleton
class ServerSessionKeeper
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val jellyfinApi: JellyfinApi,
    private val appPreferences: AppPreferences,
    private val database: ServerDatabaseDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    /** When the token was last confirmed valid after a 401, to not ask again for every request. */
    private var lastConfirmedAt = 0L

    /** Starts both. Safe to call more than once. */
    fun start() {
        if (started) return
        started = true
        keepSocketInForeground()
        signOutRevokedTokens()
    }

    private fun keepSocketInForeground() {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        scope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                appPreferences
                    .observe(appPreferences.offlineMode)
                    .distinctUntilChanged()
                    .collectLatest { offline ->
                        if (offline) return@collectLatest
                        // Any subscription keeps the socket open; the SDK closes it a few seconds
                        // after
                        // the last one ends, and reconnects with the new token or address on a
                        // switch.
                        // Play state messages need no start message, so nothing is streamed to us.
                        jellyfinApi.api.webSocket
                            .subscribe<PlaystateMessage>()
                            .catch { e -> Timber.w(e, "Session socket failed") }
                            .collect { message ->
                                Timber.d("Ignoring play state command %s", message)
                            }
                    }
            }
        }
    }

    private fun signOutRevokedTokens() {
        scope.launch {
            jellyfinApi.unauthorized.collect { token ->
                try {
                    if (jellyfinApi.api.accessToken != token) return@collect
                    if (SystemClock.elapsedRealtime() - lastConfirmedAt < RECHECK_AFTER_MS) {
                        return@collect
                    }
                    if (isRevoked()) {
                        signOut(token)
                    } else {
                        lastConfirmedAt = SystemClock.elapsedRealtime()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to check the session")
                }
            }
        }
    }

    /** Whether the server refuses the current token outright, rather than for one request. */
    private suspend fun isRevoked(): Boolean =
        try {
            jellyfinApi.userApi.getCurrentUser()
            false
        } catch (e: InvalidStatusException) {
            e.status == 401
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline or a server error: nothing says the token is gone.
            false
        }

    private suspend fun signOut(token: String) {
        val serverId = appPreferences.getValue(appPreferences.currentServer) ?: return
        val user = database.getServerCurrentUser(serverId) ?: return
        // Switched to another user meanwhile.
        if (user.accessToken != token || jellyfinApi.api.accessToken != token) return
        val server = database.getServer(serverId)

        Timber.i("The server revoked the token of ${user.name}, signing out")
        database.clearServerCurrentUser(serverId)
        database.deleteUser(user.id)
        jellyfinApi.api.update(accessToken = null)
        jellyfinApi.userId = null

        withContext(Dispatchers.Main) {
            Toast.makeText(
                    context,
                    context.getString(R.string.ce_session_revoked, server?.name ?: ""),
                    Toast.LENGTH_LONG,
                )
                .show()
        }
    }

    private companion object {
        const val RECHECK_AFTER_MS = 60_000L
    }
}
