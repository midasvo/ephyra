package dev.jdtech.jellyfin.player.cast.presentation

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.MediaSourceId
import dev.jdtech.jellyfin.models.Segment
import dev.jdtech.jellyfin.models.SegmentType
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.player.cast.CastPlayerController
import dev.jdtech.jellyfin.player.cast.CastSessionManager
import dev.jdtech.jellyfin.player.cast.models.CastAvailability
import dev.jdtech.jellyfin.player.cast.models.CastConnectionState
import dev.jdtech.jellyfin.player.cast.models.CastPlaybackStatus
import dev.jdtech.jellyfin.player.cast.models.CastPlayerState
import dev.jdtech.jellyfin.player.cast.models.Device
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.core.domain.models.Track
import dev.jdtech.jellyfin.player.local.R as PlayerLocalR
import dev.jdtech.jellyfin.player.local.domain.DebugSegments
import dev.jdtech.jellyfin.player.local.domain.segmentTrackerFrom
import dev.jdtech.jellyfin.repository.PlaybackMedia
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** What the receiver plays, or played last, as the Cast surfaces show it. */
data class CastNowPlaying(
    val itemId: ItemId,
    val kind: ItemKind,
    /** The film's or the episode's own name. */
    val title: String,
    val seriesName: String? = null,
    /** `S03E01`, or `S03E01-E02` for a double episode. */
    val episodeCode: String? = null,
    val artwork: Uri? = null,
)

/** Where the device sheet stands with finding receivers. */
enum class CastDiscovery {
    IDLE,
    SEARCHING,

    /** The search ran its time. With no devices, that is the "nothing found" state. */
    DONE,
}

data class CastUiState(
    val availability: CastAvailability = CastAvailability.NOT_IN_BUILD,
    val connectionState: CastConnectionState = CastConnectionState.DISCONNECTED,
    val discovery: CastDiscovery = CastDiscovery.IDLE,
    val devices: List<Device> = emptyList(),
    /** The receiver that is connected, connecting or reconnecting. */
    val device: Device? = null,
    /** The receiver the last attempt to connect failed on, until the next attempt. */
    val connectFailedDevice: String? = null,
    val nowPlaying: CastNowPlaying? = null,
    /** What ended when the queue ran out, for "play again". */
    val finished: CastNowPlaying? = null,
    /** The receiver could not play what it was given, until something plays again. */
    val playbackFailed: Boolean = false,
    /** What failed, when the receiver got as far as knowing it. */
    val failedItem: CastNowPlaying? = null,
    val canRetry: Boolean = false,
    /** Something was sent to the receiver and has not started, or failed, yet. */
    val isStarting: Boolean = false,
    /** What is starting, when it is something that was shown before (a retry, "play again"). */
    val startingItem: CastNowPlaying? = null,
    val player: CastPlayerState = CastPlayerState(),
    val chapters: List<PlayerChapter> = emptyList(),
    /** CE (#681): every segment of the item, for the seek bar. */
    val segments: List<Segment> = emptyList(),
    val segment: Segment? = null,
    @param:StringRes val skipSegmentLabel: Int = PlayerLocalR.string.player_controls_skip_intro,
    val audioTracks: List<Track> = emptyList(),
    val subtitleTracks: List<Track> = emptyList(),
) {
    val isConnected: Boolean
        get() = connectionState == CastConnectionState.CONNECTED

    /** A session that was up lost its receiver and is trying to get it back. */
    val isReconnecting: Boolean
        get() = connectionState == CastConnectionState.CONNECTING && nowPlaying != null
}

sealed interface CastEvent {
    /** The session ended without the user ending it here. */
    data class Disconnected(val deviceName: String) : CastEvent
}

/**
 * CE (#626): the state of every Cast surface: the top bar button, the device sheet, the mini player
 * and the player. One instance per activity, so they all show the same thing.
 */
