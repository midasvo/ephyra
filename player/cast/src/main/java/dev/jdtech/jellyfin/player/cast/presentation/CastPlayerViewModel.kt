package dev.jdtech.jellyfin.player.cast.presentation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.models.FindroidSegment
import dev.jdtech.jellyfin.models.FindroidSegmentType
import dev.jdtech.jellyfin.player.cast.CastPlayerController
import dev.jdtech.jellyfin.player.cast.CastSessionManager
import dev.jdtech.jellyfin.player.cast.models.CastConnectionState
import dev.jdtech.jellyfin.player.cast.models.CastPlaybackStatus
import dev.jdtech.jellyfin.player.cast.models.CastPlayerState
import dev.jdtech.jellyfin.player.cast.models.Device
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.core.domain.models.Track
import dev.jdtech.jellyfin.player.local.R as PlayerLocalR
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class CastPlayerViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    val sessionManager: CastSessionManager,
    val playerController: CastPlayerController,
    private val repository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    data class CurrentItemTitle(
        val seriesName: String? = null,
        val episodeInfo: String? = null,
        val title: String,
    )

    data class UiState(
        val connectionState: CastConnectionState = CastConnectionState.DISCONNECTED,
        val playerState: CastPlayerState = CastPlayerState(),
        val availableDevices: List<Device> = emptyList(),
        val connectedDevice: Device? = null,
        val currentItemTitle: CurrentItemTitle = CurrentItemTitle(title = ""),
        val currentItemArtwork: Uri? = null,
        val isMovie: Boolean = false,
        val defaultAspectRatio: Float = 16f / 10f,
        val currentSegment: FindroidSegment? = null,
        val currentSkipButtonStringRes: Int = PlayerLocalR.string.player_controls_skip_intro,
        val currentChapters: List<PlayerChapter> = emptyList(),
        val segments: List<FindroidSegment> = emptyList(),
        val fileLoaded: Boolean = false,
        val audioTracks: List<Track> = emptyList(),
        val subtitleTracks: List<Track> = emptyList(),
    )

    private val _uiState =
        MutableStateFlow(
            UiState(
                connectionState = sessionManager.connectionState.value,
                availableDevices = sessionManager.availableDevices.value,
                connectedDevice = sessionManager.connectedDevice.value,
                playerState = playerController.playerState.value,
            )
        )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var currentMediaItemSegments: List<FindroidSegment> = emptyList()
    /** The item [currentMediaItemSegments] belongs to, or is being loaded for. */
    private var segmentsItemId: UUID? = null
    private var segmentsJob: Job? = null
    var currentItemId: UUID? =
        savedStateHandle.get<String>(KEY_CURRENT_ITEM_ID)?.let {
            try {
                UUID.fromString(it)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        private set

    init {
        sessionManager.connectionState
            .onEach { value ->
                _uiState.update { it.copy(connectionState = value) }
                if (value == CastConnectionState.DISCONNECTED) {
                    onMediaItemCleared()
                }
            }
            .launchIn(viewModelScope)

        sessionManager.availableDevices
            .onEach { value -> _uiState.update { it.copy(availableDevices = value) } }
            .launchIn(viewModelScope)

        sessionManager.connectedDevice
            .onEach { value -> _uiState.update { it.copy(connectedDevice = value) } }
            .launchIn(viewModelScope)

        playerController.playerState
            .onEach { value ->
                _uiState.update { it.copy(playerState = value) }
                updateCurrentSegment(value.currentPosition)
            }
            .launchIn(viewModelScope)

        playerController.currentItem
            .onEach { item ->
                _uiState.update {
                    it.copy(
                        subtitleTracks = item?.subtitleTracks ?: emptyList(),
                        audioTracks = item?.audioTracks ?: emptyList(),
                    )
                }
                if (item != null) {
                    onMediaItemTransition(item.item)
                } else {
                    onMediaItemCleared()
                }
            }
            .launchIn(viewModelScope)
    }

    private suspend fun onMediaItemTransition(item: PlayerItem) {
        Timber.d("Cast MediaItem transition: ${item.itemId}")
        currentItemId = item.itemId
        savedStateHandle[KEY_CURRENT_ITEM_ID] = item.itemId.toString()
        val isMovie = item.seriesName == null

        val defaultRatio =
            if (isMovie) 2f / 3f else 16f / 9f

        val itemTitle =
            if (item.parentIndexNumber != null && item.indexNumber != null) {
                val parentIndex = item.parentIndexNumber.toString().padStart(2, '0')
                val index = item.indexNumber.toString().padStart(2, '0')
                val episodeInfo =
                    if (item.indexNumberEnd == null) {
                        "S${parentIndex}E$index"
                    } else {
                        val indexEnd = item.indexNumberEnd.toString().padStart(2, '0')
                        "S${parentIndex}E$index-E$indexEnd"
                    }

                CurrentItemTitle(
                    seriesName = item.seriesName,
                    episodeInfo = episodeInfo,
                    title = item.name,
                )
            } else {
                CurrentItemTitle(title = item.name)
            }

        _uiState.update {
            it.copy(
                currentItemTitle = itemTitle,
                currentItemArtwork = item.artworkUri,
                isMovie = isMovie,
                defaultAspectRatio = defaultRatio,
                currentChapters = item.chapters,
                fileLoaded = true,
            )
        }

        if (item.itemId == segmentsItemId) return
        segmentsItemId = item.itemId
        currentMediaItemSegments = emptyList()
        _uiState.update { it.copy(segments = emptyList(), currentSegment = null) }
        segmentsJob?.cancel()
        segmentsJob =
            viewModelScope.launch {
                val segments =
                    try {
                        repository.getSegments(item.itemId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to load segments for item ${item.itemId}")
                        emptyList()
                    }
                // Another item took over, or the session ended, while these were loading.
                if (segmentsItemId != item.itemId) return@launch
                currentMediaItemSegments = segments
                _uiState.update { it.copy(segments = segments) }
            }
    }

    private fun onMediaItemCleared() {
        segmentsJob?.cancel()
        segmentsItemId = null
        currentItemId = null
        savedStateHandle.remove<String>(KEY_CURRENT_ITEM_ID)
        currentMediaItemSegments = emptyList()
        _uiState.update {
            it.copy(
                currentItemTitle = CurrentItemTitle(title = ""),
                currentItemArtwork = null,
                isMovie = false,
                currentSegment = null,
                currentChapters = emptyList(),
                segments = emptyList(),
                fileLoaded = false,
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
            )
        }
    }

    private fun updateCurrentSegment(positionMs: Long) {
        if (currentMediaItemSegments.isEmpty()) {
            if (_uiState.value.currentSegment != null) {
                _uiState.update { it.copy(currentSegment = null) }
            }
            return
        }

        val active =
            currentMediaItemSegments.firstOrNull { segment ->
                positionMs >= segment.startTicks && positionMs < segment.endTicks
            }

        val skipStringRes =
            when (active?.type) {
                FindroidSegmentType.OUTRO -> PlayerLocalR.string.player_controls_skip_outro
                else -> PlayerLocalR.string.player_controls_skip_intro
            }

        _uiState.update {
            it.copy(
                currentSegment = active,
                currentSkipButtonStringRes = skipStringRes,
            )
        }
    }

    fun play() {
        playerController.play()
    }

    fun pause() {
        playerController.pause()
    }

    fun togglePlayPause() {
        if (_uiState.value.playerState.status == CastPlaybackStatus.PLAYING) {
            pause()
        } else {
            play()
        }
    }

    fun seekTo(position: Long) {
        playerController.seekTo(position)
    }

    fun playNextItem() {
        playerController.seekToNext()
    }

    fun rewindOrPlayPreviousItem() {
        if (_uiState.value.playerState.currentPosition > 5000L) {
            seekTo(0L)
        } else {
            playerController.seekToPrevious()
        }
    }

    fun skipSegment(segment: FindroidSegment) {
        seekTo(segment.endTicks)
    }

    fun setVolume(volume: Float) {
        playerController.setVolume(volume)
    }

    fun setAudioTrack(track: Track?) {
        playerController.setAudioTrack(track, currentItemId)
    }

    fun setSubtitleTrack(track: Track?) {
        playerController.setSubtitleTrack(track)
    }

    companion object {
        private const val KEY_CURRENT_ITEM_ID = "cast_current_item_id"
    }
}
