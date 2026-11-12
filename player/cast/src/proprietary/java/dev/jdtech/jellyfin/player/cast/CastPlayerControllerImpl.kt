package dev.jdtech.jellyfin.player.cast

import android.content.Context
import android.net.Uri
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.player.cast.devices.Chromecast
import dev.jdtech.jellyfin.player.cast.devices.ChromecastH265
import dev.jdtech.jellyfin.player.cast.models.CastConnectionState
import dev.jdtech.jellyfin.player.cast.models.CastError
import dev.jdtech.jellyfin.player.cast.models.CastMediaItem
import dev.jdtech.jellyfin.player.cast.models.CastPlaybackStatus
import dev.jdtech.jellyfin.player.cast.models.CastPlayerState
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.core.domain.models.Track
import dev.jdtech.jellyfin.player.local.domain.PlaylistManager
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.json.JSONObject
import timber.log.Timber

/**
 * Plays on the connected Cast receiver (Google's Default Media Receiver).
 *
 * The receiver fetches the stream itself, so every URL it gets carries what it needs: the server's
 * transcoding URL has the access token in it, a direct-play URL gets one added. Each queue item
 * also carries its Jellyfin item id and playback info as custom data, which is all this class needs
 * to pick a session up again after the app's process was gone.
 *
 * All state is touched on the main thread only: the Cast SDK calls back there, and [scope] runs
 * there.
 */
