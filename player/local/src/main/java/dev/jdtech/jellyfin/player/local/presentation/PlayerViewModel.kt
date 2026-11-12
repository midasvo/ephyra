package dev.jdtech.jellyfin.player.local.presentation

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.view.Window
import dev.jdtech.jellyfin.player.local.domain.DisplayRefreshRateManager
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidSegment
import dev.jdtech.jellyfin.models.FindroidSegmentType
import dev.jdtech.jellyfin.models.MediaSegmentAction
import dev.jdtech.jellyfin.models.SeriesTrackPreferenceDto
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.core.domain.models.ServerAudioTrack
import dev.jdtech.jellyfin.player.core.domain.models.Trickplay
import dev.jdtech.jellyfin.player.local.PlaybackService
import dev.jdtech.jellyfin.player.local.PlaybackSessionStore
import dev.jdtech.jellyfin.player.local.R
import dev.jdtech.jellyfin.player.local.domain.AudioDecoderFallback
import dev.jdtech.jellyfin.player.local.domain.DebugSegments
import dev.jdtech.jellyfin.player.local.domain.NetworkRecovery
import dev.jdtech.jellyfin.player.local.domain.PlaybackStartGate
import dev.jdtech.jellyfin.player.local.domain.PlaylistManager
import dev.jdtech.jellyfin.player.local.domain.SegmentDecision
import dev.jdtech.jellyfin.player.local.domain.SegmentTracker
import dev.jdtech.jellyfin.player.local.domain.SkipTarget
import dev.jdtech.jellyfin.player.local.domain.SleepTimer
import dev.jdtech.jellyfin.player.local.domain.SleepTimerOption
import dev.jdtech.jellyfin.player.local.domain.StoredChoice
import dev.jdtech.jellyfin.player.local.domain.StillWatchingTracker
import dev.jdtech.jellyfin.player.local.domain.SessionTrackPicks
import dev.jdtech.jellyfin.player.local.domain.TrackCandidate
import dev.jdtech.jellyfin.player.local.domain.TrackChoices
import dev.jdtech.jellyfin.player.local.domain.TrackPick
import dev.jdtech.jellyfin.player.local.domain.TrickplayLoader
import dev.jdtech.jellyfin.player.local.domain.TrickplayRegionLoader
import dev.jdtech.jellyfin.player.local.domain.TrickplayTiles
import dev.jdtech.jellyfin.player.local.domain.TV_TRICKPLAY_WIDTH_DP
import dev.jdtech.jellyfin.player.local.domain.UpNextCountdown
import dev.jdtech.jellyfin.player.local.domain.UpNextDecision
import dev.jdtech.jellyfin.player.local.domain.autoPlayNextFollowsServer
import dev.jdtech.jellyfin.player.local.domain.buildMediaSourceFactory
import dev.jdtech.jellyfin.player.local.domain.getTrackNames
import dev.jdtech.jellyfin.player.local.domain.isNewEndOfItem
import dev.jdtech.jellyfin.player.local.domain.matchSeriesTrack
import dev.jdtech.jellyfin.player.local.domain.playbackStopReport
import dev.jdtech.jellyfin.player.local.domain.playerTitle
import dev.jdtech.jellyfin.player.local.domain.resolveAutoPlayNext
import dev.jdtech.jellyfin.player.local.domain.seriesTrackChoice
import dev.jdtech.jellyfin.player.local.domain.toTrackPick
import dev.jdtech.jellyfin.player.local.domain.trackPreferenceKey
import dev.jdtech.jellyfin.player.local.mpv.MPVPlayer
import dev.jdtech.jellyfin.player.local.mpv.mpvSubtitleOptionChanges
import dev.jdtech.jellyfin.player.local.mpv.mpvSubtitleOptions
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.repository.PlaybackEvents
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.Constants
import dev.jdtech.jellyfin.settings.presentation.enums.AppVariant
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber

/** How long the OS gets to follow the surface frame rate hint before the second log line. */
private const val DISPLAY_RATE_LOG_DELAY_MS = 5_000L

