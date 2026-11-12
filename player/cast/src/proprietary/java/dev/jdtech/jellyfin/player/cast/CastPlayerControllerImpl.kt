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
import dev.jdtech.jellyfin.player.cast.devices.Chromecast
import dev.jdtech.jellyfin.player.cast.devices.ChromecastH265
import dev.jdtech.jellyfin.player.cast.models.CastConnectionState
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
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

@Singleton
class CastPlayerControllerImpl
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val jellyfinApi: JellyfinApi,
    private val repository: JellyfinRepository,
    private val sessionManager: CastSessionManager,
    private val playlistManager: PlaylistManager,
    private val appPreferences: AppPreferences,
) : CastPlayerController {

    internal var mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val _currentItem = MutableStateFlow<CastMediaItem?>(null)
    override val currentItem: StateFlow<CastMediaItem?> = _currentItem.asStateFlow()

    private val _playerState = MutableStateFlow(CastPlayerState())
    override val playerState: StateFlow<CastPlayerState> = _playerState.asStateFlow()

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

    @Volatile private var isSessionRestored = false
    @Volatile private var isReporting = false
    @Volatile private var lastActiveItemId: Int = MediaQueueItem.INVALID_ITEM_ID
    @Volatile private var lastActiveItemUuid: UUID? = null

    @Volatile private var maxBitrate: Int? = null
    @Volatile private var isTranscoding: Boolean = false
    @Volatile private var audioStreamIndex: Int? = null
    @Volatile private var subtitleStreamIndex: Int? = null

    private data class BuildMediaResult(
        val mediaInfo: MediaInfo,
        val playbackInfo: PlaybackInfoResponse,
        val subtitleTracks: List<Track>,
        val audioTracks: List<Track>,
        val isTranscoded: Boolean,
    )

    private val itemCache = ConcurrentHashMap<UUID, CastMediaItem>()
    private val itemDuration = ConcurrentHashMap<UUID, Long>()

    private val remoteMediaClientCallback =
        object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() {
                updatePlaybackStatus()

                if (!isSessionRestored && remoteMediaClient?.mediaStatus != null) {
                    restoreSession()
                }
            }

            override fun onMetadataUpdated() {
                super.onMetadataUpdated()
                val client = remoteMediaClient ?: return
                val itemIdStr = client.currentItem?.media?.customData?.optString("itemId") ?: return
                val itemId =
                    try {
                        UUID.fromString(itemIdStr)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse itemId in onMetadataUpdated: $itemIdStr")
                        return
                    }

                val cachedItem = itemCache[itemId]
                if (cachedItem != null) {
                    _currentItem.value = cachedItem
                    itemDuration[itemId] = client.streamDuration
                    manageQueue(itemId)
                } else {
                    val playbackInfoStr =
                        client.currentItem?.media?.customData?.optString("playbackInfo")
                    val playbackInfo =
                        if (!playbackInfoStr.isNullOrEmpty()) {
                            try {
                                json.decodeFromString<PlaybackInfoResponse>(playbackInfoStr)
                            } catch (e: Exception) {
                                Timber.e(e, "Failed to decode playbackInfo in onMetadataUpdated")
                                null
                            }
                        } else null
                    restoreRemoteItem(
                        itemIdStr = itemIdStr,
                        playbackInfo = playbackInfo,
                        isCurrent = true,
                    )
                }
            }

            override fun onQueueStatusUpdated() {
                val client = remoteMediaClient ?: return
                val currentActiveItemId = client.mediaStatus?.currentItemId ?: return

                if (currentActiveItemId != lastActiveItemId) {
                    if (lastActiveItemId != MediaQueueItem.INVALID_ITEM_ID) {
                        val previousItem = client.mediaStatus?.getQueueItemById(lastActiveItemId)
                        val previousItemIdStr = previousItem?.media?.customData?.optString("itemId")
                        val previousUuid =
                            try {
                                previousItemIdStr?.let { UUID.fromString(it) }
                            } catch (_: Exception) {
                                null
                            } ?: lastActiveItemUuid

                        if (previousUuid != null) {
                            stopReporting(previousUuid)
                        }
                        if (currentActiveItemId != MediaQueueItem.INVALID_ITEM_ID) {
                            startReporting()
                        }
                    }

                    lastActiveItemId = currentActiveItemId
                    val currentItem = client.mediaStatus?.getQueueItemById(currentActiveItemId)
                    val currentItemIdStr = currentItem?.media?.customData?.optString("itemId")
                    lastActiveItemUuid =
                        try {
                            currentItemIdStr?.let { UUID.fromString(it) }
                        } catch (_: Exception) {
                            null
                        }
                }
            }

            override fun onMediaError(p0: MediaError) {
                super.onMediaError(p0)
                Timber.e("Media Error: $p0")
                _playerState.update { it.copy(status = CastPlaybackStatus.ERROR) }
            }
        }

    private val playbackReportingCallback = RemoteMediaClient.ProgressListener { progressMs, _ ->
        reportPlaybackProgressAndState(progressMs)
    }

    private val remoteMediaClientProgressListener =
        RemoteMediaClient.ProgressListener { progressMs, durationMs ->
            val safeProgress = progressMs.coerceAtLeast(0L)
            val safeDuration = durationMs.coerceAtLeast(0L)
            _playerState.update {
                it.copy(
                    currentPosition = safeProgress,
                    duration = safeDuration,
                )
            }
            val currentItemId = _currentItem.value?.item?.itemId
            if (currentItemId != null && safeDuration > 0L) {
                itemDuration[currentItemId] = safeDuration
            }
        }

    private val castSessionListener =
        object : Cast.Listener() {
            override fun onVolumeChanged() {
                val session = castSession ?: return
                try {
                    val volume = session.volume.toFloat()
                    val isMuted = session.isMute
                    _playerState.update {
                        it.copy(
                            volume = volume,
                            isMuted = isMuted,
                        )
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to update volume/mute state")
                }
            }
        }

    init {
        scope.launch {
            sessionManager.connectionState.collect { state ->
                if (state == CastConnectionState.CONNECTED) {
                    castContext?.sessionManager?.currentCastSession?.let { session ->
                        castSession = session
                        try {
                            session.addCastListener(castSessionListener)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to add cast listener")
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
                        }

                        try {
                            _playerState.update {
                                it.copy(
                                    volume = session.volume.toFloat(),
                                    isMuted = session.isMute,
                                )
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to read initial volume/mute state")
                        }
                    }
                } else if (state == CastConnectionState.DISCONNECTED) {
                    clearSession()
                }
            }
        }
    }

    private fun clearSession() {
        Timber.d("Clear session")
        stopReporting()

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

        _currentItem.value = null
        _playerState.update { CastPlayerState() }
        isSessionRestored = false
        lastActiveItemId = MediaQueueItem.INVALID_ITEM_ID
        lastActiveItemUuid = null
        itemCache.clear()
        itemDuration.clear()
    }

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
            CastPlaybackStatus.IDLE -> {
                val itemId = _currentItem.value?.item?.itemId
                stopReporting(itemId)
                _currentItem.value = null
                try {
                    remoteMediaClient?.removeProgressListener(remoteMediaClientProgressListener)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to remove progress listener")
                }
            }
            CastPlaybackStatus.PLAYING -> {
                startReporting()
                try {
                    remoteMediaClient?.removeProgressListener(remoteMediaClientProgressListener)
                    remoteMediaClient?.addProgressListener(remoteMediaClientProgressListener, 1000L)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to add progress listener")
                }
            }
            CastPlaybackStatus.PAUSED -> {
                if (isReporting) {
                    reportPlaybackProgressAndState()
                    try {
                        remoteMediaClient?.removeProgressListener(playbackReportingCallback)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to remove playback reporting listener")
                    }
                }
                try {
                    remoteMediaClient?.removeProgressListener(remoteMediaClientProgressListener)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to remove progress listener")
                }
            }
            else -> {
                if (isReporting) {
                    reportPlaybackProgressAndState()
                }
            }
        }
    }

    private fun reportPlaybackProgressAndState(positionMs: Long? = null) {
        val client = remoteMediaClient ?: return
        val currentItem = _currentItem.value ?: return
        val safePosition = (positionMs ?: client.approximateStreamPosition).coerceAtLeast(0L)
        val positionTicks = safePosition * 10_000L

        scope.launch {
            try {
                repository.postPlaybackProgress(
                    itemId = currentItem.item.itemId,
                    positionTicks = positionTicks,
                    isPaused = !client.isPlaying,
                    transcoded = isTranscoding,
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to report playback progress for item: ${currentItem.item.itemId}")
            }
        }
    }

    private fun startReporting() {
        if (!isReporting) {
            val currentItem = _currentItem.value ?: return
            scope.launch {
                try {
                    repository.postPlaybackStart(
                        itemId = currentItem.item.itemId,
                        transcoded = isTranscoding,
                    )
                } catch (e: Exception) {
                    Timber.e(e, "Failed to report start for item: ${currentItem.item.itemId}")
                }
            }
            isReporting = true
        } else {
            reportPlaybackProgressAndState()
        }

        try {
            remoteMediaClient?.removeProgressListener(playbackReportingCallback)
            remoteMediaClient?.addProgressListener(playbackReportingCallback, 10000L)
        } catch (e: Exception) {
            Timber.e(e, "Failed to add playback reporting progress listener")
        }
    }

    private fun stopReporting(itemId: UUID? = null) {
        if (!isReporting) return

        val targetItemId = itemId ?: _currentItem.value?.item?.itemId
        if (targetItemId != null) {
            val playerState = _playerState.value
            val client = remoteMediaClient
            val duration =
                itemDuration[targetItemId]?.takeIf { it > 0L }
                    ?: playerState.duration.takeIf { it > 0L }
                    ?: client?.streamDuration?.takeIf { it > 0L }
                    ?: 0L

            val positionTicks = playerState.currentPosition.coerceAtLeast(0L) * 10_000L
            val playedPercentage =
                if (duration > 0L) {
                    (playerState.currentPosition.toFloat() / duration.toFloat() * 100).toInt().coerceIn(0, 100)
                } else null

            scope.launch {
                try {
                    repository.postPlaybackStop(
                        itemId = targetItemId,
                        positionTicks = positionTicks,
                        playedPercentage = playedPercentage,
                    )
                } catch (e: Exception) {
                    Timber.e(e, "Failed to report playback stop for item: $targetItemId")
                }
            }
        }

        if (itemId != null) {
            itemCache.remove(itemId)
            itemDuration.remove(itemId)
        } else {
            itemCache.clear()
            itemDuration.clear()
        }

        try {
            remoteMediaClient?.removeProgressListener(playbackReportingCallback)
        } catch (e: Exception) {
            Timber.e(e, "Failed to remove playback reporting progress listener")
        }
        isReporting = false
    }

    private fun restoreSession() {
        val client = remoteMediaClient ?: return
        val status = client.mediaStatus ?: return

        if (status.playerState == MediaStatus.PLAYER_STATE_IDLE || isSessionRestored) return

        Timber.d("Restoring Session")
        isSessionRestored = true

        val currentItemId = status.currentItemId
        val currentQueueItem = status.getQueueItemById(currentItemId)
        val streamUrl = currentQueueItem?.media?.contentId

        audioStreamIndex = streamUrl?.let { url ->
            Uri.parse(url).let { uri ->
                uri.queryParameterNames
                    .firstOrNull { it.equals("AudioStreamIndex", ignoreCase = true) }
                    ?.let { uri.getQueryParameter(it)?.toIntOrNull() }
            }
        }
        subtitleStreamIndex = status.activeTrackIds?.firstOrNull()?.toInt()

        status.queueItems.forEach { queueItem ->
            val customData = queueItem.media?.customData ?: return@forEach
            val itemIdStr = customData.optString("itemId") ?: return@forEach
            val playbackInfoStr = customData.optString("playbackInfo")
            val playbackInfo =
                if (!playbackInfoStr.isNullOrEmpty()) {
                    try {
                        json.decodeFromString<PlaybackInfoResponse>(playbackInfoStr)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to decode playbackInfo")
                        null
                    }
                } else null

            val uuid =
                try {
                    UUID.fromString(itemIdStr)
                } catch (_: Exception) {
                    null
                }
            if (uuid != null && !itemCache.containsKey(uuid)) {
                restoreRemoteItem(
                    itemIdStr = itemIdStr,
                    playbackInfo = playbackInfo,
                    isCurrent = (queueItem.itemId == currentItemId),
                )
            }
        }

        updatePlaybackStatus()

        currentQueueItem?.media?.customData?.optString("itemId")?.let { idStr ->
            try {
                val uuid = UUID.fromString(idStr)
                manageQueue(uuid)
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse UUID for queue management: $idStr")
            }
        }
    }

    private fun restoreRemoteItem(
        itemIdStr: String,
        playbackInfo: PlaybackInfoResponse?,
        isCurrent: Boolean = false,
    ) {
        scope.launch {
            try {
                val itemId = UUID.fromString(itemIdStr)
                val item = repository.getMovie(itemId).let { movie ->
                    playlistManager.getInitialItem(
                        itemId = itemId,
                        itemKind = BaseItemKind.MOVIE,
                        mediaSourceId = null,
                        startFromBeginning = false,
                    )
                } ?: run {
                    playlistManager.getInitialItem(
                        itemId = itemId,
                        itemKind = BaseItemKind.EPISODE,
                        mediaSourceId = null,
                        startFromBeginning = false,
                    )
                }

                if (item != null) {
                    val mediaSource = playbackInfo?.mediaSources?.firstOrNull()
                    val (_, subtitles, audio) =
                        if (mediaSource != null) {
                            getTracks(mediaSource)
                        } else {
                            Triple(emptyList(), emptyList(), emptyList())
                        }

                    val castItem =
                        CastMediaItem(
                            item = item,
                            playbackInfo = playbackInfo,
                            subtitleTracks = subtitles,
                            audioTracks = audio,
                        )

                    itemCache[item.itemId] = castItem

                    if (isCurrent) {
                        _currentItem.value = castItem
                        manageQueue(itemId)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to restore item $itemIdStr")
            }
        }
    }

    private suspend fun getPlaybackInfo(
        item: PlayerItem,
        audioStreamIndex: Int?,
    ): PlaybackInfoResponse? =
        withContext(ioDispatcher) {
            val userId = jellyfinApi.userId
            val connectedDevice = sessionManager.connectedDevice.value
            val profile =
                if (connectedDevice?.supportsH265 == true) {
                    ChromecastH265.deviceProfile
                } else {
                    Chromecast.deviceProfile
                }

            val settingBitrateKbps = appPreferences.getValue(appPreferences.castMaxBitrateKbps)
            val maxStreamingBitrate =
                if (settingBitrateKbps > 0) settingBitrateKbps * 1_000 else null

            try {
                jellyfinApi.mediaInfoApi
                    .getPostedPlaybackInfo(
                        item.itemId,
                        PlaybackInfoDto(
                            userId = userId,
                            mediaSourceId = item.mediaSourceId,
                            deviceProfile = profile,
                            maxStreamingBitrate = maxStreamingBitrate,
                            audioStreamIndex = audioStreamIndex,
                            enableDirectPlay = audioStreamIndex == null,
                            enableDirectStream = true,
                            enableTranscoding = true,
                            allowAudioStreamCopy = true,
                            allowVideoStreamCopy = true,
                        ),
                    )
                    .content
            } catch (e: Exception) {
                Timber.e(e, "Failed to get playback info")
                null
            }
        }

    private fun Uri.toCastOptimizeImageUri(isEpisodeOrBackdrop: Boolean = false): Uri {
        val (targetWidthPx, targetHeightPx) =
            if (isEpisodeOrBackdrop) 1280 to 720 else 480 to 720

        return this.buildUpon()
            .appendQueryParameter("fillWidth", targetWidthPx.toString())
            .appendQueryParameter("fillHeight", targetHeightPx.toString())
            .appendQueryParameter("quality", "70")
            .build()
    }

    private suspend fun buildMediaInfo(item: PlayerItem): BuildMediaResult? =
        withContext(ioDispatcher) {
            val baseUrl = jellyfinApi.api.baseUrl?.removeSuffix("/").orEmpty()
            if (baseUrl.isEmpty()) return@withContext null

            val isEpisode = item.seriesName != null
            val mediaType =
                if (isEpisode) MediaMetadata.MEDIA_TYPE_TV_SHOW
                else MediaMetadata.MEDIA_TYPE_MOVIE

            val mediaMetadata =
                MediaMetadata(mediaType).apply {
                    putString(MediaMetadata.KEY_TITLE, item.name)
                    item.seriesName?.let { putString(MediaMetadata.KEY_SERIES_TITLE, it) }
                    item.indexNumber?.let { putInt(MediaMetadata.KEY_EPISODE_NUMBER, it) }
                    item.parentIndexNumber?.let { putInt(MediaMetadata.KEY_SEASON_NUMBER, it) }

                    item.artworkUri?.let {
                        addImage(WebImage(it.toCastOptimizeImageUri(isEpisodeOrBackdrop = isEpisode)))
                    }
                }

            val playbackInfo = getPlaybackInfo(item, audioStreamIndex) ?: return@withContext null
            val customData =
                JSONObject().apply {
                    put("itemId", item.itemId.toString())
                    put("playbackInfo", json.encodeToString(playbackInfo))
                }

            val mediaSource = playbackInfo.mediaSources.firstOrNull() ?: return@withContext null
            val transcodingUrl = mediaSource.transcodingUrl

            val streamUrl =
                if (!transcodingUrl.isNullOrBlank()) {
                    val path = if (transcodingUrl.startsWith("/")) transcodingUrl else "/$transcodingUrl"
                    "$baseUrl$path"
                } else {
                    "$baseUrl/Videos/${item.itemId}/stream?static=true&MediaSourceId=${mediaSource.id}"
                }

            val isTranscoded = !transcodingUrl.isNullOrBlank() || !mediaSource.supportsDirectPlay
            val contentType =
                if (mediaSource.supportsDirectPlay && transcodingUrl.isNullOrBlank()) {
                    mediaSource.container?.let { if (it.contains("/")) it else "video/$it" } ?: "video/mp4"
                } else {
                    "application/x-mpegurl"
                }

            val mediaInfoBuilder =
                MediaInfo.Builder(streamUrl)
                    .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                    .setContentType(contentType)
                    .setMetadata(mediaMetadata)
                    .setCustomData(customData)

            val (castTracks, subtitles, audio) = getTracks(mediaSource)
            if (castTracks.isNotEmpty()) {
                mediaInfoBuilder.setMediaTracks(castTracks)
            }

            BuildMediaResult(
                mediaInfo = mediaInfoBuilder.build(),
                playbackInfo = playbackInfo,
                subtitleTracks = subtitles,
                audioTracks = audio,
                isTranscoded = isTranscoded,
            )
        }

    private fun getTracks(
        mediaSource: MediaSourceInfo
    ): Triple<List<MediaTrack>, List<Track>, List<Track>> {
        val baseUrl = jellyfinApi.api.baseUrl?.removeSuffix("/").orEmpty()
        val castTracks = mutableListOf<MediaTrack>()
        val subtitles = mutableListOf<Track>()
        val audio = mutableListOf<Track>()

        mediaSource.mediaStreams?.forEach { stream ->
            if (stream.type == MediaStreamType.SUBTITLE) {
                val trackId = stream.index + 100
                val deliveryUrl = stream.deliveryUrl

                if (subtitleStreamIndex == null && stream.isDefault) {
                    subtitleStreamIndex = trackId
                }

                if (!deliveryUrl.isNullOrEmpty()) {
                    val trackUrl =
                        if (deliveryUrl.startsWith("http://") || deliveryUrl.startsWith("https://")) {
                            deliveryUrl
                        } else {
                            val path = if (deliveryUrl.startsWith("/")) deliveryUrl else "/$deliveryUrl"
                            "$baseUrl$path"
                        }

                    val builder =
                        MediaTrack.Builder(trackId.toLong(), MediaTrack.TYPE_TEXT)
                            .setContentId(trackUrl)
                            .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                            .setContentType("text/vtt")
                            .setLanguage(stream.language)
                            .setName(stream.title ?: stream.displayTitle ?: "Track ${stream.index}")

                    castTracks.add(builder.build())
                }

                val track =
                    Track(
                        id = trackId,
                        label = stream.title ?: stream.displayTitle,
                        language = stream.language,
                        codec = stream.codec,
                        selected =
                            if (subtitleStreamIndex != null) trackId == subtitleStreamIndex
                            else stream.isDefault,
                        supported = !deliveryUrl.isNullOrEmpty(),
                        isExternal = stream.isExternal,
                        isForced = stream.isForced,
                        isHearingImpaired = stream.isHearingImpaired,
                        isDefault = stream.isDefault,
                    )
                subtitles.add(track)
            } else if (stream.type == MediaStreamType.AUDIO) {
                if (audioStreamIndex == null && stream.isDefault) {
                    audioStreamIndex = stream.index
                }

                val track =
                    Track(
                        id = stream.index,
                        label = stream.title ?: stream.displayTitle,
                        language = stream.language,
                        codec = stream.codec,
                        selected = audioStreamIndex != null && stream.index == audioStreamIndex,
                        supported = true,
                        isExternal = stream.isExternal,
                        isForced = stream.isForced,
                        isHearingImpaired = stream.isHearingImpaired,
                        isDefault = stream.isDefault,
                        channelLayout = stream.channelLayout,
                    )
                audio.add(track)
            }
        }

        if (audioStreamIndex == null && audio.isNotEmpty()) {
            audioStreamIndex = audio.first().id
            audio[0] = audio[0].copy(selected = true)
        }

        return Triple(castTracks, subtitles, audio)
    }

    override fun playItem(itemId: UUID, itemKind: String, startFromBeginning: Boolean) {
        stopReporting()

        audioStreamIndex = null
        subtitleStreamIndex = null
        isSessionRestored = true
        isReporting = false
        itemCache.clear()
        itemDuration.clear()

        playJob?.cancel()
        playJob = scope.launch {
            val initialItem =
                playlistManager.getInitialItem(
                    itemId = itemId,
                    itemKind = BaseItemKind.fromName(itemKind),
                    mediaSourceId = null,
                    startFromBeginning = startFromBeginning,
                )

            if (initialItem != null) {
                val client = remoteMediaClient ?: return@launch
                val result = buildMediaInfo(initialItem) ?: return@launch
                isTranscoding = result.isTranscoded

                val castItem =
                    CastMediaItem(
                        item = initialItem,
                        playbackInfo = result.playbackInfo,
                        subtitleTracks = result.subtitleTracks,
                        audioTracks = result.audioTracks,
                    )

                val startPositionMs = initialItem.playbackPosition.coerceAtLeast(0L)
                val loadRequest =
                    MediaLoadRequestData.Builder()
                        .setMediaInfo(result.mediaInfo)
                        .setAutoplay(true)
                        .setCurrentTime(startPositionMs)
                        .build()

                itemCache[initialItem.itemId] = castItem
                _currentItem.value = castItem

                client.load(loadRequest).setResultCallback { callbackResult ->
                    if (callbackResult.status.isSuccess) {
                        subtitleStreamIndex?.let { subId ->
                            client.setActiveMediaTracks(longArrayOf(subId.toLong()))
                        }
                    } else {
                        Timber.e("Error loading media: ${callbackResult.status.statusMessage}")
                        _currentItem.value = null
                    }
                }
            }
        }
    }

    private fun manageQueue(itemId: UUID) {
        queueJob?.cancel()
        queueJob = scope.launch {
            val status = remoteMediaClient?.mediaStatus ?: return@launch
            val queueItems = status.queueItems

            playlistManager.setCurrentMediaItemIndex(itemId)
            playlistManager.loadQueue()

            val nextItem = playlistManager.getNextPlayerItem()
            val prevItem = playlistManager.getPreviousPlayerItem()

            val queuedItemIds =
                queueItems
                    .mapNotNull { it.media?.customData?.optString("itemId")?.takeIf { id -> id.isNotEmpty() } }
                    .toSet()

            nextItem?.let { next ->
                if (next.itemId.toString() !in queuedItemIds) {
                    queueNextItem(next)
                }
            }

            prevItem?.let { prev ->
                if (prev.itemId.toString() !in queuedItemIds) {
                    queuePreviousItem(prev)
                }
            }

            _playerState.update {
                it.copy(
                    hasNextItem = nextItem != null,
                    hasPreviousItem = prevItem != null,
                )
            }
        }
    }

    private suspend fun queueNextItem(item: PlayerItem) {
        val result = buildMediaInfo(item) ?: return
        itemCache[item.itemId] =
            CastMediaItem(
                item = item,
                playbackInfo = result.playbackInfo,
                subtitleTracks = result.subtitleTracks,
                audioTracks = result.audioTracks,
            )

        queueMutex.withLock {
            val client = remoteMediaClient ?: return@withLock
            val status = client.mediaStatus ?: return@withLock

            val queueItem =
                MediaQueueItem.Builder(result.mediaInfo)
                    .setAutoplay(true)
                    .setPreloadTime(20.0)
                    .build()

            val currentItemId = status.currentItemId
            val queueItems = status.queueItems
            val currentIndex = queueItems.indexOfFirst { it.itemId == currentItemId }

            val nextItemId =
                if (currentIndex != -1 && currentIndex < queueItems.size - 1) {
                    queueItems[currentIndex + 1].itemId
                } else {
                    MediaQueueItem.INVALID_ITEM_ID
                }

            try {
                client.queueInsertItems(arrayOf(queueItem), nextItemId, null)
            } catch (e: Exception) {
                Timber.e(e, "Failed to insert next queue item")
            }
        }
    }

    private suspend fun queuePreviousItem(item: PlayerItem) {
        val result = buildMediaInfo(item) ?: return
        itemCache[item.itemId] =
            CastMediaItem(
                item = item,
                playbackInfo = result.playbackInfo,
                subtitleTracks = result.subtitleTracks,
                audioTracks = result.audioTracks,
            )

        queueMutex.withLock {
            val client = remoteMediaClient ?: return@withLock
            val status = client.mediaStatus ?: return@withLock

            val queueItem =
                MediaQueueItem.Builder(result.mediaInfo)
                    .setAutoplay(true)
                    .setPreloadTime(20.0)
                    .build()

            val currentItemId = status.currentItemId
            try {
                client.queueInsertItems(arrayOf(queueItem), currentItemId, null)
            } catch (e: Exception) {
                Timber.e(e, "Failed to insert previous queue item")
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
        val activeIds = client.mediaStatus?.activeTrackIds?.toMutableList() ?: mutableListOf()

        activeIds.remove(subtitleStreamIndex?.toLong())
        if (track != null) activeIds.add(track.id.toLong())
        subtitleStreamIndex = track?.id

        _currentItem.update { item ->
            val subtitleTracks =
                item?.subtitleTracks?.map { it.copy(selected = track != null && it.id == track.id) }
                    ?: emptyList()
            item?.copy(subtitleTracks = subtitleTracks)
        }

        val targetIds = activeIds.distinct().toLongArray()
        client.setActiveMediaTracks(targetIds).setResultCallback { result ->
            if (result.status.isSuccess) {
                val activeTrackIds = client.mediaStatus?.activeTrackIds?.toList() ?: emptyList()
                _currentItem.update { item ->
                    val subtitleTracks =
                        item?.subtitleTracks?.map {
                            it.copy(selected = activeTrackIds.contains(it.id.toLong()))
                        } ?: emptyList()
                    item?.copy(subtitleTracks = subtitleTracks)
                }
            } else {
                Timber.e("Failed to set subtitle track: ${result.status.statusMessage}")
            }
        }
    }

    override fun setAudioTrack(track: Track?, itemId: UUID?) {
        if (itemId == null || track == null) return
        if (audioStreamIndex == track.id) return
        audioStreamIndex = track.id

        _currentItem.update { item ->
            val audioTracks =
                item?.audioTracks?.map { it.copy(selected = it.id == track.id) } ?: emptyList()
            item?.copy(audioTracks = audioTracks)
        }

        audioTrackJob?.cancel()
        audioTrackJob = scope.launch {
            val client = remoteMediaClient ?: return@launch
            val cachedMedia =
                itemCache[itemId]
                    ?: _currentItem.value?.takeIf { it.item.itemId == itemId }
                    ?: return@launch
            val currentPositionMs = client.approximateStreamPosition.coerceAtLeast(0L)

            val result = buildMediaInfo(cachedMedia.item) ?: return@launch
            isTranscoding = result.isTranscoded

            val loadRequest =
                MediaLoadRequestData.Builder()
                    .setMediaInfo(result.mediaInfo)
                    .setCurrentTime(currentPositionMs)
                    .setAutoplay(true)
                    .build()

            client.load(loadRequest).setResultCallback { callbackResult ->
                if (callbackResult.status.isSuccess) {
                    itemCache.clear()
                    val updatedMedia =
                        cachedMedia.copy(
                            playbackInfo = result.playbackInfo,
                            audioTracks = result.audioTracks,
                            subtitleTracks = result.subtitleTracks,
                        )
                    itemCache[itemId] = updatedMedia
                    _currentItem.value = updatedMedia

                    subtitleStreamIndex?.let { subId ->
                        client.setActiveMediaTracks(longArrayOf(subId.toLong()))
                    }
                } else {
                    Timber.e("Failed to set audio track: ${callbackResult.status.statusMessage}")
                }
            }
        }
    }

    override fun stop() {
        try {
            remoteMediaClient?.stop()
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop remote media client")
        } finally {
            clearSession()
        }
    }
}
