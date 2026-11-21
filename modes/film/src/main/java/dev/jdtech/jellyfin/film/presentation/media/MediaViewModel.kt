package dev.jdtech.jellyfin.film.presentation.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.film.presentation.ServerAwayReload
import dev.jdtech.jellyfin.repository.DataChangeEvents
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.repository.ReloadPolicy
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class MediaViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    dataChanges: DataChangeEvents = DataChangeEvents(),
) : ViewModel() {
    private val reloadPolicy = ReloadPolicy(dataChanges)

    /** Whether coming back to the Media tab needs a load (#251), see [ReloadPolicy]. */
    fun shouldReload(): Boolean = reloadPolicy.shouldReload()

    private val _state = MutableStateFlow(MediaState())
    val state = _state.asStateFlow()

    /**
     * When the libraries last loaded, from [System.nanoTime], or null before the first successful
     * load. CE (#257): TV Main reloads only when this is old, instead of on every return.
     */
    var librariesLoadedAtNanos: Long? = null
        private set

    private val serverAwayReload = ServerAwayReload()
    private var loadJob: Job? = null
    private var storeRetryJob: Job? = null

    /**
     * Loads the libraries. [silent] (CE, #257) leaves the current list and loading state alone: no
     * loading flash, and a failure keeps the list on screen.
     */
    fun loadData(silent: Boolean = false) {
        // CE (#775): the same load twice at once, for the server's return and a resume.
        if (loadJob?.isActive == true && serverAwayReload.runningIsCurrent()) return
        // CE (#775): a load for the other state makes way, so it cannot land after this one. The
        // policy is only completed by a load that got through, so it stays as it was. A visible
        // load that is cut off leaves its loading state to this one.
        val replaced = loadJob?.takeIf { it.isActive }
        replaced?.cancel()
        val quiet = silent && !(replaced != null && _state.value.isLoading)
        // CE (#779): a newer load stands in for the one more try after a stored answer.
        storeRetryJob?.cancel()
        serverAwayReload.begin()
        loadJob = viewModelScope.launch {
            val token = reloadPolicy.begin()
            if (!quiet) _state.emit(_state.value.copy(isLoading = true, error = null))
            try {
                val result = repository.getLibraries()
                ensureActive()
                librariesLoadedAtNanos = System.nanoTime()
                reloadPolicy.complete(token)
                _state.emit(_state.value.copy(libraries = result.items, error = null))
                // CE (#779): the stored libraries before the server was known to be away get one
                // more try, quietly, so they do not stay when the server did answer the probe.
                if (serverAwayReload.retriesAfterStore(result.fromStore)) {
                    storeRetryJob = viewModelScope.launch {
                        delay(ServerAwayReload.STORE_RETRY_DELAY_MS)
                        storeRetryJob = null
                        loadData(silent = true)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (quiet) Timber.e(e, "Could not refresh the libraries")
                else _state.emit(_state.value.copy(error = e))
            }
            if (!quiet) _state.emit(_state.value.copy(isLoading = false))
        }
    }

    /**
     * CE (#775): the server went away or came back. With the server away the libraries are the
     * downloads, so the list loads again, quietly when there is one on screen.
     */
    fun onServerAwayChanged(away: Boolean) {
        if (serverAwayReload.onServerAway(away)) {
            loadData(silent = _state.value.libraries.isNotEmpty())
        }
    }

    fun onAction(action: MediaAction) {
        when (action) {
            is MediaAction.OnRetryClick -> {
                loadData()
            }
            else -> Unit
        }
    }
}