@Singleton
class CastPlayerControllerImpl
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val jellyfinApi: JellyfinApi,
    private val repository: JellyfinRepository,
    private val sessionManager: CastSessionManager,
    private val playlistManagers: Provider<PlaylistManager>,
    private val appPreferences: AppPreferences,
) : CastPlayerController {

    internal var mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val _currentItem = MutableStateFlow<CastMediaItem?>(null)
    override val currentItem: StateFlow<CastMediaItem?> = _currentItem.asStateFlow()

    private val _playerState = MutableStateFlow(CastPlayerState())
    override val playerState: StateFlow<CastPlayerState> = _playerState.asStateFlow()

    private val _errors = MutableSharedFlow<CastError>(extraBufferCapacity = 1)
    override val errors: SharedFlow<CastError> = _errors.asSharedFlow()

    private var _castContext: CastContext? = null
    private val castContext: CastContext?
        get() {
            if (_castContext != null) return _castContext
            return try {
                CastContext.getSharedInstance(context).also { _castContext = it }
            } catch (e: Exception) {
                Timber.e(e, "Failed to get CastContext")
                null
            }
        }

    private var remoteMediaClient: RemoteMediaClient? = null
    private var castSession: CastSession? = null

    private val json = Json { ignoreUnknownKeys = true }
    private val queueMutex = Mutex()
    private val scope = CoroutineScope(mainDispatcher + SupervisorJob())

    private var playJob: Job? = null
    private var audioTrackJob: Job? = null
    private var queueJob: Job? = null
    private var restoreJob: Job? = null

    private var isSessionRestored = false

    /**
     * A load of ours is on its way to the receiver. Until the receiver answers, it still shows
     * what it played before, and that must not be taken for the current item again.
     */
    private var loadPending = false

    /** The item whose start was reported and whose stop is still owed, if any. */
    private var reportingItemId: UUID? = null
    /** The last position the receiver gave for [reportingItemId], for its stop report. */
    private var reportingPositionMs = 0L

    /**
     * One per thing the user started (or per restored session). A [PlaylistManager] remembers every
     * item it handed out and does not hand it out again, so one that outlived its playlist would
     * leave the next playlist's queue empty.
     */
    private var playlistManager: PlaylistManager = playlistManagers.get()

    /** What was built for each item in the receiver's queue. */
    private val itemCache = ConcurrentHashMap<UUID, CastMediaItem>()
    private val itemDuration = ConcurrentHashMap<UUID, Long>()

    /**
     * The playlist's order, as far as it is known. [playlistManager] returns a neighbour once, so
     * the answer is kept: it is asked again each time the receiver moves to another item, and after
     * an audio switch replaced the receiver's queue.
     */
    private val playerItems = HashMap<UUID, PlayerItem>()
    private val nextOf = HashMap<UUID, UUID>()
    private val previousOf = HashMap<UUID, UUID>()

    private class BuiltMedia(
        val mediaInfo: MediaInfo,
        val castItem: CastMediaItem,
        val activeTrackIds: LongArray,
    )

    private class Tracks(
        val castTracks: List<MediaTrack>,
        val subtitles: List<Track>,
        val audio: List<Track>,
    )

    private val remoteMediaClientCallback =
        object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() {
                if (!isSessionRestored && remoteMediaClient?.mediaStatus != null) {
                    restoreSession()
                }
                updatePlaybackStatus()
            }

            override fun onMetadataUpdated() {
                val client = remoteMediaClient ?: return
                val itemId = remoteItemId() ?: return
                if (loadPending) return
                if (_currentItem.value?.item?.itemId == itemId) {
                    itemDuration[itemId] = client.streamDuration
                    return
                }

                val cachedItem = itemCache[itemId]
                if (cachedItem != null) {
                    _currentItem.value = cachedItem
                    itemDuration[itemId] = client.streamDuration
                    onCurrentItemSet(itemId)
                } else {
                    restoreRemoteItem()
                }
            }

            override fun onQueueStatusUpdated() {
                // The receiver moved on: the item that was playing is done, wherever the
                // reports for the new one stand.
                val reporting = reportingItemId ?: return
                if (remoteItemId() != reporting) stopReporting()
            }

            override fun onMediaError(error: MediaError) {
                Timber.e("Media error: ${error.reason} (${error.detailedErrorCode})")
                _playerState.update { it.copy(status = CastPlaybackStatus.ERROR) }
                _errors.tryEmit(CastError.PLAYBACK_FAILED)
            }
        }

    private val playbackReportingCallback =
        RemoteMediaClient.ProgressListener { progressMs, _ ->
            reportPlaybackProgressAndState(progressMs)
        }

    private val remoteMediaClientProgressListener =
        RemoteMediaClient.ProgressListener { progressMs, durationMs ->
            val safeProgress = progressMs.coerceAtLeast(0L)
            val safeDuration = durationMs.coerceAtLeast(0L)
            _playerState.update {
                it.copy(currentPosition = safeProgress, duration = safeDuration)
            }
            val playingItemId = remoteItemId()
            if (playingItemId != null) {
                if (safeDuration > 0L) itemDuration[playingItemId] = safeDuration
                if (playingItemId == reportingItemId) reportingPositionMs = safeProgress
            }
        }

    private val castSessionListener =
        object : Cast.Listener() {
            override fun onVolumeChanged() {
                val session = castSession ?: return
                try {
                    val volume = session.volume.toFloat()
                    val isMuted = session.isMute
                    _playerState.update { it.copy(volume = volume, isMuted = isMuted) }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to update volume/mute state")
                }
            }
        }

    init {
        scope.launch {
            sessionManager.connectionState.collect { state ->
                when (state) {
                    CastConnectionState.CONNECTED -> attachSession()
                    CastConnectionState.DISCONNECTED -> clearSession()
                    // A suspended session comes back, or ends: nothing to do until it does.
                    CastConnectionState.CONNECTING -> Unit
                }
            }
        }
    }

    private fun attachSession() {
        val session = castContext?.sessionManager?.currentCastSession ?: return
        detachListeners()

        castSession = session
        try {
            session.addCastListener(castSessionListener)
            _playerState.update {
                it.copy(volume = session.volume.toFloat(), isMuted = session.isMute)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to listen to the Cast session")
        }

        remoteMediaClient = session.remoteMediaClient
        remoteMediaClient?.let { client ->
            try {
                client.registerCallback(remoteMediaClientCallback)
            } catch (e: Exception) {
                Timber.e(e, "Failed to register callback on RemoteMediaClient")
            }
            isSessionRestored = false
            restoreSession()
            updatePlaybackStatus()
        }
    }

    private fun detachListeners() {
        try {
            remoteMediaClient?.unregisterCallback(remoteMediaClientCallback)
            remoteMediaClient?.removeProgressListener(remoteMediaClientProgressListener)
            remoteMediaClient?.removeProgressListener(playbackReportingCallback)
        } catch (e: Exception) {
            Timber.e(e, "Failed to unregister remote media client listeners")
        }
        remoteMediaClient = null

        try {
            castSession?.removeCastListener(castSessionListener)
        } catch (e: Exception) {
            Timber.e(e, "Failed to remove cast listener")
        }
        castSession = null
    }

    private fun clearSession() {
        Timber.d("Clear session")
        cancelJobs()
        stopReporting()
        detachListeners()

        _currentItem.value = null
        _playerState.update { CastPlayerState() }
        isSessionRestored = false
        forgetPlaylist()
    }

    private fun cancelJobs() {
        loadPending = false
        playJob?.cancel()
        audioTrackJob?.cancel()
        queueJob?.cancel()
        restoreJob?.cancel()
    }

    private fun forgetPlaylist() {
        itemCache.clear()
        itemDuration.clear()
        playerItems.clear()
        nextOf.clear()
        previousOf.clear()
    }

    /** The Jellyfin id of the item the receiver has loaded now, from its custom data. */
    private fun remoteItemId(): UUID? =
        remoteMediaClient?.currentItem?.media?.customData?.optString("itemId")?.toUuidOrNull()

    private fun String.toUuidOrNull(): UUID? =
        try {
            UUID.fromString(this)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun CastMediaItem.isTranscoded(): Boolean =
        !playbackInfo?.mediaSources?.firstOrNull()?.transcodingUrl.isNullOrBlank()

    private fun mapPlaybackStatus(client: RemoteMediaClient): CastPlaybackStatus {
        return when (client.playerState) {
            MediaStatus.PLAYER_STATE_BUFFERING,
            MediaStatus.PLAYER_STATE_LOADING -> CastPlaybackStatus.BUFFERING
            MediaStatus.PLAYER_STATE_PLAYING -> CastPlaybackStatus.PLAYING
            MediaStatus.PLAYER_STATE_PAUSED -> CastPlaybackStatus.PAUSED
            MediaStatus.PLAYER_STATE_IDLE -> {
                when (client.idleReason) {
                    MediaStatus.IDLE_REASON_FINISHED -> CastPlaybackStatus.ENDED
                    MediaStatus.IDLE_REASON_ERROR -> CastPlaybackStatus.ERROR
                    else -> CastPlaybackStatus.IDLE
                }
            }
            else -> CastPlaybackStatus.IDLE
        }
    }

    private fun updatePlaybackStatus() {
        val client = remoteMediaClient ?: return
        val playbackStatus = mapPlaybackStatus(client)

        _playerState.update { it.copy(status = playbackStatus) }

        when (playbackStatus) {
            CastPlaybackStatus.ENDED,
            CastPlaybackStatus.ERROR,
            CastPlaybackStatus.IDLE -> {
                // Idle between two loads still has an item; idle with none is the end.
                if (playbackStatus != CastPlaybackStatus.IDLE || remoteItemId() == null) {
                    stopReporting()
                    // A load of ours that the receiver has not answered yet keeps its item.
                    if (!loadPending) _currentItem.value = null
                }
                removeListener(remoteMediaClientProgressListener)
            }
            CastPlaybackStatus.PLAYING -> {
                startReporting()
                try {
                    client.removeProgressListener(remoteMediaClientProgressListener)
                    client.addProgressListener(remoteMediaClientProgressListener, 1000L)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to add progress listener")
                }
            }
            CastPlaybackStatus.PAUSED -> {
                reportPlaybackProgressAndState()
                removeListener(playbackReportingCallback)
                removeListener(remoteMediaClientProgressListener)
            }
            CastPlaybackStatus.BUFFERING -> reportPlaybackProgressAndState()
        }
    }

    private fun removeListener(listener: RemoteMediaClient.ProgressListener) {
        try {
            remoteMediaClient?.removeProgressListener(listener)
        } catch (e: Exception) {
            Timber.e(e, "Failed to remove progress listener")
        }
    }

    /**
     * The item whose reports are running, when it is also what the receiver plays and what
     * [currentItem] holds. During a queue transition those three disagree for a moment, and a
     * report sent then would be about the wrong item.
     */
    private fun reportableItem(): CastMediaItem? {
        val current = _currentItem.value ?: return null
        val itemId = current.item.itemId
        return current.takeIf { itemId == reportingItemId && itemId == remoteItemId() }
    }

    private fun reportPlaybackProgressAndState(positionMs: Long? = null) {
        val client = remoteMediaClient ?: return
        val current = reportableItem() ?: return
        val safePosition = (positionMs ?: client.approximateStreamPosition).coerceAtLeast(0L)
        reportingPositionMs = safePosition
        val isPaused = !client.isPlaying
        val transcoded = current.isTranscoded()

        scope.launch {
            try {
                repository.postPlaybackProgress(
                    itemId = current.item.itemId,
                    positionTicks = safePosition * 10_000L,
                    isPaused = isPaused,
                    transcoded = transcoded,
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to report playback progress for ${current.item.itemId}")
            }
        }
    }

    /**
     * Reports the start of the current item once the receiver plays it. Called when playback
     * starts and again when [currentItem] catches up with the receiver, whichever comes last.
     */
    private fun startReporting() {
        val client = remoteMediaClient ?: return
        val current = _currentItem.value ?: return
        val itemId = current.item.itemId
        if (!client.isPlaying || itemId != remoteItemId()) return

        if (reportingItemId != itemId) {
            stopReporting()
            reportingItemId = itemId
            reportingPositionMs = client.approximateStreamPosition.coerceAtLeast(0L)
            val transcoded = current.isTranscoded()
            scope.launch {
                try {
                    repository.postPlaybackStart(itemId = itemId, transcoded = transcoded)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to report start for $itemId")
                }
            }
        } else {
            reportPlaybackProgressAndState()
        }

        try {
            client.removeProgressListener(playbackReportingCallback)
            client.addProgressListener(playbackReportingCallback, 10_000L)
        } catch (e: Exception) {
            Timber.e(e, "Failed to add playback reporting progress listener")
        }
    }

    private fun stopReporting() {
        val itemId = reportingItemId ?: return
        reportingItemId = null
        removeListener(playbackReportingCallback)

        val positionMs = reportingPositionMs.coerceAtLeast(0L)
        val duration = itemDuration[itemId]?.takeIf { it > 0L }
        val playedPercentage =
            duration?.let { (positionMs.toFloat() / it.toFloat() * 100).toInt().coerceIn(0, 100) }

        scope.launch {
            try {
                repository.postPlaybackStop(
                    itemId = itemId,
                    positionTicks = positionMs * 10_000L,
                    playedPercentage = playedPercentage,
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to report playback stop for $itemId")
            }
        }
    }

    /** [currentItem] now holds the item the receiver plays. */
    private fun onCurrentItemSet(itemId: UUID) {
        manageQueue(itemId)
        startReporting()
    }

    /** Picks up what the receiver is already playing: after a reconnect, or a new process. */
    private fun restoreSession() {
        val client = remoteMediaClient ?: return
        val status = client.mediaStatus ?: return
        if (status.playerState == MediaStatus.PLAYER_STATE_IDLE || isSessionRestored) return

        Timber.d("Restoring session")
        isSessionRestored = true
        restoreRemoteItem()
    }

    /**
     * Rebuilds [currentItem] from the receiver's current queue item: one this process did not
     * build, or no longer knows. The other queue items follow the same way when their turn comes.
     */
    private fun restoreRemoteItem() {
        val client = remoteMediaClient ?: return
        val media = client.currentItem?.media ?: return
        val itemId = media.customData?.optString("itemId")?.toUuidOrNull() ?: return
        val playbackInfo =
            media.customData
                ?.optString("playbackInfo")
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    try {
                        json.decodeFromString<PlaybackInfoResponse>(it)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to decode playbackInfo")
                        null
                    }
                }
        // The audio track is part of the stream the server was asked for; the subtitle is
        // whichever text track the receiver shows.
        val audioIndex =
            media.contentId?.let { url ->
                val uri = Uri.parse(url)
                uri.queryParameterNames
                    .firstOrNull { it.equals("AudioStreamIndex", ignoreCase = true) }
                    ?.let { uri.getQueryParameter(it)?.toIntOrNull() }
            }
        val subtitleTrackId = client.mediaStatus?.activeTrackIds?.firstOrNull()?.toInt()

        restoreJob?.cancel()
        restoreJob =
            scope.launch {
                try {
                    val kind =
                        when (repository.getItem(itemId)) {
                            is FindroidMovie -> BaseItemKind.MOVIE
                            is FindroidEpisode -> BaseItemKind.EPISODE
                            else -> return@launch
                        }
                    val manager = playlistManagers.get()
                    val item = manager.getInitialItem(itemId = itemId, itemKind = kind)
                    // The receiver moved on, the session ended, or the user started something
                    // else, while this was loading.
                    if (item == null || loadPending || remoteItemId() != itemId) return@launch

                    val mediaSource = playbackInfo?.mediaSources?.firstOrNull()
                    if (playbackInfo != null && mediaSource != null) {
                        // getInitialItem asked for the phone's playback info and its play session
                        // replaced the receiver's.
                        repository.onExternalPlaybackInfo(
                            itemId,
                            playbackInfo.playSessionId,
                            transcodes = !mediaSource.transcodingUrl.isNullOrBlank(),
                        )
                    }
                    val tracks = mediaSource?.let { getTracks(it, audioIndex, subtitleTrackId) }
                    val castItem =
                        CastMediaItem(
                            item = item,
                            playbackInfo = playbackInfo,
                            subtitleTracks = tracks?.subtitles.orEmpty(),
                            audioTracks = tracks?.audio.orEmpty(),
                        )

                    playlistManager = manager
                    playerItems.clear()
                    nextOf.clear()
                    previousOf.clear()
                    playerItems[itemId] = item
                    itemCache[itemId] = castItem
                    _currentItem.value = castItem
                    onCurrentItemSet(itemId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to restore item $itemId")
                }
            }
    }

    private suspend fun getPlaybackInfo(
        item: PlayerItem,
        audioStreamIndex: Int?,
    ): PlaybackInfoResponse =
        withContext(ioDispatcher) {
            val profile =
                if (sessionManager.connectedDevice.value?.supportsH265 == true) {
                    ChromecastH265.deviceProfile
                } else {
                    Chromecast.deviceProfile
                }
            val maxBitrateMbps = appPreferences.getValue(appPreferences.castMaxBitrateMbps)

            jellyfinApi.mediaInfoApi
                .getPostedPlaybackInfo(
                    item.itemId,
                    PlaybackInfoDto(
                        userId = jellyfinApi.userId,
                        mediaSourceId = item.mediaSourceId,
                        deviceProfile = profile,
                        maxStreamingBitrate =
                            maxBitrateMbps.takeIf { it > 0 }?.let { it * 1_000_000 },
                        audioStreamIndex = audioStreamIndex,
                        // The receiver plays a file's own default audio track and cannot be told
                        // to play another, so a chosen track has to come out of the server.
                        enableDirectPlay = audioStreamIndex == null,
                        enableDirectStream = true,
                        enableTranscoding = true,
                        allowAudioStreamCopy = true,
                        allowVideoStreamCopy = true,
                    ),
                )
                .content
        }

    private fun Uri.toCastOptimizeImageUri(isEpisodeOrBackdrop: Boolean = false): Uri {
        val (targetWidthPx, targetHeightPx) = if (isEpisodeOrBackdrop) 1280 to 720 else 480 to 720

        return this.buildUpon()
            .appendQueryParameter("fillWidth", targetWidthPx.toString())
            .appendQueryParameter("fillHeight", targetHeightPx.toString())
            .appendQueryParameter("quality", "70")
            .build()
    }

    /**
     * Asks the server how the receiver can play [item] and builds what the receiver loads.
     * [audioStreamIndex] is the audio track the user chose, `null` for the item's default.
     * [subtitleTrackId] is the subtitle track to show; with [defaultSubtitle] it is the one the
     * server picks for this user instead. Throws when the server cannot be asked.
     */
    private suspend fun buildMedia(
        item: PlayerItem,
        audioStreamIndex: Int? = null,
        subtitleTrackId: Int? = null,
        defaultSubtitle: Boolean = true,
    ): BuiltMedia? {
        val baseUrl = jellyfinApi.api.baseUrl?.removeSuffix("/").orEmpty()
        if (baseUrl.isEmpty()) return null

        val playbackInfo = getPlaybackInfo(item, audioStreamIndex)
        val mediaSource = playbackInfo.mediaSources.firstOrNull() ?: return null
        val transcodingUrl = mediaSource.transcodingUrl
        val isTranscoded = !transcodingUrl.isNullOrBlank()
        repository.onExternalPlaybackInfo(item.itemId, playbackInfo.playSessionId, isTranscoded)

        val isEpisode = item.seriesName != null
        val mediaMetadata =
            MediaMetadata(
                    if (isEpisode) MediaMetadata.MEDIA_TYPE_TV_SHOW
                    else MediaMetadata.MEDIA_TYPE_MOVIE
                )
                .apply {
                    putString(MediaMetadata.KEY_TITLE, item.name)
                    item.seriesName?.let { putString(MediaMetadata.KEY_SERIES_TITLE, it) }
                    item.indexNumber?.let { putInt(MediaMetadata.KEY_EPISODE_NUMBER, it) }
                    item.parentIndexNumber?.let { putInt(MediaMetadata.KEY_SEASON_NUMBER, it) }
                    item.artworkUri?.let {
                        addImage(
                            WebImage(it.toCastOptimizeImageUri(isEpisodeOrBackdrop = isEpisode))
                        )
                    }
                }

        val streamUrl =
            if (!transcodingUrl.isNullOrBlank()) {
                baseUrl + "/" + transcodingUrl.removePrefix("/")
            } else {
                Uri.parse("$baseUrl/Videos/${item.itemId}/stream")
                    .buildUpon()
                    .appendQueryParameter("static", "true")
                    .appendQueryParameter("mediaSourceId", mediaSource.id)
                    .apply {
                        jellyfinApi.api.accessToken?.let { appendQueryParameter("api_key", it) }
                    }
                    .build()
                    .toString()
            }
        // Direct play is MP4 only (see the device profiles). The source's own container is no
        // help: the server gives ffprobe's list for it, "mov,mp4,m4a,3gp,3g2,mj2".
        val contentType = if (isTranscoded) "application/x-mpegurl" else "video/mp4"

        val subtitleId =
            if (defaultSubtitle) {
                mediaSource.defaultSubtitleStreamIndex?.takeIf { it >= 0 }?.let {
                    it + SUBTITLE_TRACK_ID_OFFSET
                }
            } else {
                subtitleTrackId
            }
        val tracks = getTracks(mediaSource, audioStreamIndex, subtitleId)

        val customData =
            JSONObject().apply {
                put("itemId", item.itemId.toString())
                put("playbackInfo", json.encodeToString(playbackInfo))
            }
        val mediaInfo =
            MediaInfo.Builder(streamUrl)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(contentType)
                .setMetadata(mediaMetadata)
                .setCustomData(customData)
                .apply { if (tracks.castTracks.isNotEmpty()) setMediaTracks(tracks.castTracks) }
                .build()

        return BuiltMedia(
            mediaInfo = mediaInfo,
            castItem =
                CastMediaItem(
                    item = item,
                    playbackInfo = playbackInfo,
                    subtitleTracks = tracks.subtitles,
                    audioTracks = tracks.audio,
                ),
            activeTrackIds =
                tracks.subtitles
                    .filter { it.selected && it.supported }
                    .map { it.id.toLong() }
                    .toLongArray(),
        )
    }

    /**
     * The tracks of [mediaSource], for the receiver and for the track dialogs. [audioStreamIndex]
     * is the audio stream that plays (`null`: the source's default), [subtitleTrackId] the subtitle
     * track that shows (`null`: none). Subtitle track ids are the stream index plus
     * [SUBTITLE_TRACK_ID_OFFSET]; the receiver gets only those the server delivers as a file.
     */
    private fun getTracks(
        mediaSource: MediaSourceInfo,
        audioStreamIndex: Int?,
        subtitleTrackId: Int?,
    ): Tracks {
        val baseUrl = jellyfinApi.api.baseUrl?.removeSuffix("/").orEmpty()
        val castTracks = mutableListOf<MediaTrack>()
        val subtitles = mutableListOf<Track>()
        val audio = mutableListOf<Track>()

        val streams = mediaSource.mediaStreams.orEmpty()
        val playingAudioIndex =
            audioStreamIndex
                ?: mediaSource.defaultAudioStreamIndex
                ?: streams.firstOrNull { it.type == MediaStreamType.AUDIO }?.index

        streams.forEach { stream ->
            if (stream.type == MediaStreamType.SUBTITLE) {
                val trackId = stream.index + SUBTITLE_TRACK_ID_OFFSET
                val trackUrl = stream.deliveryUrl?.let { receiverUrl(baseUrl, it) }

                if (trackUrl != null) {
                    castTracks.add(
                        MediaTrack.Builder(trackId.toLong(), MediaTrack.TYPE_TEXT)
                            .setContentId(trackUrl)
                            .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                            .setContentType("text/vtt")
                            .setLanguage(stream.language)
                            .setName(stream.title ?: stream.displayTitle)
                            .build()
                    )
                }

                subtitles.add(
                    Track(
                        id = trackId,
                        label = stream.title ?: stream.displayTitle,
                        language = stream.language,
                        codec = stream.codec,
                        selected = trackUrl != null && trackId == subtitleTrackId,
                        supported = trackUrl != null,
                        isExternal = stream.isExternal,
                        isForced = stream.isForced,
                        isHearingImpaired = stream.isHearingImpaired,
                        isDefault = stream.isDefault,
                    )
                )
            } else if (stream.type == MediaStreamType.AUDIO) {
                audio.add(
                    Track(
                        id = stream.index,
                        label = stream.title ?: stream.displayTitle,
                        language = stream.language,
                        codec = stream.codec,
                        selected = stream.index == playingAudioIndex,
                        supported = true,
                        isExternal = stream.isExternal,
                        isForced = stream.isForced,
                        isHearingImpaired = stream.isHearingImpaired,
                        isDefault = stream.isDefault,
                        channelLayout = stream.channelLayout,
                    )
                )
            }
        }

        return Tracks(castTracks, subtitles, audio)
    }

    /**
     * [url] as the receiver can fetch it: a path on the server, or an absolute URL on the server's
     * own host. The receiver sits on the user's network and fetches whatever it is given, so a URL
     * that points anywhere else is not passed on.
     */
    private fun receiverUrl(baseUrl: String, url: String): String? {
        if (url.isEmpty()) return null
        val uri = Uri.parse(url)
        if (uri.scheme == null) return baseUrl + "/" + url.removePrefix("/")
        return url.takeIf { uri.host != null && uri.host == Uri.parse(baseUrl).host }
    }

    private fun fail(e: Exception? = null, message: String) {
        loadPending = false
        Timber.e(e, message)
        _errors.tryEmit(CastError.PLAYBACK_FAILED)
    }

    override fun playItem(
        itemId: UUID,
        itemKind: String,
        startFromBeginning: Boolean,
        mediaSourceId: String?,
    ) {
        cancelJobs()
        stopReporting()
        isSessionRestored = true
        forgetPlaylist()
        val manager = playlistManagers.get()
        playlistManager = manager
        loadPending = true

        playJob =
            scope.launch {
                val client = remoteMediaClient
                if (client == null) {
                    fail(message = "No Cast session to play $itemId on")
                    return@launch
                }

                val item: PlayerItem?
                val built: BuiltMedia?
                try {
                    item =
                        manager.getInitialItem(
                            itemId = itemId,
                            itemKind = BaseItemKind.fromName(itemKind),
                            mediaSourceId = mediaSourceId,
                            startFromBeginning = startFromBeginning,
                        )
                    built = item?.let { buildMedia(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(e, "Failed to prepare $itemId for Cast")
                    return@launch
                }
                if (item == null || built == null) {
                    fail(message = "Nothing to play for $itemId")
                    return@launch
                }

                playerItems[item.itemId] = item
                itemCache[item.itemId] = built.castItem
                _currentItem.value = built.castItem

                val loadRequest =
                    MediaLoadRequestData.Builder()
                        .setMediaInfo(built.mediaInfo)
                        .setAutoplay(true)
                        .setCurrentTime(item.playbackPosition.coerceAtLeast(0L))
                        .setActiveTrackIds(built.activeTrackIds)
                        .build()

                client.load(loadRequest).setResultCallback { result ->
                    if (_currentItem.value === built.castItem) loadPending = false
                    if (result.status.isSuccess) {
                        // onMetadataUpdated skips an item that is current already, as this one
                        // is, so the queue is filled from here.
                        if (_currentItem.value === built.castItem) onCurrentItemSet(item.itemId)
                    } else {
                        if (_currentItem.value === built.castItem) _currentItem.value = null
                        fail(message = "Error loading media: ${result.status.statusMessage}")
                    }
                }
            }
    }

    /**
     * Keeps the playlist's next and previous item in the receiver's queue around [itemId], the
     * current item, so the receiver goes on by itself and the TV remote's skip buttons work.
     */
    private fun manageQueue(itemId: UUID) {
        queueJob?.cancel()
        queueJob =
            scope.launch {
                try {
                    val manager = playlistManager
                    manager.setCurrentMediaItemIndex(itemId)
                    manager.loadQueue()
                    manager.setCurrentMediaItemIndex(itemId)

                    manager.getNextPlayerItem()?.let { next ->
                        playerItems[next.itemId] = next
                        nextOf[itemId] = next.itemId
                        previousOf[next.itemId] = itemId
                    }
                    manager.getPreviousPlayerItem()?.let { previous ->
                        playerItems[previous.itemId] = previous
                        previousOf[itemId] = previous.itemId
                        nextOf[previous.itemId] = itemId
                    }
                    if (_currentItem.value?.item?.itemId != itemId) return@launch

                    val next = nextOf[itemId]?.let { playerItems[it] }
                    val previous = previousOf[itemId]?.let { playerItems[it] }
                    _playerState.update {
                        it.copy(hasNextItem = next != null, hasPreviousItem = previous != null)
                    }

                    next?.let { queueItem(current = itemId, item = it, before = false) }
                    previous?.let { queueItem(current = itemId, item = it, before = true) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to queue the neighbours of $itemId")
                }
            }
    }

    private fun remoteQueueItemIds(status: MediaStatus): Set<UUID> =
        status.queueItems.mapNotNullTo(HashSet()) {
            it.media?.customData?.optString("itemId")?.toUuidOrNull()
        }

    /** Puts [item] in the receiver's queue right [before] or after [current], unless it is in. */
    private suspend fun queueItem(current: UUID, item: PlayerItem, before: Boolean) {
        val queued = remoteMediaClient?.mediaStatus?.let { remoteQueueItemIds(it) } ?: return
        if (item.itemId in queued) return

        val built = buildMedia(item) ?: return

        queueMutex.withLock {
            val client = remoteMediaClient ?: return
            val status = client.mediaStatus ?: return
            if (remoteItemId() != current || item.itemId in remoteQueueItemIds(status)) return
            itemCache[item.itemId] = built.castItem

            val queueItem =
                MediaQueueItem.Builder(built.mediaInfo)
                    .setAutoplay(true)
                    .setPreloadTime(20.0)
                    .setActiveTrackIds(built.activeTrackIds)
                    .build()

            val currentItemId = status.currentItemId
            val insertBefore =
                if (before) {
                    currentItemId
                } else {
                    val queueItems = status.queueItems
                    val currentIndex = queueItems.indexOfFirst { it.itemId == currentItemId }
                    queueItems.getOrNull(currentIndex + 1)?.takeIf { currentIndex != -1 }?.itemId
                        ?: MediaQueueItem.INVALID_ITEM_ID
                }

            try {
                client.queueInsertItems(arrayOf(queueItem), insertBefore, null)
            } catch (e: Exception) {
                Timber.e(e, "Failed to insert queue item")
            }
        }
    }

    override fun play() {
        try {
            remoteMediaClient?.play()
        } catch (e: Exception) {
            Timber.e(e, "Failed to play")
        }
    }

    override fun pause() {
        try {
            remoteMediaClient?.pause()
        } catch (e: Exception) {
            Timber.e(e, "Failed to pause")
        }
    }

    override fun seekTo(position: Long) {
        val safePosition = position.coerceAtLeast(0L)
        _playerState.update { it.copy(currentPosition = safePosition) }
        try {
            val options =
                MediaSeekOptions.Builder()
                    .setPosition(safePosition)
                    .setResumeState(MediaSeekOptions.RESUME_STATE_UNCHANGED)
                    .build()
            remoteMediaClient?.seek(options)
        } catch (e: Exception) {
            Timber.e(e, "Failed to seek to $safePosition")
        }
    }

    override fun seekToNext() {
        try {
            remoteMediaClient?.queueNext(null)
        } catch (e: Exception) {
            Timber.e(e, "Failed to seek to next item")
        }
    }

    override fun seekToPrevious() {
        try {
            remoteMediaClient?.queuePrev(null)
        } catch (e: Exception) {
            Timber.e(e, "Failed to seek to previous item")
        }
    }

    override fun setVolume(volume: Float) {
        try {
            castSession?.volume = volume.toDouble().coerceIn(0.0, 1.0)
        } catch (e: Exception) {
            Timber.e(e, "Failed to set volume on CastSession")
        }
    }

    private fun CastMediaItem.withSubtitle(trackIds: Collection<Long>): CastMediaItem =
        copy(subtitleTracks = subtitleTracks.map { it.copy(selected = it.id.toLong() in trackIds) })

    override fun setSubtitleTrack(track: Track?) {
        val client = remoteMediaClient ?: return
        val current = _currentItem.value ?: return
        val itemId = current.item.itemId
        val before = current.subtitleTracks.filter { it.selected }.map { it.id.toLong() }
        val target = listOfNotNull(track?.id?.toLong())

        fun show(trackIds: Collection<Long>) {
            val item = _currentItem.value?.takeIf { it.item.itemId == itemId } ?: return
            val updated = item.withSubtitle(trackIds)
            itemCache[itemId] = updated
            _currentItem.value = updated
        }

        show(target)
        try {
            client.setActiveMediaTracks(target.toLongArray()).setResultCallback { result ->
                if (!result.status.isSuccess) {
                    Timber.e("Failed to set subtitle track: ${result.status.statusMessage}")
                    show(before)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to set subtitle track")
            show(before)
        }
    }

    override fun setAudioTrack(track: Track?, itemId: UUID?) {
        val current = _currentItem.value ?: return
        if (track == null || (itemId != null && itemId != current.item.itemId)) return
        if (current.audioTracks.firstOrNull { it.selected }?.id == track.id) return
        val currentItemId = current.item.itemId

        _currentItem.value =
            current.copy(
                audioTracks = current.audioTracks.map { it.copy(selected = it.id == track.id) }
            )

        audioTrackJob?.cancel()
        loadPending = true
        audioTrackJob =
            scope.launch {
                fun revert() {
                    loadPending = false
                    if (_currentItem.value?.item?.itemId == currentItemId) {
                        _currentItem.value = current
                    }
                }

                val client = remoteMediaClient
                val built =
                    try {
                        buildMedia(
                            item = current.item,
                            audioStreamIndex = track.id,
                            subtitleTrackId =
                                current.subtitleTracks.firstOrNull { it.selected }?.id,
                            defaultSubtitle = false,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to get the stream for audio track ${track.id}")
                        null
                    }
                if (client == null || built == null || remoteItemId() != currentItemId) {
                    revert()
                    if (built == null) _errors.tryEmit(CastError.PLAYBACK_FAILED)
                    return@launch
                }

                // The stream changes, and with it the play session the reports name.
                stopReporting()
                itemCache[currentItemId] = built.castItem
                _currentItem.value = built.castItem

                // Loading replaces the receiver's queue; manageQueue fills it again once the
                // receiver has the item.
                val loadRequest =
                    MediaLoadRequestData.Builder()
                        .setMediaInfo(built.mediaInfo)
                        .setCurrentTime(client.approximateStreamPosition.coerceAtLeast(0L))
                        .setAutoplay(client.isPlaying || client.isBuffering)
                        .setActiveTrackIds(built.activeTrackIds)
                        .build()

                client.load(loadRequest).setResultCallback { result ->
                    if (_currentItem.value === built.castItem) loadPending = false
                    if (result.status.isSuccess) {
                        onCurrentItemSet(currentItemId)
                    } else {
                        fail(message = "Failed to set audio track: ${result.status.statusMessage}")
                    }
                }
            }
    }

    override fun stop() {
        cancelJobs()
        stopReporting()
        try {
            remoteMediaClient?.stop()
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop remote media client")
        }
        _currentItem.value = null
        forgetPlaylist()
    }

    private companion object {
        /** Keeps subtitle track ids apart from the audio stream indices the dialogs also use. */
        const val SUBTITLE_TRACK_ID_OFFSET = 100
    }
}
