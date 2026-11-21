package dev.jdtech.jellyfin.film.presentation.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.core.Constants
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.ServerAwayReload
import dev.jdtech.jellyfin.film.presentation.collection.CollectionState
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.repository.DataChangeEvents
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.repository.ReloadPolicy
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class FavoritesViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    dataChanges: DataChangeEvents = DataChangeEvents(),
) : ViewModel() {
    private val _state = MutableStateFlow(CollectionState())
    val state = _state.asStateFlow()

    private val serverAwayReload = ServerAwayReload()
    private var loadJob: Job? = null
    private var storeRetryJob: Job? = null

    /**
     * CE (#782): a sync that finished while the screen was not collecting (the app in the
     * background) has still made the list out of date: the screen loads on resume when this says.
     */
    private val reloadPolicy = ReloadPolicy(dataChanges)

    /** Whether coming back to Favorites needs a load, see [ReloadPolicy]. */
    fun shouldReload(): Boolean = reloadPolicy.shouldReload()

    /**
     * CE (#775): the server went away or came back. With the server away the favourites are the
     * downloaded ones, so the list loads again.
     */
    fun onServerAwayChanged(away: Boolean) {
        if (serverAwayReload.onServerAway(away)) loadItems()
    }

    /**
     * CE (#775): the sync has sent changes made while the server was away, such as a favourite set
     * offline. A load that is running may have asked before they arrived, so it makes way for a new
     * one.
     */
    fun onSynced(): Job {
        loadJob?.cancel()
        return start()
    }

    fun loadItems(): Job {
        // CE (#775): the same load twice at once, for the server's return and the error page.
        loadJob?.let { if (it.isActive && serverAwayReload.runningIsCurrent()) return it }
        // A load for the other state makes way, so it cannot land after this one.
        loadJob?.cancel()
        return start()
    }

    private fun start(): Job {
        // CE (#779): a newer load stands in for the one more try after a stored answer.
        storeRetryJob?.cancel()
        serverAwayReload.begin()
        return load().also { loadJob = it }
    }

    private fun load(): Job = viewModelScope.launch {
        val token = reloadPolicy.begin()
        // With content on screen the refresh happens quietly (#251).
        val hasContent = _state.value.sections.isNotEmpty()
        _state.emit(_state.value.copy(isLoading = !hasContent, error = null))

        try {
            val result = repository.getFavoriteItems()
            val items = result.items
            ensureActive()

            val sections = mutableListOf<CollectionSection>()

            withContext(Dispatchers.Default) {
                CollectionSection(
                        Constants.FAVORITE_TYPE_MOVIES,
                        UiText.StringResource(CoreR.string.movies_label),
                        items.filterIsInstance<FindroidMovie>(),
                    )
                    .let {
                        if (it.items.isNotEmpty()) {
                            sections.add(it)
                        }
                    }
                CollectionSection(
                        Constants.FAVORITE_TYPE_SHOWS,
                        UiText.StringResource(CoreR.string.shows_label),
                        items.filterIsInstance<FindroidShow>(),
                    )
                    .let {
                        if (it.items.isNotEmpty()) {
                            sections.add(it)
                        }
                    }
                CollectionSection(
                        Constants.FAVORITE_TYPE_EPISODES,
                        UiText.StringResource(CoreR.string.episodes_label),
                        items.filterIsInstance<FindroidEpisode>(),
                    )
                    .let {
                        if (it.items.isNotEmpty()) {
                            sections.add(it)
                        }
                    }
            }

            // CE (#775): a failed load keeps the list on screen, and with it what it was. CE
            // (#779): downloads only when Room answered, also before the server is known away.
            reloadPolicy.complete(token)
            _state.emit(
                _state.value.copy(
                    isLoading = false,
                    sections = sections,
                    downloadsOnly = result.fromStore,
                )
            )
            if (serverAwayReload.retriesAfterStore(result.fromStore)) {
                storeRetryJob = viewModelScope.launch {
                    delay(ServerAwayReload.STORE_RETRY_DELAY_MS)
                    storeRetryJob = null
                    loadItems()
                }
            }
        } catch (e: CancellationException) {
            // CE (#775): made way for a newer load, which shows its own result.
            throw e
        } catch (e: Exception) {
            _state.emit(_state.value.copy(isLoading = false, error = e))
        }
    }
}
