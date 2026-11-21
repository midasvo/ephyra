package dev.jdtech.jellyfin.film.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.film.presentation.ServerAwayReload
import dev.jdtech.jellyfin.repository.JellyfinRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber

@HiltViewModel
class SearchViewModel @Inject constructor(private val repository: JellyfinRepository) :
    ViewModel() {
    private val _state = MutableStateFlow(SearchState())
    val state = _state.asStateFlow()

    private var currentJob: Job? = null

    private var itemKinds: List<BaseItemKind>? = null

    /**
     * CE: searches [itemKinds] instead of the repository default (movies and shows), for screens
     * that group results per kind (TV).
     */
    fun setup(itemKinds: List<BaseItemKind>) {
        this.itemKinds = itemKinds
    }

    /** The query typed last, which [onServerAwayChanged] searches again. */
    private var lastQuery = ""

    private val serverAwayReload = ServerAwayReload()

    /**
     * CE (#775): the server went away or came back. With the server away the search looks in the
     * downloads, so an open search asks again. The error page's own retry on the server's return
     * lands in the same debounce, so the two send one request.
     */
    fun onServerAwayChanged(away: Boolean) {
        if (serverAwayReload.onServerAway(away) && lastQuery.isNotBlank()) search(lastQuery)
    }

    private fun search(query: String) {
        lastQuery = query
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            try {
                if (query.isBlank()) {
                    _state.emit(SearchState(items = emptyList(), loading = false))
                    return@launch
                }

                // Inside the job, so the next keystroke's cancel also drops this request.
                delay(SEARCH_DEBOUNCE_MS)

                while (true) {
                    serverAwayReload.begin()
                    _state.emit(_state.value.copy(loading = true))
                    val result = repository.getSearchItems(query, itemKinds)

                    // CE (#779): downloads only when Room answered.
                    _state.emit(
                        SearchState(
                            query = query,
                            items = result.items,
                            loading = false,
                            downloadsOnly = result.fromStore,
                        )
                    )
                    // CE (#779): one more try when Room answered before the server was known to be
                    // away; the next keystroke or a change of state cancels it with this job.
                    if (!serverAwayReload.retriesAfterStore(result.fromStore)) break
                    delay(ServerAwayReload.STORE_RETRY_DELAY_MS)
                }
            } catch (_: CancellationException) {} catch (e: Exception) {
                Timber.e(e)
                _state.emit(_state.value.copy(query = query, loading = false, error = e))
            }
        }
    }

    fun onAction(action: SearchAction) {
        when (action) {
            is SearchAction.Search -> {
                search(query = action.query)
            }
            else -> Unit
        }
    }

    companion object {
        internal const val SEARCH_DEBOUNCE_MS = 300L
    }
}
