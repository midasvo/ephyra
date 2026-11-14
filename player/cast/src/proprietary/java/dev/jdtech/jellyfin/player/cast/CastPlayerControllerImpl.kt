package dev.jdtech.jellyfin.player.cast

import android.content.Context
import android.net.Uri
import android.os.SystemClock
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
     * A load of ours is on its way to the receiver. Until the receiver answers, it still shows what
     * it played before, and that must not be taken for the current item again.
     */
    private val loadPending: Boolean
        get() = pendingLoad != null

    /**
     * Which load that is. Each one gets a number, and only the answer to the last one counts: an
     * answer to a load that another one has replaced since changes nothing.
     */
    private var pendingLoad: Int? = null
    private var loadCount = 0

    private fun beginLoad(): Int = (++loadCount).also { pendingLoad = it }

    /** Decides which reports the server gets; [send] sends them. */
    private val reporter = CastPlaybackReporter(clock = { SystemClock.elapsedRealtime() })

    /** [playbackReportingCallback] is on the client: the interval of the progress reports runs. */
    private var intervalRunning = false

    /**
     * One per thing the user started (or per restored session). A [PlaylistManager] remembers every
     * item it handed out and does not hand it out again, so one that outlived its playlist would
     * leave the next playlist's queue empty.
     */
    private var playlistManager: PlaylistManager = playlistManagers.get()

    /** What was built for each item in the receiver's queue. */
    private val itemCache = ConcurrentHashMap<UUID, CastMediaItem>()

    /** The playlist's order, as far as [playlistManager] has told it. */
    private val playlist = CastPlaylistOrder()

    private class BuiltMedia(
        val mediaInfo: MediaInfo,
        val castItem: CastMediaItem,
        val activeTrackIds: LongArray,
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
                    reporter.durationChanged(itemId, client.streamDuration)
                    return
                }

                val cachedItem = itemCache[itemId]
                if (cachedItem != null) {
                    _currentItem.value = cachedItem
                    reporter.durationChanged(itemId, client.streamDuration)
                    onCurrentItemSet(itemId)
                } else {
                    restoreRemoteItem()
                }
            }

            override fun onQueueStatusUpdated() {
                // The receiver moved on: the item that was playing is done, wherever the
                // reports for the new one stand.
                send(reporter.queueChanged(remoteItemId()))
            }

            override fun onMediaError(error: MediaError) {
                Timber.e("Media error: ${error.reason} (${error.detailedErrorCode})")
                _playerState.update { it.copy(status = CastPlaybackStatus.ERROR) }
                _errors.tryEmit(CastError.PLAYBACK_FAILED)
            }
        }

    private val playbackReportingCallback = RemoteMediaClient.ProgressListener { progressMs, _ ->
        remoteMediaClient?.let { send(reporter.tick(receiverState(it, progressMs))) }
    }

    private val remoteMediaClientProgressListener =
        RemoteMediaClient.ProgressListener { progressMs, durationMs ->
            val safeProgress = progressMs.coerceAtLeast(0L)
            val safeDuration = durationMs.coerceAtLeast(0L)
            _playerState.update { it.copy(currentPosition = safeProgress, duration = safeDuration) }
            remoteItemId()?.let { reporter.positionChanged(it, safeProgress, safeDuration) }
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
        intervalRunning = false

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
        pendingLoad = null
        playJob?.cancel()
        audioTrackJob?.cancel()
        queueJob?.cancel()
        restoreJob?.cancel()
    }

    private fun forgetPlaylist() {
        itemCache.clear()
        reporter.forget()
        playlist.clear()
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

    /** What [reporter] is told: the receiver's state next to [currentItem]. */
    private fun receiverState(
        client: RemoteMediaClient,
        positionMs: Long = client.approximateStreamPosition,
    ) =
        CastReceiverState(
            status = mapPlaybackStatus(client),
            receiverItemId = remoteItemId(),
            currentItemId = _currentItem.value?.item?.itemId,
            positionMs = positionMs,
            loadPending = loadPending,
        )

    private fun updatePlaybackStatus() {
        val client = remoteMediaClient ?: return
        val playbackStatus = mapPlaybackStatus(client)

        _playerState.update { it.copy(status = playbackStatus) }
        send(reporter.statusChanged(receiverState(client)))

        when (playbackStatus) {
            CastPlaybackStatus.ENDED,
            CastPlaybackStatus.ERROR,
            CastPlaybackStatus.IDLE -> {
                // Idle between two loads still has an item; idle with none is the end.
                if (playbackStatus != CastPlaybackStatus.IDLE || remoteItemId() == null) {
                    // A load of ours that the receiver has not answered yet keeps its item.
                    if (!loadPending) _currentItem.value = null
                }
                removeListener(remoteMediaClientProgressListener)
            }
            CastPlaybackStatus.PLAYING -> {
                try {
                    client.removeProgressListener(remoteMediaClientProgressListener)
                    client.addProgressListener(remoteMediaClientProgressListener, 1000L)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to add progress listener")
                }
            }
            CastPlaybackStatus.PAUSED -> {
                stopInterval()
                removeListener(remoteMediaClientProgressListener)
            }
            CastPlaybackStatus.BUFFERING -> Unit
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
     * Sends what [reporter] decided, and keeps the interval of the progress reports running while
     * an item plays. It is not started again while it runs: that would push the next report back
     * with every status update.
     */
    private fun send(reports: List<CastReport>) {
        reports.forEach { report ->
            when (report) {
                is CastReport.Start -> sendStart(report)
                is CastReport.Progress -> sendProgress(report)
                is CastReport.Stop -> sendStop(report)
            }
        }

        val client = remoteMediaClient
        if (!reporter.isReporting || client == null) {
            stopInterval()
        } else if (!intervalRunning && client.isPlaying) {
            try {
                client.removeProgressListener(playbackReportingCallback)
                client.addProgressListener(
                    playbackReportingCallback,
                    CastPlaybackReporter.PROGRESS_INTERVAL_MS,
                )
                intervalRunning = true
            } catch (e: Exception) {
                Timber.e(e, "Failed to add playback reporting progress listener")
            }
        }
    }

    private fun stopInterval() {
        if (!intervalRunning) return
        intervalRunning = false
        removeListener(playbackReportingCallback)
    }

    /** Whether the stream of [itemId] is a transcode, which the reports name. */
    private fun isTranscoded(itemId: UUID): Boolean =
        (_currentItem.value?.takeIf { it.item.itemId == itemId } ?: itemCache[itemId])
            ?.isTranscoded() == true

    private fun sendStart(report: CastReport.Start) {
        val itemId = report.itemId
        val transcoded = isTranscoded(itemId)
        scope.launch {
            try {
                repository.postPlaybackStart(itemId = itemId, transcoded = transcoded)
            } catch (e: Exception) {
                Timber.e(e, "Failed to report start for $itemId")
            }
        }
    }

    private fun sendProgress(report: CastReport.Progress) {
        val transcoded = isTranscoded(report.itemId)
        scope.launch {
            try {
                repository.postPlaybackProgress(
                    itemId = report.itemId,
                    positionTicks = report.positionMs * 10_000L,
                    isPaused = report.isPaused,
                    transcoded = transcoded,
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to report playback progress for ${report.itemId}")
            }
        }
    }

    private fun sendStop(report: CastReport.Stop) {
        scope.launch {
            try {
                repository.postPlaybackStop(
                    itemId = report.itemId,
                    positionTicks = report.positionMs * 10_000L,
                    playedPercentage = report.playedPercentage,
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to report playback stop for ${report.itemId}")
            }
        }
    }

    /** Ends the reports of the item that has them running: its stop goes out. */
    private fun stopReporting() = send(reporter.stop())

    /** [currentItem] now holds the item the receiver plays. */
    private fun onCurrentItemSet(itemId: UUID) {
        manageQueue(itemId)
        remoteMediaClient?.let { send(reporter.currentItemChanged(receiverState(it))) }
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
        val audioIndex = media.contentId?.let { audioStreamIndexOf(it) }
        val subtitleTrackId = client.mediaStatus?.activeTrackIds?.firstOrNull()?.toInt()

        restoreJob?.cancel()
        restoreJob = scope.launch {
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
                playlist.restart(item)
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
            // A model known to play HEVC, or the user saying theirs does: the receiver itself
            // tells a sender nothing about its codecs.
            val hevc =
                sessionManager.connectedDevice.value?.supportsH265 == true ||
                    appPreferences.getValue(appPreferences.castReceiverHevc)
            val profile =
                if (hevc) {
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
            castStreamUrl(
                baseUrl = baseUrl,
                itemId = item.itemId,
                mediaSourceId = mediaSource.id,
                transcodingUrl = transcodingUrl,
                accessToken = jellyfinApi.api.accessToken,
            )

        val subtitleId =
            if (defaultSubtitle) defaultSubtitleTrackId(mediaSource) else subtitleTrackId
        val tracks = getTracks(mediaSource, audioStreamIndex, subtitleId)
        val mediaTracks =
            tracks.receiverTracks.map {
                MediaTrack.Builder(it.id, MediaTrack.TYPE_TEXT)
                    .setContentId(it.url)
                    .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                    .setContentType("text/vtt")
                    .setLanguage(it.language)
                    .setName(it.name)
                    .build()
            }

        val customData =
            JSONObject().apply {
                put("itemId", item.itemId.toString())
                put("playbackInfo", json.encodeToString(playbackInfo))
            }
        val mediaInfo =
            MediaInfo.Builder(streamUrl)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(castContentType(isTranscoded))
                .setMetadata(mediaMetadata)
                .setCustomData(customData)
                .apply { if (mediaTracks.isNotEmpty()) setMediaTracks(mediaTracks) }
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
            activeTrackIds = tracks.activeTrackIds.toLongArray(),
        )
    }

    private fun getTracks(
        mediaSource: MediaSourceInfo,
        audioStreamIndex: Int?,
        subtitleTrackId: Int?,
    ): CastTracks =
        castTracks(
            mediaSource = mediaSource,
            baseUrl = jellyfinApi.api.baseUrl?.removeSuffix("/").orEmpty(),
            audioStreamIndex = audioStreamIndex,
            subtitleTrackId = subtitleTrackId,
        )

    private fun fail(e: Exception? = null, message: String) {
        pendingLoad = null
        Timber.e(e, message)
        _errors.tryEmit(CastError.PLAYBACK_FAILED)
    }

    override fun playItem(
        itemId: UUID,
        itemKind: String,
        startFromBeginning: Boolean,
        mediaSourceId: String?,
        shuffle: Boolean,
    ) {
        cancelJobs()
        stopReporting()
        isSessionRestored = true
        forgetPlaylist()
        val manager = playlistManagers.get()
        playlistManager = manager
        val load = beginLoad()

        playJob = scope.launch {
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
                        shuffle = shuffle,
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

            playlist.add(item)
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
                if (pendingLoad != load) return@setResultCallback
                pendingLoad = null
                val stillCurrent = _currentItem.value?.item?.itemId == item.itemId
                if (result.status.isSuccess) {
                    // onMetadataUpdated skips an item that is current already, as this one
                    // is, so the queue is filled from here.
                    if (stillCurrent) onCurrentItemSet(item.itemId)
                } else {
                    if (stillCurrent) _currentItem.value = null
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
        queueJob = scope.launch {
            try {
                val manager = playlistManager
                manager.setCurrentMediaItemIndex(itemId)
                manager.loadQueue()
                manager.setCurrentMediaItemIndex(itemId)

                val nextItem = manager.getNextPlayerItem()
                val previousItem = manager.getPreviousPlayerItem()
                // The order of another playlist is of no use to the one that replaced it.
                if (playlistManager !== manager) return@launch
                playlist.setNeighbours(itemId, next = nextItem, previous = previousItem)
                if (_currentItem.value?.item?.itemId != itemId) return@launch

                val next = playlist.next(itemId)
                val previous = playlist.previous(itemId)
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

    private fun queueInsert(status: MediaStatus, current: UUID, item: PlayerItem, before: Boolean) =
        queueInsertPosition(
            queue =
                status.queueItems.map {
                    ReceiverQueueItem(
                        queueItemId = it.itemId,
                        itemId = it.media?.customData?.optString("itemId")?.toUuidOrNull(),
                    )
                },
            currentQueueItemId = status.currentItemId,
            current = current,
            itemId = item.itemId,
            before = before,
        )

    /** Puts [item] in the receiver's queue right [before] or after [current], unless it is in. */
    private suspend fun queueItem(current: UUID, item: PlayerItem, before: Boolean) {
        val statusBefore = remoteMediaClient?.mediaStatus ?: return
        if (queueInsert(statusBefore, current, item, before) == QueueInsert.Skip) return

        val built = buildMedia(item) ?: return

        queueMutex.withLock {
            val client = remoteMediaClient ?: return
            val status = client.mediaStatus ?: return
            // Asked again: building the item took a request to the server.
            val insertBefore =
                when (val insert = queueInsert(status, current, item, before)) {
                    QueueInsert.Skip -> return
                    is QueueInsert.Before -> insert.queueItemId
                    QueueInsert.AtEnd -> MediaQueueItem.INVALID_ITEM_ID
                }
            itemCache[item.itemId] = built.castItem

            val queueItem =
                MediaQueueItem.Builder(built.mediaInfo)
                    .setAutoplay(true)
                    .setPreloadTime(20.0)
                    .setActiveTrackIds(built.activeTrackIds)
                    .build()

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

        _currentItem.value = current.withAudio(track.id)

        audioTrackJob?.cancel()
        val load = beginLoad()
        audioTrackJob = scope.launch {
            fun revert() {
                if (pendingLoad == load) pendingLoad = null
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
                        subtitleTrackId = current.subtitleTracks.firstOrNull { it.selected }?.id,
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
                if (pendingLoad != load) return@setResultCallback
                pendingLoad = null
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
}
