package dev.jdtech.jellyfin.player.cast.debug

import android.annotation.SuppressLint
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.player.cast.CastPlayerController
import dev.jdtech.jellyfin.player.cast.CastSessionManager
import dev.jdtech.jellyfin.player.cast.di.CastStandIn
import dev.jdtech.jellyfin.player.cast.models.CastAvailability
import dev.jdtech.jellyfin.player.cast.models.CastConnectionState
import dev.jdtech.jellyfin.player.cast.models.CastError
import dev.jdtech.jellyfin.player.cast.models.CastMediaItem
import dev.jdtech.jellyfin.player.cast.models.CastPlaybackStatus
import dev.jdtech.jellyfin.player.cast.models.CastPlayerState
import dev.jdtech.jellyfin.player.cast.models.Device
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.core.domain.models.Track
import dev.jdtech.jellyfin.player.local.domain.PlaylistManager
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber

/**
 * Debug builds only: a pretend receiver, for looking at the Cast screens on an emulator, which
 * finds no Cast devices. It is on while the system property `debug.ephyra.cast` starts with `fake`:
 *
 *     adb shell setprop debug.ephyra.cast fake     # then start the app again
 *
 * The property is read again at each step, so a state can be asked for while the app runs:
 * `fake:none` finds no devices, `fake:connectfail` fails to connect, `fake:fail` fails the next
 * play, `fake:error` fails what plays now, `fake:drop` loses the receiver (set `fake` to get it
 * back), `fake:lost` ends the session, `fake:end` runs the item out, `fake:unavailable` is a
 * device without Google Play services. Nothing is reported to the server.
 */
@Module
@InstallIn(SingletonComponent::class)
object FakeCastModule {
    @Provides
    @Singleton
    fun provideCastStandIn(
        playlistManagers: Provider<PlaylistManager>,
        repository: JellyfinRepository,
    ): CastStandIn {
        if (scenario() == null) {
            return object : CastStandIn {
                override val sessionManager: CastSessionManager? = null
                override val playerController: CastPlayerController? = null
            }
        }
        Timber.w("Cast is a stand-in (debug.ephyra.cast): nothing is sent to a receiver")
        val session = FakeCastSessionManager()
        val controller = FakeCastPlayerController(session, playlistManagers, repository)
        return object : CastStandIn {
            override val sessionManager: CastSessionManager = session
            override val playerController: CastPlayerController = controller
        }
    }
}

/** What follows `fake` in the property (empty for plain `fake`), `null` when it is not set. */
@SuppressLint("PrivateApi")
internal fun scenario(): String? {
    val value =
        try {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
                .invoke(null, "debug.ephyra.cast") as? String
        } catch (_: Exception) {
            null
        }
    if (value == null || !value.startsWith("fake")) return null
    return value.removePrefix("fake").removePrefix(":")
}

internal class FakeCastSessionManager : CastSessionManager {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var scanJob: Job? = null
    private var connectJob: Job? = null
    private var started = false

    private val devices =
        listOf(Device(id = "living-room", name = "Living room TV"), Device("bedroom", "Bedroom"))

    override val availability: CastAvailability
        get() =
            if (scenario() == "unavailable") CastAvailability.NO_PLAY_SERVICES
            else CastAvailability.AVAILABLE

    private val _connectionState = MutableStateFlow(CastConnectionState.DISCONNECTED)
    override val connectionState = _connectionState.asStateFlow()

    private val _availableDevices = MutableStateFlow<List<Device>>(emptyList())
    override val availableDevices = _availableDevices.asStateFlow()

    private val _connectedDevice = MutableStateFlow<Device?>(null)
    override val connectedDevice = _connectedDevice.asStateFlow()

    override fun init() {
        if (started) return
        started = true
        // A receiver that goes away, or comes back, while the app runs.
        scope.launch {
            while (true) {
                delay(1000)
                val state = _connectionState.value
                when (scenario()) {
                    "drop" ->
                        if (state == CastConnectionState.CONNECTED) {
                            _connectionState.value = CastConnectionState.CONNECTING
                        }
                    "lost" ->
                        if (state != CastConnectionState.DISCONNECTED) {
                            _connectedDevice.value = null
                            _connectionState.value = CastConnectionState.DISCONNECTED
                        }
                    else ->
                        if (
                            state == CastConnectionState.CONNECTING && connectJob?.isActive != true
                        ) {
                            _connectionState.value = CastConnectionState.CONNECTED
                        }
                }
            }
        }
    }

    override fun updateDiscovery(activeScan: Boolean) {
        if (!activeScan) return
        scanJob?.cancel()
        scanJob =
            scope.launch {
                delay(2500)
                _availableDevices.value = if (scenario() == "none") emptyList() else devices
            }
    }

    override fun connect(device: Device) {
        connectJob?.cancel()
        _connectedDevice.value = device
        _connectionState.value = CastConnectionState.CONNECTING
        connectJob =
            scope.launch {
                delay(2000)
                if (scenario() == "connectfail") {
                    _connectedDevice.value = null
                    _connectionState.value = CastConnectionState.DISCONNECTED
                } else {
                    _connectionState.value = CastConnectionState.CONNECTED
                }
            }
    }

    override fun disconnect() {
        connectJob?.cancel()
        _connectedDevice.value = null
        _connectionState.value = CastConnectionState.DISCONNECTED
    }

    override fun release() {}
}