@HiltViewModel
class PlayerViewModel
@Inject
constructor(
    private val application: Application,
    private val playlistManager: PlaylistManager,
    private val repository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    private val savedStateHandle: SavedStateHandle,
    private val playbackEvents: PlaybackEvents,
    private val sessionStore: PlaybackSessionStore,
    private val database: ServerDatabaseDao,
    private val jellyfinApi: JellyfinApi,
    private val appVariant: AppVariant,
) : ViewModel(), Player.Listener {
    val player: Player

    /**
     * The only media session for [player], on phone and TV alike. Media3 does not allow two
     * sessions with the same ID, so the activities and screens must not build their own.
     */
    private var mediaSession: MediaSession? = null

    /** Read once per player session. The setting is only offered on phones. */
    val isBackgroundAudioEnabled: Boolean =
        appPreferences.getValue(appPreferences.playerBackgroundAudio)

    private val _uiState =
        MutableStateFlow(
            UiState(
                currentItemId = null,
                currentItemTitle = "",
                currentSegment = null,
                currentSkipButtonStringRes = R.string.player_controls_skip_intro,
                currentTrickplay = null,
                currentChapters = emptyList(),
                fileLoaded = false,
                showStillWatching = false,
                stillWatchingTimeoutSeconds = 30,
            )
        )
    val uiState = _uiState.asStateFlow()

    /**
     * CE (#126, ADR 0004): position, duration and play state, updated every [PROGRESS_TICK_MS]
     * while something collects it (the TV player). Phone doesn't collect it, so there it never
     * runs. Each tick also refreshes the playing snapshot, as the TV screen's own poll loop did.
     */
    val progress: StateFlow<PlaybackProgress> =
        flow {
                while (true) {
                    refreshPlayingSnapshot()
                    val duration = player.duration
                    emit(
                        PlaybackProgress(
                            positionMs = player.currentPosition.coerceAtLeast(0L),
                            durationMs = if (duration == C.TIME_UNSET) 0L else duration,
                            isPlaying = player.isPlaying,
                        )
                    )
                    delay(PROGRESS_TICK_MS)
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000L),
                PlaybackProgress(positionMs = 0L, durationMs = 0L, isPlaying = false),
            )

    // Buffered so transient events (e.g. NavigateBack on STATE_ENDED, error toasts) are not
    // dropped if the collector is paused mid-config-change. trySend on a rendezvous channel
    // silently fails when nobody is collecting.
    private val eventsChannel = Channel<PlayerEvents>(Channel.BUFFERED)
    val eventsChannelFlow = eventsChannel.receiveAsFlow()

    data class UiState(
        /** The item currently loaded in the player. `null` until the first transition fires. */
        val currentItemId: UUID?,
        val currentItemTitle: String,
        /** The segment the skip button is for, while playback is in one set to ask. */
        val currentSegment: FindroidSegment?,
        val currentSkipButtonStringRes: Int,
        /**
         * CE (#271): the skip button's first seconds in [currentSegment]. After that the UI shows
         * it only while the controls are visible.
         */
        val skipButtonInWindow: Boolean = false,
        val currentTrickplay: Trickplay?,
        val currentChapters: List<PlayerChapter>,
        val fileLoaded: Boolean,
        val showStillWatching: Boolean,
        val stillWatchingTimeoutSeconds: Int,
        /**
         * CE (#367): when the still-watching prompt times out, in [SystemClock.elapsedRealtime]
         * time. The dialog counts down to it, so it shows the time really left after the app was
         * in the background.
         */
        val stillWatchingEndsAtMs: Long = 0L,
        /** CE (#131): the item in the player, for the TV overlay (series name, episode line). */
        val currentItem: PlayerItem? = null,
        /** CE (#452): the series of the item, the phone player's line above [currentItemTitle]. */
        val currentItemSeriesName: String? = null,
        /**
         * CE (#450): the connection was lost; the player loads again by itself when the network is
         * back. The phone player shows a spinner and a line saying so.
         */
        val waitingForNetwork: Boolean = false,
    )

    private val _queue = MutableStateFlow<List<FindroidItem>>(emptyList())

    /**
     * CE (#131): the queued items after the current one, for the TV player's queue row. Phone
     * doesn't read it. Jump to one with [playQueueItem].
     */
    val queue: StateFlow<List<FindroidItem>> = _queue.asStateFlow()

    /**
     * CE (#298): the sleep timer, for this player session only. [sleepTimerState] has its setting
     * and the time until it pauses, updated every second while it runs.
     */
    private val sleepTimer = SleepTimer()
    private var sleepTimerJob: Job? = null

    /** CE (#450): waiting for the network after the connection was lost. */
    private val networkRecovery = NetworkRecovery { SystemClock.elapsedRealtime() }
    private var lastNetworkError: PlaybackException? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkRetryJob: Job? = null

    /** The item the sleep timer stopped at the end of, until playback resumes. */
    private var sleepTimerEndedItemId: String? = null
    private val _sleepTimerState = MutableStateFlow(SleepTimerState(SleepTimerOption.Off, null))
    val sleepTimerState: StateFlow<SleepTimerState> = _sleepTimerState.asStateFlow()

    /** The sleep timer choices, with a one-minute one in debuggable builds for testing. */
    val sleepTimerChoices: List<SleepTimerOption> =
        SleepTimerOption.choices(
            debug = application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        )

    /**
     * [remainingMs] is the time until the timer pauses: on the clock for a duration, until the end
     * of the item (at the current speed) for [SleepTimerOption.EndOfItem], `null` when unknown.
     */
    data class SleepTimerState(val option: SleepTimerOption, val remainingMs: Long?)

    /**
     * CE (#272): the "Up next" card at the end of an episode, `null` while it is hidden. Updated
     * every [UP_NEXT_TICK_MS] by [updateUpNext]; the card's buttons call [playUpNextNow] and
     * [cancelUpNext].
     */
    private val upNextCountdown = UpNextCountdown()
    private val _upNext = MutableStateFlow<UpNextState?>(null)
    val upNext: StateFlow<UpNextState?> = _upNext.asStateFlow()

    /**
     * The next item, for the "Up next" card. [secondsLeft] and [fractionLeft] (1 to 0) are the
     * countdown to it; both are null when autoplay is off, as nothing starts by itself then.
     */
    data class UpNextState(
        val itemId: UUID,
        val title: String,
        /** `S01E02`, or null for an item without season and episode numbers. */
        val episodeLabel: String?,
        val imageUri: Uri?,
        val secondsLeft: Int?,
        val fractionLeft: Float?,
    )

    private var items: MutableList<PlayerItem> = mutableListOf()

    /**
     * The item whose audio and subtitle choice was last applied, and how many audio and subtitle
     * tracks it had then ([trackPreferenceKey]), so it happens once per set of tracks. CE (#596):
     * mpv lists the subtitle files of an item after the tracks of the stream, and a choice for
     * one of those found nothing to select when it was applied to the first list only.
     */
    private var appliedTrackPreferenceItemId: String? = null

    /**
     * CE (#296): the playing series' saved audio and subtitle choice, read from Room while its
     * first item buffers. See [seriesTrackPreferences].
     */
    private var trackPreferences: Pair<UUID, Deferred<List<SeriesTrackPreferenceDto>>>? = null

    /** CE (#596): the tracks picked by hand in an item without a series, such as a movie. */
    private val sessionTrackPicks = SessionTrackPicks()

    private val displayRefreshRateManager = DisplayRefreshRateManager(appPreferences)
    private var loggedDisplayFrameRate: Float? = null
    private val trackSelector = DefaultTrackSelector(application)
    private val audioDecoderFallback =
        AudioDecoderFallback(
            build = Build.FINGERPRINT,
            broken = appPreferences.getValue(appPreferences.playerBrokenAudioDecoders),
            isTv = application.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
            onBrokenChanged = {
                appPreferences.setValue(appPreferences.playerBrokenAudioDecoders, it)
            },
        )
    var playWhenReady = true
    private var currentMediaItemIndex = savedStateHandle["mediaItemIndex"] ?: 0
    private var playbackPosition: Long = savedStateHandle["position"] ?: 0
    // CE (#271): which segment playback is in and what to do about it. Built from the settings
    // in init.
    private var segmentTracker =
        SegmentTracker(
            actions = emptyMap(),
            buttonDurationMs = 0L,
            nextEpisodeThresholdMs = 0L,
            autoSkipOnlyInPip = false,
        )

    var playbackSpeed: Float = 1f

    /** Bitrate cap in bits per second, 0 = original quality. Mirrors [AppPreferences.playerMaxBitrate]. */
    var maxBitrate: Int = 0
        private set

    var isInPictureInPictureMode: Boolean = false

    // "Are you still watching?" — prompt fires after N consecutive auto-advanced episodes
    // OR M minutes without a user touch. Either threshold can be disabled by setting it
    // to 0 in settings.
    private val stillWatchingTracker: StillWatchingTracker
    private val stillWatchingPromptTimeoutSeconds: Int
    private var stillWatchingTimeoutJob: Job? = null

    // Armed immediately before an app-initiated seekToNextMediaItem() (auto-advance). Because
    // pauseAtEndOfMediaItems is on, the app performs that transition as an explicit seek, which
    // surfaces in onPositionDiscontinuity as DISCONTINUITY_REASON_SEEK — indistinguishable from a
    // user seek without this hint. onPositionDiscontinuity checks and clears the flag so it does
    // not treat our own advance as a user interaction (which would reset the still-watching
    // counter and refresh the inactivity timer). The flag persists until the callback fires rather
    // than being cleared right after the seek call, so it holds whether a backend delivers the
    // discontinuity synchronously from seekTo() (MPVPlayer) or later on the main looper.
    private var pendingAutoAdvanceSeek = false

    /**
     * The item the server was told is playing, with the last known position and duration. Used to
     * report playback stopped for the item being left when [onMediaItemTransition] fires, which
     * covers every item change (auto-advance, next/previous buttons, gestures, media-session and
     * remote controls, the outro "Next episode" button) on both backends.
     *
     * `null` before the first transition, after [releasePlayer], and after the still-watching
     * prompt already reported the finished item (so the transition that follows does not report it
     * again).
     *
     * [startJob] is the item's playback start report, `null` until it is sent. Until the duration
     * is known, [positionMs] is the position playback was asked to start at (#461).
     */
    private data class PlayingSnapshot(
        val itemId: UUID,
        val positionMs: Long,
        val durationMs: Long,
        val startJob: Job? = null,
    )

    private var playing: PlayingSnapshot? = null

    /** CE (#601): holds an item's start report until the player has loaded it. */
    private val startGate = PlaybackStartGate<PlayerItem>()

    /**
     * Whether to start the next item by itself at the end of an item: the "Play next episode
     * automatically" preference, which follows the Jellyfin user's `enableNextEpisodeAutoPlay`
     * unless it is set to always or never. Resolved once per player session and started here, at
     * construction, so the end of an item does not wait on the network. The server is only asked
     * when the preference follows it; if that fails (offline, network error) it falls back to
     * autoplay. Only affects what happens automatically: next/previous, the outro "Next episode"
     * button and remote controls always work, and the queue is the same either way.
     */
    private val autoPlayNext: Deferred<Boolean> =
        viewModelScope.async {
            val pref = appPreferences.getValue(appPreferences.playerAutoPlayNext)
            val server =
                if (autoPlayNextFollowsServer(pref)) {
                    try {
                        repository.getUserConfiguration()?.enableNextEpisodeAutoPlay
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.e(e, "Could not read the server's next episode autoplay setting")
                        null
                    }
                } else {
                    null
                }
            resolveAutoPlayNext(pref, server).also { Timber.d("Autoplay next item: $it") }
        }

    init {
        sessionStore.onSessionCreated()

        maxBitrate = appPreferences.getValue(appPreferences.playerMaxBitrate)

        // CE: the switch turns the whole prompt off, whatever the thresholds say.
        val enabled = appPreferences.getValue(appPreferences.stillWatchingEnabled)
        val episodes = appPreferences.getValue(appPreferences.stillWatchingAfterEpisodes)
        val minutes = appPreferences.getValue(appPreferences.stillWatchingAfterMinutes)
        stillWatchingTracker =
            StillWatchingTracker(
                autoAdvanceThreshold =
                    if (enabled) episodes.coerceAtLeast(0) else StillWatchingTracker.OFF,
                inactivityThresholdMs =
                    if (!enabled || minutes <= 0) StillWatchingTracker.OFF_MS
                    else minutes * 60_000L,
            )
        stillWatchingTracker.reset(nowMs = System.currentTimeMillis())
        stillWatchingPromptTimeoutSeconds =
            appPreferences
                .getValue(appPreferences.stillWatchingPromptTimeoutSeconds)
                .coerceAtLeast(5)
        _uiState.update { it.copy(stillWatchingTimeoutSeconds = stillWatchingPromptTimeoutSeconds) }

        segmentTracker =
            SegmentTracker(
                actions =
                    appPreferences.playerMediaSegmentsActions
                        .mapNotNull { (type, preference) ->
                            val segmentType =
                                FindroidSegmentType.entries.firstOrNull { it.name == type }
                                    ?: return@mapNotNull null
                            segmentType to
                                MediaSegmentAction.fromPreferenceValue(
                                    appPreferences.getValue(preference),
                                    default = MediaSegmentAction.IGNORE,
                                )
                        }
                        .toMap(),
                buttonDurationMs =
                    appPreferences.getValue(
                        appPreferences.playerMediaSegmentsSkipButtonDuration
                    ) * 1000L,
                nextEpisodeThresholdMs =
                    appPreferences.getValue(
                        appPreferences.playerMediaSegmentsNextEpisodeThreshold
                    ),
                autoSkipOnlyInPip =
                    appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkipMode) ==
                        Constants.PlayerMediaSegmentsAutoSkip.PIP,
            )

        val audioAttributes =
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setUsage(C.USAGE_MEDIA)
                .build()

        trackSelector.setParameters(
            trackSelector
                .buildUponParameters()
                .setTunnelingEnabled(true)
                .setPreferredAudioLanguage(
                    appPreferences.getValue(appPreferences.preferredAudioLanguage)
                )
                .setPreferredTextLanguage(
                    appPreferences.getValue(appPreferences.preferredSubtitleLanguage)
                )
        )


        val playerBackend = appPreferences.getValue(appPreferences.playerBackend)
        player = when (playerBackend) {
            "exoplayer" -> {
                // ON (not PREFER) so the platform MediaCodec decodes audio ahead of the
                // bundled ffmpeg extension: only the platform pipeline does Atmos spatial
                // audio and E-AC-3/Atmos passthrough over HDMI, and it costs less battery.
                // ffmpeg always decodes to PCM. It picks up what no platform decoder
                // handles, and what a broken one fails on, see AudioDecoderFallback.
                val renderersFactory =
                    DefaultRenderersFactory(application)
                        .setExtensionRendererMode(
                            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                        )
                        .setMediaCodecSelector(audioDecoderFallback)
                // Keep ExoPlayer's default frame rate strategy (ONLY_IF_SEAMLESS): it hints
                // the surface with the video frame rate so the OS can drop a 120Hz phone
                // panel to a multiple of 24fps, as upstream does. Forcing it OFF when the
                // TV-only "match refresh rate" setting is unset left phones at their
                // maximum rate for the whole playback (#338). The TV setting only drives
                // the non-seamless mode switch in DisplayRefreshRateManager.
                ExoPlayer.Builder(application, renderersFactory)
                    .setAudioAttributes(audioAttributes, true)
                    .setHandleAudioBecomingNoisy(true)
                    .setMediaSourceFactory(
                        buildMediaSourceFactory(application, jellyfinApi.httpClient)
                    )
                    .setTrackSelector(trackSelector)
                    .setSeekBackIncrementMs(
                        appPreferences.getValue(appPreferences.playerSeekBackInc)
                    )
                    .setSeekForwardIncrementMs(
                        appPreferences.getValue(appPreferences.playerSeekForwardInc)
                    )
                    .setPauseAtEndOfMediaItems(true)
                    .build()
            }
            "mpv" -> {
                MPVPlayer.Builder(application)
                    .setAudioAttributes(audioAttributes, true)
                    .setTrackSelectionParameters(trackSelector.parameters)
                    .setSeekBackIncrementMs(
                        appPreferences.getValue(appPreferences.playerSeekBackInc)
                    )
                    .setSeekForwardIncrementMs(
                        appPreferences.getValue(appPreferences.playerSeekForwardInc)
                    )
                    .setPauseAtEndOfMediaItems(true)
                    .setVideoOutput(appPreferences.getValue(appPreferences.playerMpvVo))
                    .setAudioOutput(appPreferences.getValue(appPreferences.playerMpvAo))
                    .setHwDec(appPreferences.getValue(appPreferences.playerMpvHwdec))
                    .setMatchRefreshRate(
                        appPreferences.getValue(appPreferences.playerTvMatchRefreshRate)
                    )
                    .setSubtitleOptions(appPreferences.mpvSubtitleOptions())
                    .build()
            }

            else -> throw RuntimeException("$playerBackend is not a valid player backend")
        }

        // CE (#300, #308): mpv takes a subtitle setting changed while it plays (from the settings, with
        // the player in picture-in-picture). ExoPlayer's SubtitleView is styled when the player
        // screen opens.
        (player as? MPVPlayer)?.let { mpvPlayer ->
            viewModelScope.launch {
                appPreferences.mpvSubtitleOptionChanges().collect {
                    mpvPlayer.updateSubtitleOptions(it)
                }
            }
        }

        // A new player can open while the old one is still alive (a Watch Next link while playing,
        // #187), so each session needs its own ID: Media3 allows only one session per ID.
        mediaSession =
            MediaSession.Builder(application, player).setId(UUID.randomUUID().toString()).build()
        // PlaybackService keeps the process alive, with a media notification, while the player
        // activity is in the background. Without background audio the player pauses when the
        // activity does, so the service is not needed.
        if (isBackgroundAudioEnabled) {
            // Tapping the media notification or lock screen controls brings the player back.
            mediaSession?.setSessionActivity(
                PendingIntent.getActivity(
                    application,
                    0,
                    Intent()
                        .setClassName(application.packageName, "dev.jdtech.jellyfin.PlayerActivity"),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            PlaybackService.mediaSession = mediaSession
            application.startService(Intent(application, PlaybackService::class.java))
        }

        // CE (#272): the "Up next" card, on both backends and in the background too, so its
        // countdown ends the same way whether or not a screen shows it.
        viewModelScope.launch {
            while (true) {
                updateUpNext()
                delay(UP_NEXT_TICK_MS)
            }
        }
    }

    fun attachWindow(window: Window) {
        displayRefreshRateManager.attachWindow(window)
    }

    fun detachWindow() {
        displayRefreshRateManager.detachWindow()
    }

    fun initializePlayer(
        itemId: UUID,
        itemKind: String,
        startFromBeginning: Boolean,
        mediaSourceId: String? = null,
        shuffle: Boolean = false,
    ) {
        player.addListener(this)

        viewModelScope.launch {
            var failure: Exception? = null
            val startItem =
                try {
                    playlistManager.getInitialItem(
                        itemId = itemId,
                        itemKind = BaseItemKind.fromName(itemKind),
                        mediaSourceId = mediaSourceId,
                        startFromBeginning = startFromBeginning,
                        shuffle = shuffle,
                    )
                } catch (e: Exception) {
                    Timber.e(e)
                    failure = e
                    null
                }

            if (startItem == null) {
                Timber.e("No start item, stopping player initialization")
                // CE (#131, #374): nothing will play, so tell the screen. The TV player shows the
                // message; the phone player shows a toast and closes. No toast here, or the
                // phone showed the raw exception text as a second toast.
                eventsChannel.trySend(PlayerEvents.PlayerError.startFailed(failure))
                return@launch
            }

            items = listOfNotNull(startItem).toMutableList()
            currentMediaItemIndex = items.indexOf(startItem)

            val mediaItems = mutableListOf<MediaItem>()
            try {
                for (item in items) {
                    mediaItems.add(item.toMediaItem())
                }
            } catch (e: Exception) {
                Timber.e(e)
            }

            val startPosition =
                if (playbackPosition == 0L) {
                    items.getOrNull(currentMediaItemIndex)?.playbackPosition ?: C.TIME_UNSET
                } else {
                    playbackPosition
                }

            // Read the series' track choice alongside buffering, so it is there when the tracks are.
            startItem.seriesId?.let { seriesTrackPreferences(it) }
            player.setMediaItems(mediaItems, 0, startPosition)
            player.prepare()
            player.play()

            // The first item started without the episode list; fetch it now, while the player
            // buffers. The neighbour lookups wait for it either way.
            launch { playlistManager.loadQueue() }
        }
    }

    private fun PlayerItem.toMediaItem(): MediaItem {
        val streamUrl = mediaSourceUri
        val mediaSubtitles =
            externalSubtitles.map { externalSubtitle ->
                MediaItem.SubtitleConfiguration.Builder(externalSubtitle.uri)
                    .setLabel(
                        // CE (#597): a track of the file itself that the server delivers apart
                        // (during a transcode) is named as it is when the stream carries it.
                        if (externalSubtitle.embedded) {
                            externalSubtitle.title.ifBlank { null }
                        } else {
                            externalSubtitle.title.ifBlank {
                                application.getString(R.string.external)
                            }
                        }
                    )
                    .setMimeType(externalSubtitle.mimeType)
                    .setLanguage(externalSubtitle.language)
                    .setSelectionFlags(
                        if (externalSubtitle.embedded) {
                            (if (externalSubtitle.default) C.SELECTION_FLAG_DEFAULT else 0) or
                                (if (externalSubtitle.forced) C.SELECTION_FLAG_FORCED else 0)
                        } else {
                            0
                        }
                    )
                    .build()
            }

        Timber.d("Stream url: $streamUrl")
        val mediaItem =
            MediaItem.Builder()
                .setMediaId(itemId.toString())
                .setUri(streamUrl)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(name)
                        .setArtist(seriesName)
                        .setArtworkUri(artworkUri?.let { resolveArtworkUri(it) })
                        .build()
                )
                .setSubtitleConfigurations(mediaSubtitles)
                .apply {
                    // A transcode is delivered as an HLS manifest. Tell ExoPlayer
                    // explicitly so it does not have to infer the type from the URL.
                    if (isTranscoded) setMimeType(MimeTypes.APPLICATION_M3U8)
                }
                .build()

        return mediaItem
    }

    /**
     * CE (#131): play [itemId], an item from [queue], from its beginning. The player's playlist
     * starts over at that item, and [onMediaItemTransition] queues its neighbours as usual and
     * reports the item being left as stopped.
     */
    fun playQueueItem(itemId: UUID) {
        markUserInteraction()
        viewModelScope.launch { jumpToQueued(itemId) }
    }

    /**
     * CE (R10): play [itemId], an episode picked in the phone player's episode sheet, from its
     * beginning. An episode in the queue (the playing season, once [PlaylistManager.loadQueue] has
     * run) is jumped to as [playQueueItem] does; any other starts over as the episode page's Play
     * would, in this player.
     */
    fun playEpisode(itemId: UUID) {
        markUserInteraction()
        viewModelScope.launch {
            playlistManager.loadQueue()
            if (!jumpToQueued(itemId)) {
                initializePlayer(
                    itemId = itemId,
                    itemKind = BaseItemKind.EPISODE.serialName,
                    startFromBeginning = true,
                )
            }
        }
    }

    /** Restarts the player's playlist at [itemId] if it is queued. False when it is not. */
    private suspend fun jumpToQueued(itemId: UUID): Boolean {
        val playerItem =
            try {
                playlistManager.jumpTo(itemId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Could not jump to queue item $itemId")
                null
            } ?: return false
        refreshPlayingSnapshot()
        items = mutableListOf(playerItem)
        player.setMediaItems(listOf(playerItem.toMediaItem()), 0, 0L)
        player.prepare()
        player.play()
        return true
    }

    /** Downloaded items store their images relative to filesDir; the session needs a file URI. */
    private fun resolveArtworkUri(uri: Uri): Uri =
        if (uri.scheme == null) {
            Uri.fromFile(application.filesDir.resolve(uri.path.orEmpty().removePrefix("/")))
        } else {
            uri
        }

    /**
     * Turn video decoding off while the activity is in the background with background audio
     * playing, and back on when it returns. Nothing is drawn then, so decoding only costs battery.
     */
    fun setVideoEnabled(enabled: Boolean) {
        val isEnabled = C.TRACK_TYPE_VIDEO !in player.trackSelectionParameters.disabledTrackTypes
        if (isEnabled == enabled) return
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
                .build()
    }

    private fun releasePlayer() {
        // CE (#450): nothing loads again once the player is closing.
        endNetworkWait()
        displayRefreshRateManager.reset()
        // Take the exact position, then clear the snapshot so a transition fired while releasing
        // cannot report the same item a second time.
        refreshPlayingSnapshot()
        val stopped = playing
        playing = null
        startGate.clear()

        _uiState.value.currentTrickplay?.loader?.release()
        _uiState.update { it.copy(currentTrickplay = null) }
        sleepTimerJob?.cancel()
        sleepTimer.clear()
        playWhenReady = false
        playbackPosition = 0L
        currentMediaItemIndex = 0
        player.removeListener(this)
        player.release()

        val released = mediaSession
        mediaSession?.release()
        mediaSession = null
        // A newer player may already have handed the service its own session: leave that one.
        if (isBackgroundAudioEnabled && PlaybackService.mediaSession === released) {
            PlaybackService.mediaSession = null
            try {
                application.stopService(Intent(application, PlaybackService::class.java))
            } catch (e: Exception) {
                Timber.e(e)
            }
        }

        playbackEvents.onPlaying(null)
        stopped?.let { reportStopped(it) }
    }

    /**
     * Copy the player's position and duration into [playing], if the player is still on that item
     * and both values are known. Unknown values are skipped so the last good ones are kept: mpv
     * resets its position and duration as soon as it starts loading the next file, before it
     * reports the item change.
     *
     * Called from the periodic progress and segment updates, from the UI poll loops, and right
     * before every app-initiated item change, so a stop report uses a recent position.
     */
    fun refreshPlayingSnapshot() {
        val current = playing ?: return
        if (player.currentMediaItem?.mediaId != current.itemId.toString()) return
        val position = player.currentPosition
        val duration = player.duration
        if (position < 0 || duration == C.TIME_UNSET || duration <= 0) return
        playing = current.copy(positionMs = position, durationMs = duration)
    }

    /**
     * Tell the server (and the local database) that [snapshot]'s item stopped playing. The only
     * caller of [JellyfinRepository.postPlaybackStop]. Before the duration is known the stop is
     * only sent if the start was, and it leaves the local database alone ([playbackStopReport]).
     *
     * Runs in [GlobalScope] because it must outlive [viewModelScope]: [releasePlayer] runs from
     * [onCleared], and the player can be closed right after an item change. It waits up to
     * [START_REPORT_WAIT_MS] for the item's start report, so the stop reaches the server after it.
     *
     * @return the reporting job, so a caller can wait for it, or `null` if nothing was sent.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun reportStopped(snapshot: PlayingSnapshot): Job? {
        val (itemId, position, duration, startJob) = snapshot
        val report = playbackStopReport(position, duration, startSent = startJob != null)
        if (report == null) {
            Timber.d("No playback stop for $itemId: neither loaded nor started")
            return null
        }
        return GlobalScope.launch(Dispatchers.IO) {
            try {
                if (startJob != null && !startJob.isCompleted) {
                    withTimeoutOrNull(START_REPORT_WAIT_MS) { startJob.join() }
                        ?: Timber.w("Start report for $itemId still running, sending stop anyway")
                }
                if (report.playedPercentage == null) {
                    Timber.d("Sending playback stop for $itemId at its start, $position ms")
                } else {
                    Timber.d("Sending playback stop for $itemId at $position / $duration ms")
                }
                repository.postPlaybackStop(itemId, report.positionTicks, report.playedPercentage)
            } catch (e: Exception) {
                Timber.e(e)
            } finally {
                playbackEvents.onStopped(itemId)
            }
        }
    }

    fun updatePlaybackProgress() {
        Timber.d("Updating playback progress")
        viewModelScope.launch(Dispatchers.Main) {
            refreshPlayingSnapshot()
            savedStateHandle["position"] = player.currentPosition
            // The current item's stop was already reported (end of item without autoplay, or the
            // still-watching prompt). A progress report now would reopen its session on the
            // server and overwrite the position the stop stored, e.g. from onPause while the
            // player closes.
            // CE (#601): nor before the start report, which waits for the item to load. The
            // server marks an item without a runtime played at its first position report, so an
            // item that failed to open was marked played.
            if (playing?.startJob == null) return@launch
            val mediaItem = player.currentMediaItem ?: return@launch
            if (mediaItem.mediaId.isNotEmpty()) {
                val itemId = UUID.fromString(mediaItem.mediaId)
                // CE (#599): the item as it plays now; a quality switch replaces it in items.
                val transcoded = items.firstOrNull { it.itemId == itemId }?.isTranscoded == true
                try {
                    repository.postPlaybackProgress(
                        itemId,
                        player.currentPosition.times(10000),
                        !player.isPlaying,
                        transcoded,
                    )
                } catch (e: Exception) {
                    Timber.e(e)
                }
            }
        }
    }

    /**
     * Inspect the player's current position and update the UI to reflect the
     * configured action for any segment it has entered. Suspends rather than
     * launching its own coroutine so the activity's per-second poll loop can
     * call it directly without paying for an extra `viewModelScope.launch`
     * each tick (PR #20 review). The decisions are [SegmentTracker]'s (#271).
     */
    suspend fun updateCurrentSegment() = withContext(Dispatchers.Main) {
        refreshPlayingSnapshot()
        val decision =
            segmentTracker.update(
                positionMs = player.currentPosition,
                durationMs = player.duration,
                hasNextItem = player.hasNextMediaItem(),
                inPictureInPicture = isInPictureInPictureMode,
            )
        when (decision) {
            SegmentDecision.None -> {
                if (_uiState.value.currentSegment != null) {
                    _uiState.update { it.copy(currentSegment = null, skipButtonInWindow = false) }
                }
            }
            is SegmentDecision.Ask -> {
                Timber.tag("SegmentInfo").d("Asking to skip %s", decision.segment)
                _uiState.update {
                    it.copy(
                        currentSegment = decision.segment,
                        currentSkipButtonStringRes = skipButtonText(decision.segment, decision.target),
                        skipButtonInWindow = decision.inButtonWindow,
                    )
                }
            }
            is SegmentDecision.AutoSkip -> {
                // CE (#298): an outro skip to the next item is where the item ends for the timer.
                if (decision.target == SkipTarget.NextItem && sleepTimer.stopsAtEndOfItem) {
                    _uiState.update { it.copy(currentSegment = null, skipButtonInWindow = false) }
                    fireSleepTimer()
                    return@withContext
                }
                // CE (#272): an outro skip to the next item hands over to the "Up next" countdown,
                // or ends the item if the card was cancelled.
                if (decision.target == SkipTarget.NextItem) {
                    updateUpNext()
                    if (_upNext.value?.secondsLeft != null) {
                        _uiState.update { it.copy(currentSegment = null, skipButtonInWindow = false) }
                        return@withContext
                    }
                    if (upNextCountdown.isCancelled(player.currentMediaItem?.mediaId)) {
                        skipTo(SkipTarget.EndItem)
                        return@withContext
                    }
                }
                Timber.tag("SegmentInfo").d("Skipping %s", decision.segment)
                val target =
                    if (decision.target == SkipTarget.NextItem) {
                        SegmentTracker.autoSkipTarget(decision.target, autoPlayNext.await())
                    } else {
                        decision.target
                    }
                skipTo(target)
            }
        }
    }

    /**
     * True if at least one segment type is set to skip or ask; used by the
     * activity to decide whether to start the per-second segment poller.
     */
    fun shouldPollSegments(): Boolean = segmentTracker.isActive

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        Timber.d("Playing MediaItem: ${mediaItem?.mediaId}")
        displayRefreshRateManager.onMediaItemTransition()
        loggedDisplayFrameRate = null
        // CE (#450): another item: no reload of the one that lost its connection.
        if (networkRecovery.isWaiting) endNetworkWait()
        savedStateHandle["mediaItemIndex"] = player.currentMediaItemIndex

        // Every item change goes through here, whatever caused it, so this is where the item being
        // left is reported as stopped. The first load (nothing playing yet) reports nothing, and a
        // reload of the same item (quality switch) keeps its snapshot and reports nothing.
        val previous = playing
        val newId = mediaItem?.mediaId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val stopJob =
            if (previous != null && previous.itemId != newId) reportStopped(previous) else null
        playing =
            when {
                previous != null && previous.itemId == newId -> previous
                newId != null -> PlayingSnapshot(newId, 0L, C.TIME_UNSET)
                else -> null
            }
        // Smart Downloads must not delete the file that is open, even once the server has marked
        // it played. Only releasePlayer clears this: a finished item stays open until the player
        // closes.
        playbackEvents.onPlaying(newId)
        if (sleepTimer.onItemChanged(mediaItem?.mediaId)) updateSleepTimerState()

        viewModelScope.launch {
            try {
                items
                    .first { it.itemId.toString() == player.currentMediaItem?.mediaId }
                    .let { item ->
                        // CE (#452): the series name is a line of its own above this.
                        val itemTitle = playerTitle(item)
                        _uiState.update {
                            it.copy(
                                currentItemId = item.itemId,
                                currentItemTitle = itemTitle.title,
                                currentItemSeriesName = itemTitle.seriesName,
                                currentItem = item,
                                currentSegment = null,
                                currentChapters = item.chapters,
                                fileLoaded = false,
                            )
                        }

                        // Keep the previous item's stop ahead of this item's start, as the old
                        // auto-advance code did. A stop that reaches the server after the start
                        // can clear the session's now-playing item.
                        stopJob?.join()
                        // CE (#601): the start waits for the item to load (PlaybackStartGate).
                        startGate
                            .onTransition(
                                item,
                                ready = player.playbackState == ExoPlayer.STATE_READY,
                                current = playing?.itemId == item.itemId,
                            )
                            ?.let { sendPlaybackStart(it) }

                        segmentTracker.setSegments(emptyList())
                        if (shouldPollSegments()) {
                            getSegments(item.itemId)
                        }

                        playlistManager.setCurrentMediaItemIndex(item.itemId)

                        // Trickplay and the neighbouring items are not needed to show the first
                        // frame, so they wait for the player to be ready. When it already is (an
                        // auto-advance), they start now.
                        deferredWork = item
                        if (player.playbackState == ExoPlayer.STATE_READY) {
                            runDeferredWork()
                        }
                    }
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    /**
     * Tell the server that [item] started playing, and record the report on [playing] so that
     * leaving the item sends the stop this start is owed (#461). Until the duration is known the
     * snapshot holds the position playback was asked to start at, so that stop keeps a saved
     * resume position.
     *
     * Sent once the player has loaded the item ([startGate], #601): an item that never opens
     * sends no start, no progress and no stop.
     *
     * The report runs in [GlobalScope] so that closing the player does not cut it off halfway;
     * [reportStopped] waits for it (with a time limit) to keep the stop behind it.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun sendPlaybackStart(item: PlayerItem) {
        // The player moved on while the previous item's stop was being sent (Previous or Next
        // pressed while this item was loading). A start now would arrive after the next item's,
        // and nothing would stop it.
        if (playing?.itemId != item.itemId) {
            Timber.d("No playback start for ${item.itemId}: left before it was sent")
            return
        }
        val start =
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    repository.postPlaybackStart(item.itemId, item.isTranscoded)
                } catch (e: Exception) {
                    Timber.e(e)
                }
            }
        playing =
            playing?.let {
                if (it.durationMs == C.TIME_UNSET) {
                    it.copy(positionMs = item.playbackPosition, startJob = start)
                } else {
                    it.copy(startJob = start)
                }
            }
        start.join()
    }

    /** The item whose trickplay and neighbour queueing wait for the first ready state. */
    private var deferredWork: PlayerItem? = null

    /** Runs the work [onMediaItemTransition] left for the ready state, once per item. */
    private fun runDeferredWork() {
        val item = deferredWork ?: return
        if (item.itemId.toString() != player.currentMediaItem?.mediaId) return
        deferredWork = null

        viewModelScope.launch {
            try {
                if (appPreferences.getValue(appPreferences.playerTrickplay)) {
                    getTrickplay(item)
                }
            } catch (e: Exception) {
                Timber.e(e)
            }

            try {
                val previousItem = playlistManager.getPreviousPlayerItem()
                if (previousItem != null) {
                    items.add(player.currentMediaItemIndex, previousItem)
                    player.addMediaItem(player.currentMediaItemIndex, previousItem.toMediaItem())
                }

                val nextItem = playlistManager.getNextPlayerItem()
                if (nextItem != null) {
                    items.add(player.currentMediaItemIndex + 1, nextItem)
                    player.addMediaItem(player.currentMediaItemIndex + 1, nextItem.toMediaItem())
                }

                _queue.value = playlistManager.upcomingItems()

                Timber.tag("PlayerItems").d(items.map { it.indexNumber }.toString())
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    /**
     * CE (#477): the playWhenReady of the last [onPlayWhenReadyChanged], so a repeated end of item
     * report while already paused at the end is not taken as a new end. Starts as playing, so the
     * first end of a session always counts.
     */
    private var lastPlayWhenReady = true

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        val wasPlayWhenReady = lastPlayWhenReady
        lastPlayWhenReady = playWhenReady
        // End of the current item: transition to the next one, or close the player if autoplay is
        // off. On an advance, the stop for the finished item is reported by onMediaItemTransition.
        if (isNewEndOfItem(wasPlayWhenReady, playWhenReady, reason, player.playbackState)) {
            // Dispatched (not immediate) so the advance runs after this listener callback returns,
            // as it did when a network call came first.
            viewModelScope.launch(Dispatchers.Main) {
                refreshPlayingSnapshot()

                // CE (#298): the sleep timer is set for the end of this item, or already fired.
                // Stay paused on the last frame with the player open; play goes on from here.
                if (sleepTimer.stopsAtEndOfItem) {
                    sleepTimerEndedItemId = player.currentMediaItem?.mediaId
                    fireSleepTimer()
                    return@launch
                }

                // CE (#272): "Cancel" on the "Up next" card: stop here, as with autoplay off.
                if (
                    player.hasNextMediaItem() &&
                        upNextCountdown.isCancelled(player.currentMediaItem?.mediaId)
                ) {
                    endItemWithoutAdvancing()
                    return@launch
                }

                // Autoplay off: stop here and close the player instead of advancing. The last item
                // keeps the normal path below, which ends the playlist (STATE_ENDED →
                // NavigateBack) and reports the stop on release, so the setting changes nothing
                // there.
                if (player.hasNextMediaItem()) {
                    val endedItemId = player.currentMediaItem?.mediaId
                    // Normally resolved long ago; this only waits if the server is slow to answer.
                    val autoPlay = autoPlayNext.await()
                    // The user moved to another item while waiting: nothing left to do here.
                    if (player.currentMediaItem?.mediaId != endedItemId) return@launch
                    if (!autoPlay) {
                        endItemWithoutAdvancing()
                        return@launch
                    }
                }

                advanceOrAskStillWatching()
            }
        }
    }

    /**
     * The automatic move to the next item, at the end of an item or when the "Up next" countdown
     * runs out during an outro (#272): advance, or ask "Are you still watching?" first.
     */
    private fun advanceOrAskStillWatching() {
        // "Are you still watching?" — only intercept if there's another item to advance
        // to. If this was the last item, fall through to the normal seek+play (which
        // becomes a no-op for seek and the player will hit STATE_ENDED → NavigateBack).
        if (
            player.hasNextMediaItem() &&
                stillWatchingTracker.onAutoAdvance(nowMs = System.currentTimeMillis())
        ) {
            // Paused on the finished item (or, after the "Up next" countdown, in its outro), so no
            // transition happens yet. Report the stop now so the server knows the item finished,
            // and clear the snapshot so the transition after the prompt (confirm, or the user
            // pressing play or next after dismissing it) does not report it again.
            player.pause()
            refreshPlayingSnapshot()
            playing?.let { reportStopped(it) }
            playing = null
            showStillWatchingPrompt()
            return
        }
        autoAdvanceToNextItem()
    }

    /**
     * CE (#272): show, count down or hide the "Up next" card for the current position. Only for an
     * episode with a next item, and never when the end of the item belongs to the sleep timer or to
     * the still-watching prompt (which wins over the countdown).
     */
    private suspend fun updateUpNext() {
        val mediaId = player.currentMediaItem?.mediaId
        val current = currentPlayerItem()
        val next = current?.let { items.getOrNull(items.indexOf(it) + 1) }
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        // Normally resolved long ago; this only waits if the server is slow to answer.
        val autoPlay = autoPlayNext.await()
        val eligible =
            current != null &&
                (current.seriesId != null || current.indexNumber != null) &&
                next != null &&
                player.hasNextMediaItem() &&
                player.playbackState != Player.STATE_IDLE &&
                player.playbackState != Player.STATE_ENDED &&
                !sleepTimer.stopsAtEndOfItem &&
                !_uiState.value.showStillWatching &&
                !(autoPlay &&
                    stillWatchingTracker.wouldPromptOnAutoAdvance(System.currentTimeMillis()))
        val decision =
            upNextCountdown.update(
                itemId = mediaId,
                positionMs = player.currentPosition,
                durationMs = duration,
                outroStartMs = segmentTracker.closingOutroStartMs(duration, hasNextItem = true),
                eligible = eligible,
                autoPlay = autoPlay,
                speed = playbackSpeed,
            )
        when (decision) {
            UpNextDecision.Hidden -> _upNext.value = null
            is UpNextDecision.Show ->
                if (next != null) {
                    _upNext.value =
                        UpNextState(
                            itemId = next.itemId,
                            title = next.name,
                            episodeLabel = episodeLabel(next),
                            imageUri = next.artworkUri?.let { resolveArtworkUri(it) },
                            secondsLeft = decision.secondsLeft,
                            fractionLeft = decision.fractionLeft,
                        )
                }
            UpNextDecision.Advance -> {
                Timber.d("Up next countdown ended, advancing")
                _upNext.value = null
                advanceOrAskStillWatching()
            }
        }
    }

    /** CE (#272): "Play now" on the "Up next" card. */
    fun playUpNextNow() {
        markUserInteraction()
        _upNext.value = null
        if (!player.hasNextMediaItem()) return
        // Exact position for the stop report onMediaItemTransition sends for this item.
        refreshPlayingSnapshot()
        player.seekToNextMediaItem()
        player.play()
    }

    /**
     * CE (#272): "Cancel" on the "Up next" card. The item plays to its end and the player stops
     * there, as with autoplay off; the next item plays as usual.
     */
    fun cancelUpNext() {
        markUserInteraction()
        upNextCountdown.cancel(player.currentMediaItem?.mediaId)
        _upNext.value = null
    }

    /**
     * Advance to the next item as part of automatic playback (end-of-item auto-advance or a
     * confirmed still-watching prompt). Arms [pendingAutoAdvanceSeek] so the resulting
     * DISCONTINUITY_REASON_SEEK is not mistaken for a user interaction. The flag is only set when
     * a next item actually exists — otherwise seekToNextMediaItem() is a no-op that fires no
     * discontinuity, and the flag would leak onto the next genuine user seek.
     */
    private fun autoAdvanceToNextItem() {
        // Exact position for the stop report onMediaItemTransition sends for the item being left.
        refreshPlayingSnapshot()
        if (player.hasNextMediaItem()) {
            pendingAutoAdvanceSeek = true
        }
        player.seekToNextMediaItem()
        player.play()
    }

    /**
     * End the current item without moving on, because autoplay is off: report the stop for it and
     * close the player, the same way it closes after the last item. Used at the end of an item and
     * for an automatic skip of an outro that runs to the end. The still-watching prompt is not
     * shown, since nothing plays on.
     *
     * Clearing [playing] keeps [releasePlayer] (and [updatePlaybackProgress]) from reporting the
     * item again when the player closes. It also makes this a no-op if called again for the same
     * item, e.g. by the next segment poll while the player is closing.
     */
    private fun endItemWithoutAdvancing() {
        refreshPlayingSnapshot()
        val finished = playing ?: return
        playing = null
        Timber.d("Autoplay off — not advancing past ${finished.itemId}")
        // Already paused at the end of an item; this stops an outro that is still playing.
        player.pause()
        reportStopped(finished)
        eventsChannel.trySend(PlayerEvents.NavigateBack)
    }

    private fun showStillWatchingPrompt() {
        Timber.d("Still-watching threshold tripped — prompting user")
        val endsAtMs = SystemClock.elapsedRealtime() + stillWatchingPromptTimeoutSeconds * 1000L
        _uiState.update { it.copy(showStillWatching = true, stillWatchingEndsAtMs = endsAtMs) }
        stillWatchingTimeoutJob?.cancel()
        stillWatchingTimeoutJob =
            viewModelScope.launch {
                delay(endsAtMs - SystemClock.elapsedRealtime())
                // Timeout: user is gone. Hide the dialog and leave the player paused. The current
                // item's stop was already reported in onPlayWhenReadyChanged (with the actual
                // position), so the season won't be marked watched.
                _uiState.update { it.copy(showStillWatching = false) }
                Timber.d("Still-watching prompt timed out — staying paused")
            }
    }

    /** Called when the user confirms the still-watching prompt. Resumes auto-advance. */
    fun acknowledgeStillWatching() {
        stillWatchingTimeoutJob?.cancel()
        stillWatchingTimeoutJob = null
        stillWatchingTracker.onUserInteraction(nowMs = System.currentTimeMillis())
        _uiState.update { it.copy(showStillWatching = false) }
        if (player.hasNextMediaItem()) {
            autoAdvanceToNextItem()
        }
    }

    /** Called when the user dismisses the still-watching prompt without confirming. */
    fun dismissStillWatching() {
        stillWatchingTimeoutJob?.cancel()
        stillWatchingTimeoutJob = null
        _uiState.update { it.copy(showStillWatching = false) }
    }

    /** CE (#298): set the sleep timer, from now, or turn it off with [SleepTimerOption.Off]. */
    fun setSleepTimer(option: SleepTimerOption) {
        markUserInteraction()
        Timber.d("Sleep timer set to $option")
        sleepTimer.set(option, SystemClock.elapsedRealtime(), player.currentMediaItem?.mediaId)
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        updateSleepTimerState()
        if (option == SleepTimerOption.Off) return
        sleepTimerJob =
            viewModelScope.launch {
                while (sleepTimer.option != SleepTimerOption.Off) {
                    if (sleepTimer.isDue(SystemClock.elapsedRealtime())) {
                        fireSleepTimer()
                        break
                    }
                    updateSleepTimerState()
                    delay(SLEEP_TIMER_TICK_MS)
                }
            }
    }

    private fun updateSleepTimerState() {
        val option = sleepTimer.option
        val remainingMs =
            sleepTimer.remainingMs(SystemClock.elapsedRealtime())
                ?: if (option == SleepTimerOption.EndOfItem) {
                    val duration = player.duration
                    if (duration == C.TIME_UNSET || duration <= 0) {
                        null
                    } else {
                        ((duration - player.currentPosition).coerceAtLeast(0L) /
                                playbackSpeed.coerceAtLeast(0.1f))
                            .toLong()
                    }
                } else {
                    null
                }
        _sleepTimerState.value = SleepTimerState(option, remainingMs)
    }

    /**
     * The sleep timer went off: pause, report the position as a pause does, and hold autoplay
     * until the user resumes. The player stays open on the paused item.
     */
    private fun fireSleepTimer() {
        Timber.d("Sleep timer fired, pausing")
        sleepTimer.fire()
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        player.pause()
        updatePlaybackProgress()
        updateSleepTimerState()
        eventsChannel.trySend(PlayerEvents.SleepTimerFired)
    }

    /**
     * Play was pressed after the sleep timer stopped at the end of [endedItemId]: go on to the next
     * item, as autoplay would have. ExoPlayer does that by itself when play is pressed at the end
     * of an item; mpv stays on the last frame. A user who seeked back first just plays on.
     */
    private fun continueAfterSleepTimerEnd(endedItemId: String) {
        sleepTimerEndedItemId = null
        viewModelScope.launch(Dispatchers.Main) {
            if (player.currentMediaItem?.mediaId != endedItemId) return@launch
            val duration = player.duration
            if (duration == C.TIME_UNSET || duration - player.currentPosition > END_TOLERANCE_MS) {
                return@launch
            }
            if (player.hasNextMediaItem()) autoAdvanceToNextItem()
        }
    }

    /**
     * Reset the still-watching counters. Call from every user-driven action — touches,
     * gestures, button presses, dialog choices.
     */
    fun markUserInteraction() {
        stillWatchingTracker.onUserInteraction(nowMs = System.currentTimeMillis())
    }

    override fun onPlayerError(error: PlaybackException) {
        Timber.e(error, "Player error: ${error.errorCodeName}")
        if (audioDecoderFallback.onPlayerError(error)) {
            // Stays on the same item and position, and keeps playing if it was.
            Timber.w("Audio decoder failed, continuing without it")
            player.prepare()
            return
        }
        // CE (#450): a lost connection waits for the network and loads again by itself.
        val mediaId = player.currentMediaItem?.mediaId
        val canPrepare = player.isCommandAvailable(Player.COMMAND_PREPARE)
        when (networkRecovery.onError(error.errorCode, mediaId, canPrepare)) {
            NetworkRecovery.OnError.WAIT -> {
                Timber.w("Connection lost, waiting for the network")
                lastNetworkError = error
                waitForNetwork()
                eventsChannel.trySend(
                    PlayerEvents.PlayerError(error, nothingLoaded = false, waitingForNetwork = true)
                )
                return
            }
            NetworkRecovery.OnError.KEEP_WAITING -> {
                lastNetworkError = error
                return
            }
            NetworkRecovery.OnError.GIVE_UP -> {
                Timber.w("The network did not come back in time, giving up")
                endNetworkWait()
            }
            NetworkRecovery.OnError.SHOW_ERROR -> endNetworkWait()
        }
        eventsChannel.trySend(PlayerEvents.PlayerError.fromPlayer(error, player.currentMediaItem))
    }

    /**
     * CE (#450): show the waiting state and load again when a network with internet access comes
     * back ([ConnectivityManager.NetworkCallback]) and every [NetworkRecovery.RETRY_INTERVAL_MS]
     * while one is up. The callback is registered only while waiting.
     */
    private fun waitForNetwork() {
        _uiState.update { it.copy(waitingForNetwork = true) }
        val connectivityManager = application.getSystemService(ConnectivityManager::class.java)
        if (networkCallback == null && connectivityManager != null) {
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        viewModelScope.launch(Dispatchers.Main) {
                            retryAfterNetworkLoss(onlyWhenOnline = false)
                        }
                    }
                }
            try {
                connectivityManager.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    callback,
                )
                networkCallback = callback
                Timber.d("Watching the network")
            } catch (e: RuntimeException) {
                // Too many callbacks or no permission: the timer below still retries.
                Timber.w(e, "Could not watch the network")
            }
        }
        if (networkRetryJob == null) {
            networkRetryJob =
                viewModelScope.launch {
                    while (true) {
                        delay(NetworkRecovery.RETRY_INTERVAL_MS)
                        retryAfterNetworkLoss(onlyWhenOnline = true)
                    }
                }
        }
    }

    private fun retryAfterNetworkLoss(onlyWhenOnline: Boolean) {
        when (networkRecovery.check(player.currentMediaItem?.mediaId)) {
            NetworkRecovery.Check.RETRY -> {
                if (onlyWhenOnline && !isOnline()) return
                if (player.playbackState != Player.STATE_IDLE) return
                // Keeps the position, and playWhenReady: paused while waiting stays paused.
                Timber.d("Loading again after the connection was lost")
                player.prepare()
            }
            NetworkRecovery.Check.GIVE_UP -> {
                Timber.w("The network did not come back in time, giving up")
                val error = lastNetworkError
                endNetworkWait()
                error?.let {
                    eventsChannel.trySend(PlayerEvents.PlayerError.fromPlayer(it, player.currentMediaItem))
                }
            }
            NetworkRecovery.Check.STOPPED -> endNetworkWait()
        }
    }

    private fun isOnline(): Boolean {
        val connectivityManager =
            application.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        return connectivityManager
            .getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    /** CE (#450): stop waiting for the network: unregister, stop the timer, hide the state. */
    private fun endNetworkWait() {
        networkRecovery.stop()
        lastNetworkError = null
        networkRetryJob?.cancel()
        networkRetryJob = null
        networkCallback?.let { callback ->
            try {
                application
                    .getSystemService(ConnectivityManager::class.java)
                    ?.unregisterNetworkCallback(callback)
                Timber.d("Stopped watching the network")
            } catch (e: IllegalArgumentException) {
                Timber.w(e, "Network callback was not registered")
            }
        }
        networkCallback = null
        if (_uiState.value.waitingForNetwork) {
            _uiState.update { it.copy(waitingForNetwork = false) }
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
        var stateString = "UNKNOWN_STATE             -"
        when (state) {
            ExoPlayer.STATE_IDLE -> {
                stateString = "ExoPlayer.STATE_IDLE      -"
            }
            ExoPlayer.STATE_BUFFERING -> {
                stateString = "ExoPlayer.STATE_BUFFERING -"
            }
            ExoPlayer.STATE_READY -> {
                stateString = "ExoPlayer.STATE_READY     -"
                // CE (#450): loaded again after the connection was lost.
                if (networkRecovery.isWaiting) endNetworkWait()
                _uiState.update { it.copy(fileLoaded = true) }
                // CE (#601): the item loaded, so its start report is due.
                startGate.onReady()?.let { item ->
                    viewModelScope.launch { sendPlaybackStart(item) }
                }
                runDeferredWork()
            }
            ExoPlayer.STATE_ENDED -> {
                stateString = "ExoPlayer.STATE_ENDED     -"
                displayRefreshRateManager.reset()
                eventsChannel.trySend(PlayerEvents.NavigateBack)
            }
        }
        Timber.d("Changed player state to $stateString")
    }

    private fun handleDisplayRefreshRate(tracks: Tracks) {
        val format = extractVideoFormat(tracks)
        val frameRate = format?.frameRate?.takeIf { it > 0f && !it.isNaN() } ?: return
        displayRefreshRateManager.onVideoFrameRateDetected(frameRate)
        logDisplayRefreshRate(frameRate)
    }

    /**
     * Logs the video frame rate against the display's refresh rate, now and once the OS has had
     * time to follow the surface's frame rate hint. Device tests grep this line (see
     * `docs/mpv-checklist.md`): a 120Hz phone that still reads 120Hz five seconds into a 24fps
     * video is #338 again.
     */
    private fun logDisplayRefreshRate(frameRate: Float) {
        if (frameRate == loggedDisplayFrameRate) return
        val before = displayRefreshRateManager.describeDisplay() ?: return
        loggedDisplayFrameRate = frameRate
        Timber.i("Video ${frameRate}fps, display $before")
        viewModelScope.launch {
            delay(DISPLAY_RATE_LOG_DELAY_MS)
            val after = displayRefreshRateManager.describeDisplay() ?: return@launch
            Timber.i("Video ${frameRate}fps, display $after after ${DISPLAY_RATE_LOG_DELAY_MS / 1000}s")
        }
    }

    private fun extractVideoFormat(tracks: Tracks): Format? {
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_VIDEO) {
                for (i in 0 until group.length) {
                    if (group.isTrackSelected(i)) {
                        val format = group.getTrackFormat(i)
                        if (format.frameRate > 0f && !format.frameRate.isNaN()) {
                            return format
                        }
                    }
                }
            }
        }
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_VIDEO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    if (format.frameRate > 0f && !format.frameRate.isNaN()) {
                        return format
                    }
                }
            }
        }
        return null
    }

    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
        handleDisplayRefreshRate(player.currentTracks)
    }

    override fun onCleared() {
        super.onCleared()
        Timber.d("Clearing Player ViewModel")
        displayRefreshRateManager.detachWindow()
        releasePlayer()
    }

    private fun currentPlayerItem(): PlayerItem? =
        items.firstOrNull { it.itemId.toString() == player.currentMediaItem?.mediaId }

    private fun supportedGroups(trackType: @C.TrackType Int) =
        player.currentTracks.groups.filter { it.type == trackType && it.isSupported }

    /** The audio track playing now, as the matcher describes it, or null when it has no name. */
    private fun selectedAudioChoice(): StoredChoice? {
        val groups = supportedGroups(C.TRACK_TYPE_AUDIO)
        return seriesTrackChoice(
            playerCandidates(C.TRACK_TYPE_AUDIO),
            groups.indexOfFirst { it.isSelected },
        )
    }

    private fun playerCandidates(trackType: @C.TrackType Int): List<TrackCandidate> =
        supportedGroups(trackType).map {
            val format = it.mediaTrackGroup.getFormat(0)
            TrackCandidate(format.language, format.label)
        }

    /**
     * Remembers the track at [index] of [candidates], picked by hand: for the rest of the series,
     * or, in an item without a series (CE, #596), for as long as the player is open. Without that
     * a movie fell back to its default tracks on every reload of the stream: a quality switch, or
     * an audio switch while the server transcodes.
     */
    private fun rememberTrackChoice(
        trackType: @C.TrackType Int,
        index: Int,
        candidates: List<TrackCandidate> = playerCandidates(trackType),
    ) {
        val item = currentPlayerItem() ?: return
        // Only subtitles can be remembered as off. Silent audio for a whole series is never wanted.
        if (index == -1 && trackType != C.TRACK_TYPE_TEXT) return
        val choice = if (index == -1) null else seriesTrackChoice(candidates, index)
        val seriesId = item.seriesId
        if (seriesId == null) {
            // A track with neither a language nor a title cannot be found again after a reload.
            if (index != -1 && choice == null) {
                sessionTrackPicks.forget(item.itemId, trackType)
            } else {
                sessionTrackPicks.remember(item.itemId, TrackPick(trackType, choice))
            }
            return
        }
        val userId = jellyfinApi.userId ?: return
        val preference =
            if (index == -1) {
                SeriesTrackPreferenceDto(userId, seriesId, trackType, disabled = true)
            } else {
                if (choice == null) return
                SeriesTrackPreferenceDto(
                    userId,
                    seriesId,
                    trackType,
                    disabled = false,
                    language = choice.language,
                    title = choice.title,
                    positionInLanguage = choice.positionInLanguage,
                )
            }
        // Also the choice for the series' next items, read back once written (#296).
        trackPreferences =
            seriesId to
                viewModelScope.async(Dispatchers.IO) {
                    try {
                        database.insertSeriesTrackPreference(preference)
                    } catch (e: Exception) {
                        Timber.e(e)
                    }
                    readSeriesTrackPreferences(userId, seriesId)
                }
    }

    override fun onTracksChanged(tracks: Tracks) {
        handleDisplayRefreshRate(tracks)
        val selectableTracks =
            tracks.groups.count { it.type == C.TRACK_TYPE_AUDIO || it.type == C.TRACK_TYPE_TEXT }
        val itemId = player.currentMediaItem?.mediaId
        if (selectableTracks == 0 || itemId == null) return
        val key = trackPreferenceKey(itemId, selectableTracks)
        if (key == appliedTrackPreferenceItemId) return
        appliedTrackPreferenceItemId = key

        // Back to the global behaviour first, so a choice made in an earlier episode or series does
        // not carry over.
        var parameters = player.trackSelectionParameters.buildUpon()
        for (trackType in listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)) {
            parameters = parameters.clearOverridesOfType(trackType).setTrackTypeDisabled(trackType, false)
        }
        val reset = parameters.build()

        val item = currentPlayerItem()
        val preferences = item?.seriesId?.let { seriesTrackPreferences(it) }
        // CE (#596): an item without a series has the picks of this session instead.
        val sessionPicks =
            if (item != null && item.seriesId == null) sessionTrackPicks.of(item.itemId)
            else emptyList()
        if (preferences == null && sessionPicks.isEmpty()) {
            player.trackSelectionParameters = reset
            return
        }
        // Normally read while the item buffered (#296): then the choice goes in with the reset, as
        // one track selection before the first frame, and nothing switches or rebuffers after it.
        // Otherwise reset now and apply the choice once it is read.
        val ready = preferences?.isCompleted ?: true
        if (!ready) player.trackSelectionParameters = reset
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val stored = preferences?.await()?.map { it.toTrackPick() } ?: sessionPicks
            if (player.currentMediaItem?.mediaId != itemId) return@launch

            var applied = (if (ready) reset else player.trackSelectionParameters).buildUpon()
            for (pick in stored) {
                val trackType = pick.trackType
                val choice = pick.choice
                if (choice == null) {
                    if (trackType == C.TRACK_TYPE_TEXT) {
                        applied = applied.setTrackTypeDisabled(trackType, true)
                    }
                    continue
                }
                val groups = supportedGroups(trackType)
                val group =
                    matchSeriesTrack(choice, playerCandidates(trackType))?.let { groups[it] }
                        ?: continue
                applied =
                    applied
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                        .setTrackTypeDisabled(trackType, false)
            }
            player.trackSelectionParameters = applied.build()
        }
    }

    /**
     * The saved choice for [seriesId], read on IO once per series and kept for its next items.
     * Null without a logged-in user. A read error gives no choice, as if none was saved.
     */
    private fun seriesTrackPreferences(seriesId: UUID): Deferred<List<SeriesTrackPreferenceDto>>? {
        trackPreferences?.let { (id, preferences) -> if (id == seriesId) return preferences }
        val userId = jellyfinApi.userId ?: return null
        val preferences =
            viewModelScope.async(Dispatchers.IO) { readSeriesTrackPreferences(userId, seriesId) }
        trackPreferences = seriesId to preferences
        return preferences
    }

    private suspend fun readSeriesTrackPreferences(
        userId: UUID,
        seriesId: UUID,
    ): List<SeriesTrackPreferenceDto> =
        try {
            database.getSeriesTrackPreferences(userId, seriesId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e)
            emptyList()
        }

    /**
     * CE (#596): the file's audio streams when [trackType] is audio and the server transcodes
     * the playing item, or null: then the player's own tracks are the ones to list and select.
     */
    private fun transcodeAudioTracks(trackType: @C.TrackType Int): List<ServerAudioTrack>? =
        currentPlayerItem()
            ?.takeIf { trackType == C.TRACK_TYPE_AUDIO && it.isTranscoded }
            ?.audioTracks
            ?.takeIf { it.isNotEmpty() }

    /**
     * CE (#596): what the track dialog of [trackType] lists, for [switchToTrack] to pick from by
     * position. The player's tracks, except for audio while the server transcodes: that stream
     * carries one track, so the file's audio streams are listed instead.
     */
    fun trackChoices(trackType: @C.TrackType Int, context: Context): TrackChoices {
        val audioTracks = transcodeAudioTracks(trackType)
        if (audioTracks != null) {
            val off = C.TRACK_TYPE_AUDIO in player.trackSelectionParameters.disabledTrackTypes
            val playing = currentPlayerItem()?.audioStreamIndex
            return TrackChoices(
                names = audioTracks.getTrackNames(context),
                selected = if (off) -1 else audioTracks.indexOfFirst { it.index == playing },
            )
        }
        val groups = supportedGroups(trackType)
        return TrackChoices(
            names = groups.getTrackNames(context).toList(),
            selected = groups.indexOfFirst { it.isSelected },
        )
    }

    /** Selects the track at [index] of [trackChoices]; -1 turns the track type off. */
    fun switchToTrack(trackType: @C.TrackType Int, index: Int) {
        markUserInteraction()
        val audioTracks = transcodeAudioTracks(trackType)
        if (index != -1 && audioTracks != null) {
            switchTranscodeAudio(audioTracks, index)
            return
        }
        // Index -1 equals disable track
        if (index == -1) {
            player.trackSelectionParameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(trackType)
                    .setTrackTypeDisabled(trackType, true)
                    .build()
        } else {
            val filteredGroups = player.currentTracks.groups
                .filter { it.type == trackType && it.isSupported }
            val group = filteredGroups.getOrNull(index) ?: return
            player.trackSelectionParameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(
                        TrackSelectionOverride(
                            group.mediaTrackGroup,
                            0,
                        )
                    )
                    .setTrackTypeDisabled(trackType, false)
                    .build()
        }
        rememberTrackChoice(trackType, index)
    }

    /**
     * CE (#596): plays the audio stream at [index] of [audioTracks] while the server transcodes.
     * The transcode carries one track, so the server is asked for a new stream with the picked
     * one, and the queue is reloaded at the same position, as on a quality switch.
     */
    private fun switchTranscodeAudio(audioTracks: List<ServerAudioTrack>, index: Int) {
        val item = currentPlayerItem() ?: return
        val track = audioTracks.getOrNull(index) ?: return
        // "None" turns the stream's one track off in the player: any pick turns it on again.
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .build()
        rememberTrackChoice(
            C.TRACK_TYPE_AUDIO,
            index,
            audioTracks.map { TrackCandidate(it.language, it.title) },
        )
        if (track.index == item.audioStreamIndex) return

        viewModelScope.launch {
            val rebuilt =
                try {
                    // An episode's pick is saved for its series: once it is written, the other
                    // queued episodes are requested with it as well.
                    if (item.seriesId != null) trackPreferences?.second?.await()
                    items.toList().map {
                        playlistManager.recreatePlayerItem(
                            it,
                            audioStreamIndex = track.index.takeIf { _ -> it.itemId == item.itemId },
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e)
                    // The stream that plays keeps playing, with the track it has.
                    e.localizedMessage?.let {
                        Toast.makeText(application, it, Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
            reloadQueue(rebuilt)
        }
    }

    /**
     * Swaps the whole queue for [rebuilt], the same items with new streams, keeping the item, the
     * position and the pause state.
     */
    private fun reloadQueue(rebuilt: List<PlayerItem>) {
        val currentIndex = player.currentMediaItemIndex
        val position = player.currentPosition
        val playWhenReady = player.playWhenReady
        items = rebuilt.toMutableList()
        appliedTrackPreferenceItemId = null
        player.setMediaItems(rebuilt.map { it.toMediaItem() }, currentIndex, position)
        player.prepare()
        // Keep playing (or paused) as before, whatever the player does with the flag on a new
        // playlist: mpv used to clear it, so the button showed Play while it played (#305).
        player.playWhenReady = playWhenReady
    }

    fun selectSpeed(speed: Float) {
        markUserInteraction()
        player.setPlaybackSpeed(speed)
        playbackSpeed = speed
    }

    /**
     * Change the max streaming bitrate (0 = original quality) and switch the
     * running stream over to it. Every queued item's sources are re-resolved
     * under the new cap — the server answers with a transcode URL for files
     * above it — and the whole queue is swapped in place, preserving position
     * and pause state. Works on both backends: ExoPlayer and MPVPlayer share
     * the setMediaItems(items, index, position) + prepare() reload path.
     *
     * The playlist rebuild is done here explicitly instead of relying on
     * onMediaItemTransition: MPVPlayer only emits a transition when the media
     * *id* changes, which it does not on an in-place quality switch.
     * PlaylistManager's cache entries are replaced by recreatePlayerItem, so
     * when ExoPlayer does fire the transition its prev/next re-add finds the
     * items already queued and adds nothing.
     */
    fun selectMaxBitrate(bitrate: Int) {
        markUserInteraction()
        if (bitrate == maxBitrate) return
        val previousBitrate = maxBitrate
        maxBitrate = bitrate
        appPreferences.setValue(appPreferences.playerMaxBitrate, bitrate)

        viewModelScope.launch {
            // CE (#580): the playing item keeps its audio track when the switch leads to a
            // transcode, which carries one track.
            val playingId = player.currentMediaItem?.mediaId
            val playingAudio = selectedAudioChoice()
            val rebuilt =
                try {
                    items.map {
                        playlistManager.recreatePlayerItem(
                            it,
                            playingAudio.takeIf { _ -> it.itemId.toString() == playingId },
                        )
                    }
                } catch (e: Exception) {
                    Timber.e(e)
                    // Keep playing the current stream and roll the setting back so
                    // the UI does not claim a quality that is not being delivered.
                    maxBitrate = previousBitrate
                    appPreferences.setValue(appPreferences.playerMaxBitrate, previousBitrate)
                    // A Toast without text throws, and some errors (an NPE) have no message.
                    e.localizedMessage?.let {
                        Toast.makeText(application, it, Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

            // Same URLs for the whole queue (local file, or every source already
            // below the cap) — nothing to reload, keep playing untouched.
            if (rebuilt.map { it.mediaSourceUri } == items.map { it.mediaSourceUri }) {
                items = rebuilt.toMutableList()
                return@launch
            }

            reloadQueue(rebuilt)
        }
    }

    private suspend fun getSegments(itemId: UUID) {
        val segments =
            try {
                // Debuggable builds can fake segments for testing (#271).
                DebugSegments.read(application) ?: repository.getSegments(itemId)
            } catch (e: Exception) {
                Timber.e(e)
                emptyList()
            }
        segmentTracker.setSegments(segments)
    }

    private suspend fun getTrickplay(item: PlayerItem) {
        if (appVariant == AppVariant.TV) {
            getTvTrickplay(item)
            return
        }
        _uiState.value.currentTrickplay?.loader?.release()
        // CE (#594): an item without trickplay clears the previous item's, which would otherwise
        // show its thumbnails on this item's seek bar.
        val trickplayInfo = item.trickplayInfo
        if (trickplayInfo == null) {
            _uiState.update { it.copy(currentTrickplay = null) }
            return
        }
        Timber.d("Trickplay Resolution: ${trickplayInfo.width}")

        // Hand the consumer a loader instead of decoding the whole strip: a long film is
        // hundreds of bitmaps, which is a lot of memory and network while the player is starting.
        val loader =
            TrickplayLoader(
                repository = repository,
                itemId = item.itemId,
                width = trickplayInfo.width,
                height = trickplayInfo.height,
                tileWidth = trickplayInfo.tileWidth,
                tileHeight = trickplayInfo.tileHeight,
                thumbnailCount = trickplayInfo.thumbnailCount,
                interval = trickplayInfo.interval,
            )
        _uiState.update {
            it.copy(currentTrickplay = Trickplay(trickplayInfo.interval, loader = loader))
        }
    }

    /**
     * CE (#134): the TV box is weak and 32-bit, so TV always takes the lazy path, decoding one
     * thumbnail at a time at the size it's shown at. The sheet for the start position is fetched
     * right away, so the first seek shows a thumbnail without waiting for the network.
     */
    private fun getTvTrickplay(item: PlayerItem) {
        _uiState.value.currentTrickplay?.loader?.release()
        val trickplayInfo = item.trickplayInfo
        if (trickplayInfo == null) {
            _uiState.update { it.copy(currentTrickplay = null) }
            return
        }
        val tiles =
            TrickplayTiles(
                width = trickplayInfo.width,
                height = trickplayInfo.height,
                tileWidth = trickplayInfo.tileWidth,
                tileHeight = trickplayInfo.tileHeight,
                thumbnailCount = trickplayInfo.thumbnailCount,
                interval = trickplayInfo.interval,
            )
        val loader =
            TrickplayRegionLoader(
                repository = repository,
                itemId = item.itemId,
                tiles = tiles,
                targetWidth =
                    (TV_TRICKPLAY_WIDTH_DP * application.resources.displayMetrics.density).toInt(),
            )
        _uiState.update {
            it.copy(currentTrickplay = Trickplay(trickplayInfo.interval, loader = loader))
        }
        viewModelScope.launch { loader.prefetch(item.playbackPosition) }
    }

    /** The skip button was pressed for [segment]. */
    fun skipSegment(segment: FindroidSegment) {
        markUserInteraction()
        skipTo(segmentTracker.skipTarget(segment, player.duration, player.hasNextMediaItem()))
    }

    private fun skipTo(target: SkipTarget) {
        _uiState.update { it.copy(currentSegment = null, skipButtonInWindow = false) }
        when (target) {
            is SkipTarget.SeekTo -> player.seekTo(target.positionMs)
            SkipTarget.NextItem -> {
                // Exact position for the stop report onMediaItemTransition sends for this item.
                // Past 90 % of the runtime, that report marks the item as played (#31).
                refreshPlayingSnapshot()
                player.seekToNextMediaItem()
            }
            // An automatic outro skip with autoplay off: end the item and close the player.
            SkipTarget.EndItem -> endItemWithoutAdvancing()
        }
    }

    private fun skipButtonText(segment: FindroidSegment, target: SkipTarget): Int =
        if (target == SkipTarget.NextItem) {
            R.string.player_controls_next_episode
        } else {
            when (segment.type) {
                FindroidSegmentType.INTRO -> R.string.player_controls_skip_intro
                FindroidSegmentType.OUTRO -> R.string.player_controls_skip_outro
                FindroidSegmentType.RECAP -> R.string.player_controls_skip_recap
                FindroidSegmentType.COMMERCIAL -> R.string.player_controls_skip_commercial
                FindroidSegmentType.PREVIEW -> R.string.player_controls_skip_preview
                FindroidSegmentType.UNKNOWN -> R.string.player_controls_skip_unknown
            }
        }

    /**
     * Get chapters of current item
     *
     * @return list of [PlayerChapter]
     */
    private fun getChapters(): List<PlayerChapter> {
        return uiState.value.currentChapters
    }

    /**
     * Get the index of the current chapter
     *
     * @return the index of the current chapter
     */
    private fun getCurrentChapterIndex(): Int? {
        val chapters = getChapters()

        for (i in chapters.indices.reversed()) {
            if (chapters[i].startPosition < player.currentPosition) {
                return i
            }
        }

        return null
    }

    /**
     * Get the index of the next chapter
     *
     * @return the index of the next chapter
     */
    private fun getNextChapterIndex(): Int? {
        val chapters = getChapters()
        val currentChapterIndex = getCurrentChapterIndex() ?: return null

        return minOf(chapters.size - 1, currentChapterIndex + 1)
    }

    /**
     * Get the index of the previous chapter. Only use this for seeking as it will return the
     * current chapter when player position is more than 5 seconds past the start of the chapter
     *
     * @return the index of the previous chapter
     */
    private fun getPreviousChapterIndex(): Int? {
        val chapters = getChapters()
        val currentChapterIndex = getCurrentChapterIndex() ?: return null

        // Return current chapter when more than 5 seconds past chapter start
        if (player.currentPosition > chapters[currentChapterIndex].startPosition + 5000L) {
            return currentChapterIndex
        }

        return maxOf(0, currentChapterIndex - 1)
    }

    fun isLastChapter(): Boolean =
        getChapters().let { chapters -> getCurrentChapterIndex() == chapters.size - 1 }

    /**
     * Seek to chapter
     *
     * @param [chapterIndex] the index of the chapter to seek to
     * @return the [PlayerChapter] which has been sought to
     */
    fun seekToChapter(chapterIndex: Int): PlayerChapter? {
        return getChapters().getOrNull(chapterIndex)?.also { chapter ->
            player.seekTo(chapter.startPosition)
        }
    }

    fun onAction(action: PlayerAction) {
        when (action) {
            is PlayerAction.JumpToChapter -> {
                seekToChapter(action.index)
            }
        }
    }

    /**
     * Build the URL for a chapter thumbnail. Returns `null` when no item is loaded yet, when the
     * index is out of bounds, or when the server did not extract a thumbnail for this chapter.
     */
    fun chapterImageUrl(chapterIndex: Int): String? {
        val state = uiState.value
        val itemId = state.currentItemId ?: return null
        val chapter = state.currentChapters.getOrNull(chapterIndex) ?: return null
        val tag = chapter.imageTag ?: return null
        val base = repository.getBaseUrl().trimEnd('/')
        if (base.isEmpty()) return null
        return "$base/Items/$itemId/Images/Chapter/$chapterIndex?tag=$tag"
    }

    /**
     * Seek to the next chapter
     *
     * @return the [PlayerChapter] which has been sought to
     */
    fun seekToNextChapter(): PlayerChapter? {
        markUserInteraction()
        return getNextChapterIndex()?.let { seekToChapter(it) }
    }

    /**
     * Seek to the previous chapter Will seek to start of current chapter if player position is more
     * than 5 seconds past start of chapter
     *
     * @return the [PlayerChapter] which has been sought to
     */
    fun seekToPreviousChapter(): PlayerChapter? {
        markUserInteraction()
        return getPreviousChapterIndex()?.let { seekToChapter(it) }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        super.onIsPlayingChanged(isPlaying)
        eventsChannel.trySend(PlayerEvents.IsPlayingChanged(isPlaying))
        if (isPlaying) {
            sleepTimer.onResumed()
            sleepTimerEndedItemId?.let { continueAfterSleepTimerEnd(it) }
        }
        if (isBackgroundAudioEnabled) sessionStore.onPlayingChanged(isPlaying)
        if (isPlaying && isBackgroundAudioEnabled) startBackgroundProgressReports()
    }

    /**
     * Keeps reporting progress to the server while the session plays without a player screen,
     * which is normally the screen's job. It stops by itself once playback stops.
     */
    private fun startBackgroundProgressReports() {
        if (backgroundProgressJob?.isActive == true) return
        backgroundProgressJob =
            viewModelScope.launch {
                while (player.isPlaying) {
                    if (!sessionStore.isUiAttached) updatePlaybackProgress()
                    delay(5000L)
                }
            }
    }

    private var backgroundProgressJob: Job? = null

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        super.onPositionDiscontinuity(oldPosition, newPosition, reason)
        // Player.DISCONTINUITY_REASON_SEEK fires for user-driven seeks (controls + gestures both
        // end up here). It ALSO fires for our own auto-advance: with pauseAtEndOfMediaItems the
        // app performs the transition to the next item via an explicit seekToNextMediaItem(), which
        // the player reports as a seek, not as an AUTO_TRANSITION. autoAdvanceToNextItem() arms
        // pendingAutoAdvanceSeek before that call so we can tell the two apart here — a programmatic
        // advance must not count as a user interaction (that would reset the still-watching counter
        // and refresh the inactivity timer, so the prompt could never fire).
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            if (pendingAutoAdvanceSeek) {
                pendingAutoAdvanceSeek = false
            } else {
                markUserInteraction()
            }
        }
    }
}

sealed interface PlayerEvents {
    data object NavigateBack : PlayerEvents

    data class IsPlayingChanged(val isPlaying: Boolean) : PlayerEvents

    /**
     * Playback failed. [nothingLoaded] is true when the player has no item at all, so nothing will
     * ever play: the phone player closes then (#374). With an item loaded, Play retries.
     * [waitingForNetwork]: the connection was lost and the player loads again by itself when it is
     * back (#450); the phone player shows that from [PlayerViewModel.UiState.waitingForNetwork]
     * instead of an error.
     */
    data class PlayerError(
        val error: PlaybackException,
        val nothingLoaded: Boolean,
        val waitingForNetwork: Boolean = false,
    ) : PlayerEvents {
        companion object {
            /** The item to start with could not be loaded ([failure] is null without an item). */
            fun startFailed(failure: Exception?) =
                PlayerError(
                    PlaybackException(
                        failure?.localizedMessage,
                        failure,
                        PlaybackException.ERROR_CODE_UNSPECIFIED,
                    ),
                    nothingLoaded = true,
                )

            /** The player reported [error] while on [currentMediaItem]. */
            fun fromPlayer(error: PlaybackException, currentMediaItem: MediaItem?) =
                PlayerError(error, nothingLoaded = currentMediaItem == null)
        }
    }

    /** CE (#298): the sleep timer paused playback. */
    data object SleepTimerFired : PlayerEvents
}

/** How often a running sleep timer updates [PlayerViewModel.sleepTimerState] and checks its end. */
private const val SLEEP_TIMER_TICK_MS = 1_000L

/** How often [PlayerViewModel.upNext] is updated. */
private const val UP_NEXT_TICK_MS = 500L

/**
 * How long a stop report waits for its item's start report before it is sent anyway (#461), so a
 * start that hangs (no network) cannot hold back the stop.
 */
private const val START_REPORT_WAIT_MS = 5_000L

/** `S01E02` (VISION §4), `S01E02-E03` for a double episode, or null without numbers. */
internal fun episodeLabel(item: PlayerItem): String? {
    val season = item.parentIndexNumber ?: return null
    val episode = item.indexNumber ?: return null
    val end = item.indexNumberEnd?.let { "-E%02d".format(it) }.orEmpty()
    return "S%02dE%02d%s".format(season, episode, end)
}

/** How close to its end an item counts as ended, for resuming after the sleep timer. */
private const val END_TOLERANCE_MS = 2_000L