@HiltViewModel
class CastViewModel
@Inject
constructor(
    private val sessionManager: CastSessionManager,
    private val playerController: CastPlayerController,
    private val media: PlaybackMedia,
    private val appPreferences: AppPreferences,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private class PlayRequest(
        val itemId: ItemId,
        val kind: ItemKind,
        val mediaSourceId: MediaSourceId?,
        val shuffle: Boolean = false,
    )

    private val _state =
        MutableStateFlow(
            CastUiState(
                availability = sessionManager.availability,
                connectionState = sessionManager.connectionState.value,
                devices = sessionManager.availableDevices.value,
                device = sessionManager.connectedDevice.value,
                player = playerController.playerState.value,
            )
        )
    val state: StateFlow<CastUiState> = _state.asStateFlow()

    private val _events = Channel<CastEvent>(Channel.BUFFERED)
    val events: Flow<CastEvent> = _events.receiveAsFlow()

    /**
     * CE (#851): the local player's segment rule for the positions the receiver reports for the
     * current item. A new one, built from the settings, for each item; null without an item.
     */
    private var segmentSkipper: CastSegmentSkipper? = null
    /** The receiver's [CastPlayerState.load] the skipper knows of. */
    private var segmentLoad = 0
    /** The item the segments belong to, or are being loaded for. */
    private var segmentsItemId: ItemId? = null
    private var segmentsJob: Job? = null
    private var discoveryJob: Job? = null

    private var lastRequest: PlayRequest? = null
    /** What played last, for the failure that takes the item away before it is reported. */
    private var lastPlaying: CastNowPlaying? = null
    /** The receiver the user picked, until it is connected or the attempt failed. */
    private var connectingTo: String? = null
    private var wasConnected = false
    private var lastDeviceName: String? = null
    private var userDisconnected = false

    init {
        // Registers the session listeners; without them no connection is ever seen. It does
        // nothing a second time, and nothing at all in a build without Cast.
        sessionManager.init()

        sessionManager.connectionState.onEach(::onConnectionState).launchIn(viewModelScope)

        sessionManager.availableDevices
            .onEach { devices -> _state.update { it.copy(devices = devices) } }
            .launchIn(viewModelScope)

        sessionManager.connectedDevice
            .onEach { device ->
                if (device != null) lastDeviceName = device.name
                _state.update { it.copy(device = device) }
            }
            .launchIn(viewModelScope)

        playerController.playerState
            .onEach { player ->
                _state.update {
                    it.copy(
                        player = player,
                        playbackFailed =
                            it.playbackFailed && player.status != CastPlaybackStatus.PLAYING,
                        failedItem =
                            it.failedItem.takeIf { player.status != CastPlaybackStatus.PLAYING },
                    )
                }
                updateSegment(player)
            }
            .launchIn(viewModelScope)

        playerController.currentItem
            .onEach { item ->
                _state.update {
                    it.copy(
                        subtitleTracks = item?.subtitleTracks.orEmpty(),
                        audioTracks = item?.audioTracks.orEmpty(),
                    )
                }
                if (item != null) onItem(item.item) else onNoItem()
            }
            .launchIn(viewModelScope)

        playerController.errors
            .onEach {
                _state.update {
                    it.copy(
                        playbackFailed = true,
                        failedItem = it.nowPlaying ?: it.startingItem ?: lastPlaying,
                        isStarting = false,
                        startingItem = null,
                        canRetry = canRetry(),
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    private fun onConnectionState(connection: CastConnectionState) {
        when (connection) {
            CastConnectionState.CONNECTED -> {
                wasConnected = true
                connectingTo = null
                userDisconnected = false
                _state.update { it.copy(connectionState = connection, connectFailedDevice = null) }
            }
            CastConnectionState.CONNECTING ->
                _state.update { it.copy(connectionState = connection) }
            CastConnectionState.DISCONNECTED -> {
                val failed = connectingTo.takeIf { !wasConnected }
                if (wasConnected && !userDisconnected) {
                    lastDeviceName?.let { _events.trySend(CastEvent.Disconnected(it)) }
                }
                wasConnected = false
                connectingTo = null
                userDisconnected = false
                lastRequest = null
                onNoItem()
                _state.update {
                    it.copy(
                        connectionState = connection,
                        connectFailedDevice = failed ?: it.connectFailedDevice,
                        finished = null,
                        playbackFailed = false,
                        failedItem = null,
                        canRetry = false,
                        isStarting = false,
                        startingItem = null,
                    )
                }
            }
        }
    }

    private fun onItem(item: PlayerItem) {
        Timber.d("Cast item: ${item.itemId.asString()}")
        lastPlaying = item.toNowPlaying()
        _state.update {
            it.copy(
                nowPlaying = item.toNowPlaying(),
                finished = null,
                isStarting = false,
                startingItem = null,
                chapters = item.chapters.withImages(item.itemId),
            )
        }

        if (item.itemId == segmentsItemId) return
        segmentsItemId = item.itemId
        // The settings may have changed since the last item. Until its segments load, the new
        // item's skipper has none, and no position of the previous item reaches it.
        val skipper = newSegmentSkipper(item)
        segmentSkipper = skipper
        segmentLoad = playerController.playerState.value.load
        _state.update { it.copy(segment = null, segments = emptyList()) }
        segmentsJob?.cancel()
        segmentsJob = viewModelScope.launch {
            val loaded =
                try {
                    // Debuggable builds can fake segments for testing (#271, #681).
                    DebugSegments.read(context) ?: media.getSegments(item.itemId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to load segments for item ${item.itemId.asString()}")
                    emptyList()
                }
            // Another item took over, or the session ended, while these were loading.
            if (segmentSkipper === skipper) {
                skipper.setSegments(loaded)
                // CE (#681): the seek bar shows them unless the setting is off.
                if (appPreferences.getValue(appPreferences.playerMediaSegmentsOnSeekBar)) {
                    _state.update { it.copy(segments = loaded) }
                }
            }
        }
    }

    private fun onNoItem() {
        segmentsJob?.cancel()
        segmentsItemId = null
        segmentSkipper = null
        // The receiver finished its last item: that is what "play again" offers.
        val ended = playerController.playerState.value.status == CastPlaybackStatus.ENDED
        _state.update {
            it.copy(
                nowPlaying = null,
                finished = if (ended) it.nowPlaying ?: it.finished else it.finished,
                chapters = emptyList(),
                segment = null,
                segments = emptyList(),
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                canRetry = canRetry(),
            )
        }
    }

    private fun PlayerItem.toNowPlaying(): CastNowPlaying {
        val season = parentIndexNumber
        val episode = indexNumber
        val code =
            if (season != null && episode != null) {
                val start = "S${season.pad()}E${episode.pad()}"
                indexNumberEnd?.let { "$start-E${it.pad()}" } ?: start
            } else {
                null
            }
        return CastNowPlaying(
            itemId = itemId,
            kind = if (seriesName != null) ItemKind.EPISODE else ItemKind.MOVIE,
            title = name,
            seriesName = seriesName,
            episodeCode = code,
            artwork = artworkUri,
        )
    }

    private fun Int.pad(): String = toString().padStart(2, '0')

    /** The chapters with the URL of their thumbnail, where the server made one. */
    private fun List<PlayerChapter>.withImages(itemId: ItemId): List<PlayerChapter> {
        return mapIndexed { index, chapter ->
            val tag = chapter.imageTag ?: return@mapIndexed chapter
            val url = media.chapterImageUrl(itemId, index, tag) ?: return@mapIndexed chapter
            chapter.copy(imageUri = url)
        }
    }

    private fun newSegmentSkipper(item: PlayerItem) =
        CastSegmentSkipper(
            itemId = item.itemId,
            tracker = segmentTrackerFrom(appPreferences),
            now = SystemClock::elapsedRealtime,
            resumePositionMs = item.playbackPosition,
        )

    /**
     * CE (#851): the same rule as the local player. A segment set to skip is skipped on the
     * receiver, one set to ask shows the button, an ignored one nothing. Only a position the
     * receiver reported for the current item counts ([CastSegmentSkipper]).
     */
    private fun updateSegment(player: CastPlayerState) {
        val skipper = segmentSkipper
        val step =
            if (
                skipper == null ||
                    (player.positionItemId != null && player.positionItemId != skipper.itemId)
            ) {
                // This load belongs to another item; don't let it reset the old skipper or leak
                // into the old item's later reports before currentItem catches up (#1027).
                if (skipper != null && player.load != segmentLoad) {
                    segmentLoad = player.load
                }
                CastSegmentStep.None
            } else {
                // Another load of the item, or a start the controller learned of: wait for its
                // position.
                if (player.load != segmentLoad) {
                    segmentLoad = player.load
                    skipper.expectLoad(player.loadPositionMs)
                }
                skipper.update(
                    player.positionItemId,
                    player.currentPosition,
                    player.duration,
                    fromSeek = player.positionFromSeek,
                )
            }
        val active =
            when (step) {
                CastSegmentStep.None -> null
                is CastSegmentStep.ShowButton -> step.segment
                is CastSegmentStep.SeekTo -> {
                    Timber.d("Cast: skipping %s to %d ms", step.segment, step.positionMs)
                    // Not seekTo(): this seek is not the user's.
                    playerController.seekTo(step.positionMs)
                    null
                }
            }
        if (active == _state.value.segment) return
        _state.update {
            it.copy(
                segment = active,
                skipSegmentLabel =
                    when (active?.type) {
                        SegmentType.OUTRO -> PlayerLocalR.string.player_controls_skip_outro
                        SegmentType.RECAP -> PlayerLocalR.string.player_controls_skip_recap
                        SegmentType.PREVIEW -> PlayerLocalR.string.player_controls_skip_preview
                        SegmentType.COMMERCIAL ->
                            PlayerLocalR.string.player_controls_skip_commercial
                        SegmentType.INTRO -> PlayerLocalR.string.player_controls_skip_intro
                        else -> PlayerLocalR.string.player_controls_skip_unknown
                    },
            )
        }
    }

    private fun canRetry(): Boolean =
        lastRequest != null || _state.value.nowPlaying != null || lastPlaying != null

    /** The settings or the server may have changed since the state was last shown. */
    fun refreshAvailability() {
        val availability = sessionManager.availability
        if (availability != _state.value.availability) {
            _state.update { it.copy(availability = availability) }
        }
    }

    /** The device sheet is on screen: look for receivers, for a while. */
    fun startDiscovery() {
        refreshAvailability()
        if (!sessionManager.isSupported) return
        sessionManager.updateDiscovery(activeScan = true)
        _state.update { it.copy(discovery = CastDiscovery.SEARCHING) }
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch {
            delay(SEARCH_MILLIS)
            _state.update { it.copy(discovery = CastDiscovery.DONE) }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        if (sessionManager.isSupported) sessionManager.updateDiscovery(activeScan = false)
        _state.update { it.copy(discovery = CastDiscovery.IDLE, connectFailedDevice = null) }
    }

    fun connect(device: Device) {
        connectingTo = device.name
        lastDeviceName = device.name
        _state.update { it.copy(connectFailedDevice = null) }
        sessionManager.connect(device)
    }

    fun disconnect() {
        userDisconnected = true
        sessionManager.disconnect()
    }

    /**
     * Plays the item on the connected receiver, with the rest of its playlist behind it. Returns
     * `false` when there is no receiver, and the caller plays the item on the device.
     */
    fun playItem(
        itemId: ItemId,
        kind: ItemKind,
        startFromBeginning: Boolean,
        mediaSourceId: MediaSourceId?,
        shuffle: Boolean = false,
    ): Boolean =
        play(itemId, kind, startFromBeginning, mediaSourceId, known = null, shuffle = shuffle)

    private fun play(
        itemId: ItemId,
        kind: ItemKind,
        startFromBeginning: Boolean,
        mediaSourceId: MediaSourceId?,
        known: CastNowPlaying?,
        shuffle: Boolean = false,
    ): Boolean {
        if (sessionManager.connectionState.value != CastConnectionState.CONNECTED) return false
        lastRequest = PlayRequest(itemId, kind, mediaSourceId, shuffle)
        lastPlaying = null
        _state.update {
            it.copy(
                playbackFailed = false,
                failedItem = null,
                finished = null,
                isStarting = true,
                startingItem = known,
            )
        }
        playerController.playItem(
            itemId,
            kind.serialName,
            startFromBeginning,
            mediaSourceId,
            shuffle,
        )
        return true
    }

    /** After a failure: the item that failed, from where the server has it. */
    fun retry() {
        val current = _state.value.nowPlaying ?: _state.value.failedItem
        val request = lastRequest
        when {
            current != null ->
                play(
                    current.itemId,
                    current.kind,
                    startFromBeginning = false,
                    mediaSourceId =
                        request?.mediaSourceId?.takeIf { request.itemId == current.itemId },
                    known = current,
                )
            request != null ->
                playItem(
                    request.itemId,
                    request.kind,
                    startFromBeginning = false,
                    mediaSourceId = request.mediaSourceId,
                    shuffle = request.shuffle,
                )
        }
    }

    fun playAgain() {
        val finished = _state.value.finished ?: return
        play(
            finished.itemId,
            finished.kind,
            startFromBeginning = true,
            mediaSourceId = null,
            known = finished,
        )
    }

    fun dismissFinished() {
        _state.update { it.copy(finished = null) }
    }

    fun togglePlayPause() {
        if (_state.value.player.status == CastPlaybackStatus.PLAYING) {
            playerController.pause()
        } else {
            playerController.play()
        }
    }

    fun seekTo(positionMs: Long) {
        segmentSkipper?.onUserSeek(positionMs)
        playerController.seekTo(positionMs)
    }

    fun seekBy(deltaMs: Long) {
        val player = _state.value.player
        val target = player.currentPosition + deltaMs
        seekTo(if (player.duration > 0L) target.coerceIn(0L, player.duration) else target)
    }

    fun next() {
        playerController.seekToNext()
    }

    /** Back to the start of the item, or to the previous one from within its first seconds. */
    fun previous() {
        val player = _state.value.player
        if (player.currentPosition > RESTART_THRESHOLD_MILLIS || !player.hasPreviousItem) {
            seekTo(0L)
        } else {
            playerController.seekToPrevious()
        }
    }

    fun skipSegment() {
        _state.value.segment?.let { seekTo(it.endTicks) }
    }

    fun setVolume(volume: Float) {
        playerController.setVolume(volume)
    }

    fun setAudioTrack(track: Track) {
        playerController.setAudioTrack(track, _state.value.nowPlaying?.itemId)
    }

    fun setSubtitleTrack(track: Track?) {
        playerController.setSubtitleTrack(track)
    }

    companion object {
        /** How long the device sheet says it is searching before it says what it found. */
        const val SEARCH_MILLIS = 10_000L

        /** Previous restarts the item when it is further in than this. */
        const val RESTART_THRESHOLD_MILLIS = 5_000L
    }
}