internal class FakeCastPlayerController(
    private val session: FakeCastSessionManager,
    private val playlistManagers: Provider<PlaylistManager>,
    private val repository: JellyfinRepository,
) : CastPlayerController {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var playJob: Job? = null
    private var manager: PlaylistManager = playlistManagers.get()

    private val _currentItem = MutableStateFlow<CastMediaItem?>(null)
    override val currentItem = _currentItem.asStateFlow()

    private val _playerState = MutableStateFlow(CastPlayerState(volume = 0.6f))
    override val playerState = _playerState.asStateFlow()

    private val _errors = MutableSharedFlow<CastError>(extraBufferCapacity = 1)
    override val errors = _errors.asSharedFlow()

    init {
        scope.launch {
            session.connectionState.collect { state ->
                if (state == CastConnectionState.DISCONNECTED) {
                    playJob?.cancel()
                    _currentItem.value = null
                    _playerState.value = CastPlayerState(volume = 0.6f)
                }
            }
        }
        scope.launch {
            while (true) {
                delay(1000)
                val state = _playerState.value
                if (_currentItem.value == null) continue
                when {
                    scenario() == "error" -> fail()
                    scenario() == "end" ||
                        (state.duration > 0 && state.currentPosition >= state.duration) -> {
                        _playerState.update {
                            it.copy(
                                status = CastPlaybackStatus.ENDED,
                                currentPosition = it.duration,
                            )
                        }
                        _currentItem.value = null
                    }
                    state.status == CastPlaybackStatus.PLAYING &&
                        session.connectionState.value == CastConnectionState.CONNECTED ->
                        _playerState.update { it.copy(currentPosition = it.currentPosition + 1000) }
                }
            }
        }
    }

    private fun fail() {
        _playerState.update { it.copy(status = CastPlaybackStatus.ERROR) }
        _currentItem.value = null
        _errors.tryEmit(CastError.PLAYBACK_FAILED)
    }

    override fun playItem(
        itemId: UUID,
        itemKind: String,
        startFromBeginning: Boolean,
        mediaSourceId: String?,
    ) {
        manager = playlistManagers.get()
        load {
            manager.getInitialItem(
                itemId = itemId,
                itemKind = BaseItemKind.fromName(itemKind),
                mediaSourceId = mediaSourceId,
                startFromBeginning = startFromBeginning,
            )
        }
    }

    private fun load(item: suspend () -> PlayerItem?) {
        playJob?.cancel()
        playJob =
            scope.launch {
                _playerState.update { it.copy(status = CastPlaybackStatus.BUFFERING) }
                val loaded =
                    try {
                        item()
                    } catch (e: Exception) {
                        Timber.e(e, "The stand-in could not load the item")
                        null
                    }
                delay(1200)
                if (loaded == null || scenario() == "fail") {
                    fail()
                    return@launch
                }
                val duration =
                    try {
                        repository.getItem(loaded.itemId)?.runtimeTicks?.div(10_000L) ?: 0L
                    } catch (_: Exception) {
                        0L
                    }
                manager.setCurrentMediaItemIndex(loaded.itemId)
                manager.loadQueue()
                manager.setCurrentMediaItemIndex(loaded.itemId)
                _currentItem.value =
                    CastMediaItem(
                        item = loaded,
                        audioTracks =
                            listOf(
                                Track(1, "English", "eng", "aac", true, true, channelLayout = "stereo"),
                                Track(2, "Nederlands", "nld", "eac3", false, true, channelLayout = "5.1"),
                            ),
                        subtitleTracks =
                            listOf(
                                Track(102, "English", "eng", "subrip", false, true, isExternal = true),
                                Track(
                                    103,
                                    "English",
                                    "eng",
                                    "subrip",
                                    false,
                                    true,
                                    isHearingImpaired = true,
                                ),
                                Track(104, "Nederlands", "nld", "pgssub", false, false),
                            ),
                    )
                _playerState.update {
                    it.copy(
                        status = CastPlaybackStatus.PLAYING,
                        currentPosition = loaded.playbackPosition.coerceAtLeast(0L),
                        duration = duration,
                        hasNextItem = loaded.seriesName != null,
                        hasPreviousItem = (loaded.indexNumber ?: 1) > 1,
                    )
                }
            }
    }

    override fun play() {
        _playerState.update { it.copy(status = CastPlaybackStatus.PLAYING) }
    }

    override fun pause() {
        _playerState.update { it.copy(status = CastPlaybackStatus.PAUSED) }
    }

    override fun seekTo(position: Long) {
        _playerState.update { it.copy(currentPosition = position.coerceAtLeast(0L)) }
    }

    override fun seekToNext() {
        load { manager.getNextPlayerItem() }
    }

    override fun seekToPrevious() {
        load { manager.getPreviousPlayerItem() }
    }

    override fun setVolume(volume: Float) {
        _playerState.update { it.copy(volume = volume.coerceIn(0f, 1f)) }
    }

    override fun setAudioTrack(track: Track?, itemId: UUID?) {
        _currentItem.update { item ->
            item?.copy(audioTracks = item.audioTracks.map { it.copy(selected = it.id == track?.id) })
        }
    }

    override fun setSubtitleTrack(track: Track?) {
        _currentItem.update { item ->
            item?.copy(
                subtitleTracks = item.subtitleTracks.map { it.copy(selected = it.id == track?.id) }
            )
        }
    }

    override fun stop() {
        playJob?.cancel()
        _currentItem.value = null
    }
}
