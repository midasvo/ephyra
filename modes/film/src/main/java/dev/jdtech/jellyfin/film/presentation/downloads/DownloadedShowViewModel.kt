package dev.jdtech.jellyfin.film.presentation.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.AccountKey
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.repository.CurrentAccount
import dev.jdtech.jellyfin.repository.DownloadDeleter
import dev.jdtech.jellyfin.repository.DownloadList
import dev.jdtech.jellyfin.repository.DownloadQueuePort
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The downloaded episodes of one show, grouped by season, for managing them in one place. */
@HiltViewModel
class DownloadedShowViewModel
@Inject
constructor(
    private val account: CurrentAccount,
    private val downloadQueue: DownloadQueuePort,
    private val downloadDeleter: DownloadDeleter,
    private val downloadList: DownloadList,
    private val appPreferences: AppPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow(DownloadedShowState())
    val state = _state.asStateFlow()

    fun loadEpisodes(showId: ItemId) {
        viewModelScope.launch {
            val (episodes, sizes) =
                withContext(Dispatchers.IO) {
                    val userId = account.getUserId()
                    // Episodes with only a `.download` source are still in progress.
                    // CE (#718): the ones the current server downloaded.
                    val episodes =
                        (appPreferences.currentServerId()?.let {
                                downloadList.downloadedEpisodesOfShow(
                                    AccountKey(it, userId),
                                    showId,
                                )
                            } ?: emptyList())
                            .sortedWith(compareBy({ it.parentIndexNumber }, { it.indexNumber }))
                    // An episode's sources are its local sources, read with it.
                    val sizes = episodes.associate { ep ->
                        ep.id to
                            ep.sources.sumOf { src ->
                                File(src.path).takeIf { it.exists() }?.length() ?: 0L
                            }
                    }
                    episodes to sizes
                }
            val seasons =
                episodes
                    .groupBy { it.parentIndexNumber }
                    .map { (number, seasonEpisodes) ->
                        DownloadedSeason(
                            number = number,
                            name = seasonEpisodes.first().seasonName,
                            episodes = seasonEpisodes,
                        )
                    }
            _state.emit(
                DownloadedShowState(seasons = seasons, itemSizes = sizes, isLoading = false)
            )
        }
    }

    fun deleteEpisodes(showId: ItemId, episodes: List<Episode>) {
        downloadDeletionScope.launch {
            for (episode in episodes) {
                downloadQueue.remove(episode.id)
                val source =
                    episode.sources.firstOrNull { it.type == ItemSourceType.LOCAL } ?: continue
                downloadDeleter.deleteItem(episode, source)
            }
            loadEpisodes(showId)
        }
    }
}
