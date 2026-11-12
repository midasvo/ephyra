package dev.jdtech.jellyfin.player.local.mpv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.core.content.getSystemService
import androidx.media3.common.AdPlaybackState
import androidx.media3.common.AudioAttributes
import androidx.media3.common.BasePlayer
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Player.Commands
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.audio.AudioFocusRequestCompat
import androidx.media3.common.audio.AudioManagerCompat
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Clock
import androidx.media3.common.util.ListenerSet
import androidx.media3.common.util.Size
import androidx.media3.common.util.Util
import dev.jdtech.jellyfin.settings.domain.Constants
import dev.jdtech.mpv.MPVLib
import dev.jdtech.mpv.MPVLib.MpvEvent
import dev.jdtech.mpv.MPVLib.MpvFormat
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber

class MPVPlayer(
    context: Context,
    private val audioAttributes: AudioAttributes = AudioAttributes.DEFAULT,
    private val handleAudioFocus: Boolean = true,
    @Volatile
    private var trackSelectionParameters: TrackSelectionParameters =
        TrackSelectionParameters.DEFAULT,
    private val seekBackIncrement: Long = C.DEFAULT_SEEK_BACK_INCREMENT_MS,
    private val seekForwardIncrement: Long = C.DEFAULT_SEEK_FORWARD_INCREMENT_MS,
    private val pauseAtEndOfMediaItems: Boolean = false,
    videoOutput: String = "gpu-next",
    // Mirrors AppPreferences.playerMpvAo — see the CE divergence note there (issue #52).
    // Never observed in practice: PlayerViewModel always passes the stored preference.
    audioOutput: String = Constants.MpvAudioOutput.AUDIOTRACK,
    hwDec: String = "mediacodec",
    private val matchRefreshRate: Boolean = false,
    subtitleOptions: Map<String, String> = emptyMap(),
) : BasePlayer(), MPVLib.EventObserver, AudioManager.OnAudioFocusChangeListener {
    private val mpvLib: MPVLib
    private val audioManager: AudioManager by lazy { context.getSystemService()!! }
    private var audioFocusCallback: () -> Unit = {}
    private lateinit var audioFocusRequest: AudioFocusRequestCompat
    private val handler: Handler = Handler(context.mainLooper)
    private val appContext: Context = context.applicationContext
    private var becomingNoisyRegistered = false
    private var currentSurface: Surface? = null
    private var currentFrameRate: Float = 0f

    /**
     * Set by [release]. After it every [MPVLib] call throws, and the activity's window, and with it
     * the SurfaceView, is torn down after the view model has released the player (#318).
     */
    @Volatile
    private var released = false

    // Headphones were unplugged: pause rather than carry on over the speaker.
    private val becomingNoisyReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
                mpvLib.setPropertyBoolean("pause", true)
                setPlayerStateAndNotifyIfChanged(
                    playWhenReady = false,
                    playWhenReadyChangeReason = PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
                )
            }
        }

    /**
     * mpv's END_FILE carries a reason, but the JNI layer only passes the event id. A file that
     * never reaches FILE_LOADED failed to open; its error line can come before or after END_FILE
     * (#446, see [MpvLoadState]).
     */
    private val logObserver =
        object : MPVLib.LogObserver {
            override fun logMessage(prefix: String, level: Int, text: String) {
                if (level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR) {
                    post {
                        val message = "$prefix: ${text.trim()}"
                        when (loadState.errorLogged(message)) {
                            MpvLoadState.Logged.KEPT -> Unit
                            // #576: the line the failure was held for.
                            MpvLoadState.Logged.COMPLETES_FAILURE -> {
                                handler.removeCallbacks(loadErrorHoldTimeout)
                                onLoadError(loadState.failure)
                            }
                            MpvLoadState.Logged.AFTER_FAILURE ->
                                Timber.w("mpv, after the failed load: $message")
                        }
                        streamBreak.onErrorLog(prefix, text, SystemClock.elapsedRealtime())
                    }
                }
            }
        }

    private constructor(
        builder: Builder
    ) : this(
        context = builder.context,
        audioAttributes = builder.audioAttributes,
        handleAudioFocus = builder.handleAudioFocus,
        trackSelectionParameters = builder.trackSelectionParameters,
        seekBackIncrement = builder.seekBackIncrementMs,
        seekForwardIncrement = builder.seekForwardIncrementMs,
        pauseAtEndOfMediaItems = builder.pauseAtEndOfMediaItems,
        videoOutput = builder.videoOutput,
        audioOutput = builder.audioOutput,
        hwDec = builder.hwDec,
        matchRefreshRate = builder.matchRefreshRate,
        subtitleOptions = builder.subtitleOptions,
    )

    class Builder(val context: Context) {
        var audioAttributes: AudioAttributes = AudioAttributes.DEFAULT
            private set

        var handleAudioFocus: Boolean = true
            private set

        var trackSelectionParameters: TrackSelectionParameters = TrackSelectionParameters.DEFAULT
            private set

        var seekBackIncrementMs: Long = C.DEFAULT_SEEK_BACK_INCREMENT_MS
            private set

        var seekForwardIncrementMs: Long = C.DEFAULT_SEEK_FORWARD_INCREMENT_MS
            private set

        var pauseAtEndOfMediaItems: Boolean = false
            private set

        var videoOutput: String = "gpu-next"
            private set

        var audioOutput: String = Constants.MpvAudioOutput.AUDIOTRACK
            private set

        var hwDec: String = "mediacodec"
            private set

        var matchRefreshRate: Boolean = false
            private set

        var subtitleOptions: Map<String, String> = emptyMap()
            private set

        fun setAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean) =
            apply {
                this.audioAttributes = audioAttributes
                this.handleAudioFocus = handleAudioFocus
            }

        fun setTrackSelectionParameters(trackSelectionParameters: TrackSelectionParameters) =
            apply {
                this.trackSelectionParameters = trackSelectionParameters
            }

        fun setSeekBackIncrementMs(seekBackIncrementMs: Long) = apply {
            this.seekBackIncrementMs = seekBackIncrementMs
        }

        fun setSeekForwardIncrementMs(seekForwardIncrementMs: Long) = apply {
            this.seekForwardIncrementMs = seekForwardIncrementMs
        }

        fun setPauseAtEndOfMediaItems(pauseAtEndOfMediaItems: Boolean) = apply {
            this.pauseAtEndOfMediaItems = pauseAtEndOfMediaItems
        }

        fun setVideoOutput(videoOutput: String) = apply { this.videoOutput = videoOutput }

        fun setAudioOutput(audioOutput: String) = apply { this.audioOutput = audioOutput }

        fun setHwDec(hwDec: String) = apply { this.hwDec = hwDec }

        fun setMatchRefreshRate(matchRefreshRate: Boolean) = apply {
            this.matchRefreshRate = matchRefreshRate
        }

        /** CE (#300, #308): see [mpvSubtitleOptions]. */
        fun setSubtitleOptions(subtitleOptions: Map<String, String>) = apply {
            this.subtitleOptions = subtitleOptions
        }

        fun build() = MPVPlayer(this)
    }

    init {
        val configDir = File(context.filesDir, "mpv")
        val cacheDir = File(context.cacheDir, "mpv")

        setupDirectories(context, configDir, cacheDir)

        mpvLib =
            MPVLib.create(context) ?: throw IllegalStateException("MPVLib.create() returned null")

        // General
        mpvLib.setOptionString("config", "yes")
        mpvLib.setOptionString("config-dir", configDir.path)
        for (opt in arrayOf("gpu-shader-cache-dir", "icc-cache-dir")) mpvLib.setOptionString(
            opt,
            cacheDir.path,
        )
        mpvLib.setOptionString("profile", "fast")
        mpvLib.setOptionString("vo", videoOutput)
        mpvLib.setOptionString("ao", audioOutput)
        mpvLib.setOptionString("gpu-context", "android")
        mpvLib.setOptionString("opengl-es", "yes")

        // Hardware video decoding
        mpvLib.setOptionString("hwdec", hwDec)
        mpvLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")

        // TLS
        mpvLib.setOptionString("tls-verify", "no")

        // Cache
        mpvLib.setOptionString("cache", "yes")
        mpvLib.setOptionString("cache-pause-initial", "yes")
        mpvLib.setOptionString("demuxer-max-bytes", "64MiB")
        mpvLib.setOptionString("demuxer-max-back-bytes", "32MiB")
        // #460: a read on a dead or starved connection blocks until this timeout before ffmpeg
        // reconnects. mpv's default of 60 s kept playback stuck for over a minute after the
        // network came back. 10 s used up ffmpeg's reconnect attempts during a longer stall; the
        // slowest single transcode request measured on the demo server took 6.4 s.
        mpvLib.setOptionString("network-timeout", "30")

        // Subs
        mpvLib.setOptionString("sub-scale-with-window", "yes")
        mpvLib.setOptionString("sub-use-margins", "no")
        subtitleOptions.forEach { (name, value) -> mpvLib.setOptionString(name, value) }

        // Language
        // Split on "-" and use last part because media3 does some weird mapping
        // See
        // https://github.com/androidx/media/blob/1.4.0/libraries/common/src/main/java/androidx/media3/common/util/Util.java#L3742
        trackSelectionParameters.preferredAudioLanguages.firstOrNull()?.let {
            mpvLib.setOptionString("alang", it.split("-").last())
        }
        trackSelectionParameters.preferredTextLanguages.firstOrNull()?.let {
            println(it.split("-").last())
            mpvLib.setOptionString("slang", it.split("-").last())
        }

        // Other options
        mpvLib.setOptionString("force-window", "no")
        mpvLib.setOptionString("keep-open", "always")
        mpvLib.setOptionString("save-position-on-quit", "no")
        mpvLib.setOptionString("ytdl", "no")
        mpvLib.setOptionString("audio-set-media-role", "yes")

        mpvLib.init()

        mpvLib.addObserver(this)
        mpvLib.addLogObserver(logObserver)

        // Observe properties
        data class Property(val name: String, val format: Int)
        arrayOf(
                Property("track-list", MpvFormat.MPV_FORMAT_STRING),
                Property("paused-for-cache", MpvFormat.MPV_FORMAT_FLAG),
                Property("eof-reached", MpvFormat.MPV_FORMAT_FLAG),
                Property("seekable", MpvFormat.MPV_FORMAT_FLAG),
                Property("time-pos", MpvFormat.MPV_FORMAT_DOUBLE),
                Property("duration", MpvFormat.MPV_FORMAT_DOUBLE),
                Property("demuxer-cache-time", MpvFormat.MPV_FORMAT_DOUBLE),
                Property("speed", MpvFormat.MPV_FORMAT_DOUBLE),
                Property("playlist-count", MpvFormat.MPV_FORMAT_INT64),
                Property("playlist-current-pos", MpvFormat.MPV_FORMAT_INT64),
            )
            .forEach { (name, format) -> mpvLib.observeProperty(name, format) }

        val audioSessionId = audioManager.generateAudioSessionId()
        if (audioSessionId != AudioManager.ERROR) {
            mpvLib.setPropertyInt("audiotrack-session-id", audioSessionId)
            mpvLib.setPropertyInt("aaudio-session-id", audioSessionId)
        }

        if (handleAudioFocus) {
            audioFocusRequest =
                AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(audioAttributes)
                    .setOnAudioFocusChangeListener(this)
                    .build()
            val res = AudioManagerCompat.requestAudioFocus(audioManager, audioFocusRequest)
            if (res != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                mpvLib.setPropertyBoolean("pause", true)
            }
        }
    }

    private fun setupDirectories(context: Context, configDir: File, cacheDir: File) {
        Timber.i("mpv config dir: $configDir")
        Timber.i("mpv cache dir: $cacheDir")

        if (!configDir.exists()) configDir.mkdirs()
        if (!cacheDir.exists()) cacheDir.mkdirs()

        writeFontsConf(context, configDir)
    }

    private fun writeFontsConf(context: Context, configDir: File) {
        val configFile = File(configDir, "fonts.conf")
        if (configFile.exists()) return

        val cacheDir = File(context.cacheDir, "fontconfig")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        val config =
            """
            <fontconfig>
                <dir>/system/fonts/</dir>
                <dir>/product/fonts/</dir>

                <cachedir>${cacheDir.path}</cachedir>

                <alias>
                    <family>serif</family>
                    <prefer><family>Noto Serif</family></prefer>
                </alias>

                <alias>
                    <family>sans-serif</family>
                    <prefer>
                        <family>Roboto</family>
                        <family>Noto Sans</family>
                    </prefer>
                </alias>

                <alias>
                    <family>monospace</family>
                    <prefer><family>Droid Sans Mono</family></prefer>
                </alias>

            </fontconfig>
        """
                .trimIndent()

        try {
            configFile.writeText(config)
        } catch (e: IOException) {
            Timber.w("Failed to write fonts.conf: $e")
        }
    }

    // Listeners and notification.
    private val listeners: ListenerSet<Player.Listener> =
        ListenerSet(context.mainLooper, Clock.DEFAULT) { listener: Player.Listener, flags: FlagSet
            ->
            listener.onEvents(this, Player.Events(flags))
        }
    private val videoListeners = CopyOnWriteArraySet<Player.Listener>()

    /** CE (#453): which item listeners were last told is playing. */
    private val itemTransitions = MpvItemTransitions(listeners)

    /** CE (#445): whether an end of file is a real end or a lost connection. */
    private val streamBreak = MpvStreamBreak()

    // Internal state.
    private var internalMediaItems = mutableListOf<MediaItem>()

    @Player.State private var playbackState: Int = STATE_IDLE
    private var currentPlayWhenReady: Boolean = false
    @Player.PlaybackSuppressionReason
    private var suppressionReason: Int = PLAYBACK_SUPPRESSION_REASON_NONE

    @Player.RepeatMode private val repeatMode: Int = REPEAT_MODE_OFF
    private var currentTracks: Tracks = Tracks.EMPTY
    private var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT

    // MPV Custom
    private var isPlayerReady: Boolean = false
    private var isSeekable: Boolean = false
    private var currentMediaItemIndex: Int = 0
    private var currentPositionMs: Long? = null
    private var currentDurationMs: Long? = null
    private var currentCacheDurationMs: Long? = null
    private var initialCommands = mutableListOf<Array<String>>()

    /**
     * CE (#597): the subtitle files of the item being loaded that are marked forced. `sub-add`
     * cannot pass that mark on, so the track list gets it back from here.
     */
    private val forcedSubtitleFiles = HashSet<String>()

    /**
     * CE (#617): runs `sub-add` commands off the main thread so slow subtitle downloads do not
     * block the UI. A single-thread executor preserves the queue order (#597).
     */
    private val subtitleExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "mpv-subtitles").apply { isDaemon = true }
        }
    private val mpvLock = Any()
    @Volatile
    private var loadGeneration: Long = 0L
    private var subtitlesLoadedGeneration: Long = -1L
    private var initialIndex: Int = 0
    private var initialSeekTo: Long = 0L

    /**
     * The start position handed to mpv with the first `loadfile`, so it opens there instead of at 0
     * and seeking afterwards. The seek in [applyInitialSeek] is skipped for it once the file is up.
     */
    private var startPassedToLoadMs: Long? = null
    private var notifiedAvailableCommands: Commands = Commands.EMPTY
    private var playerError: PlaybackException? = null

    /** CE (#446): whether an END_FILE is a failed load. */
    private val loadState = MpvLoadState()

    /**
     * CE (#576): a failed load whose error line never came is reported without one. Removed by a
     * new load, and by release() with every other callback.
     */
    private val loadErrorHoldTimeout = Runnable {
        if (!released && loadState.holdTimedOut()) onLoadError(loadState.failure)
    }

    /** CE (#474): what prepare() loads after an error. */
    private val errorRecovery = MpvErrorRecovery()

    /**
     * CE (#474): [reloadAfterError] moved mpv's playlist and the new file has not started yet. mpv
     * can report the index in between, which belongs to another item.
     */
    private var reloadingAfterError = false

    // mpv events

    /**
     * Runs an mpv callback on the main thread, unless the player was released before it got there:
     * mpv is gone then and every [MPVLib] call throws (#318).
     */
    private fun post(block: () -> Unit) {
        handler.post { if (!released) block() }
    }

    override fun eventProperty(property: String) {
        // Nothing to do...
    }

    override fun eventProperty(property: String, value: String) {
        post {
            when (property) {
                "track-list" -> {
                    val hadVideo = currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
                    val newTracks = getTracks(value, forcedSubtitleFiles)
                    currentTracks = newTracks
                    // The selected video track's frame rate, not the last video group's:
                    // mpv also lists attached pictures as 1fps video tracks.
                    val fps = selectedVideoFrameRate(newTracks)
                    if (fps != null) {
                        currentFrameRate = fps
                        applyFrameRateToSurface(currentSurface, fps)
                    }
                    listeners.sendEvent(EVENT_TRACKS_CHANGED) { listener ->
                        listener.onTracksChanged(currentTracks)
                    }
                    // PlayerView covers the surface with a black shutter while no video track is
                    // selected, and only lifts it on onRenderedFirstFrame.
                    if (isPlayerReady && !hadVideo && newTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                        for (videoListener in videoListeners) {
                            videoListener.onRenderedFirstFrame()
                        }
                    }
                }
            }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        post {
            when (property) {
                "eof-reached" -> {
                    // #474: after a break mpv holds the file paused at its end; a video reinit
                    // (the surface coming back) reaches the end again, which is no new error.
                    if (value && isPlayerReady && playerError == null) {
                        // #445: a lost connection reaches the end of the file too. It must not
                        // end the item, report it finished or advance to the next one.
                        val outcome = streamBreak.atEndOfFile(currentDurationMs)
                        Timber.d("mpv end of file, $streamBreak, duration $currentDurationMs: $outcome")
                        if (outcome is MpvStreamBreak.Outcome.Broken) {
                            onStreamBroken(outcome)
                            return@post
                        }
                        if (currentMediaItemIndex < (internalMediaItems.size - 1)) {
                            if (pauseAtEndOfMediaItems) {
                                setPlayerStateAndNotifyIfChanged(
                                    playWhenReady = false,
                                    playWhenReadyChangeReason =
                                        PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM,
                                    playbackState = STATE_READY,
                                )
                            } else {
                                val oldPosition = currentPositionInfo()
                                prepareMediaItem(currentMediaItemIndex + 1)
                                notifyPositionDiscontinuity(
                                    oldPosition,
                                    positionInfo(currentMediaItemIndex + 1, initialSeekTo),
                                    DISCONTINUITY_REASON_AUTO_TRANSITION,
                                )
                                play()
                            }
                        } else {
                            setPlayerStateAndNotifyIfChanged(
                                playWhenReady = false,
                                playWhenReadyChangeReason =
                                    PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM,
                                playbackState = STATE_ENDED,
                            )
                            // No reset: like ExoPlayer the player stays ENDED until a seek or a
                            // new prepare, and a seek must still reach mpv.
                        }
                    }
                }
                "paused-for-cache" -> {
                    if (isPlayerReady) {
                        if (value) {
                            setPlayerStateAndNotifyIfChanged(playbackState = STATE_BUFFERING)
                        } else {
                            setPlayerStateAndNotifyIfChanged(playbackState = STATE_READY)
                        }
                    }
                }
                "seekable" -> {
                    if (isSeekable != value) {
                        isSeekable = value
                        notifyAvailableCommandsIfChanged()
                    }
                }
            }
        }
    }

    override fun eventProperty(property: String, value: Long) {
        post {
            when (property) {
                "playlist-count" -> {
                    listeners.sendEvent(EVENT_TIMELINE_CHANGED) { listener ->
                        listener.onTimelineChanged(
                            timeline,
                            TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
                        )
                    }
                    notifyAvailableCommandsIfChanged()
                }
                "playlist-current-pos" -> {
                    // #474: after an error mpv may move on to the next entry before it is
                    // stopped. The item stays the one the error is on until prepare() or a seek
                    // to another item clears the error. #576: also while the error waits for
                    // mpv's error line, when there is no playerError yet.
                    if (
                        value < 0 ||
                            playerError != null ||
                            reloadingAfterError ||
                            loadState.failed
                    ) {
                        return@post
                    }
                    val indexChanged = currentMediaItemIndex != value.toInt()
                    currentMediaItemIndex = value.toInt()
                    itemTransitions.currentPositionChanged(timeline, currentMediaItem, indexChanged)
                    listeners.flushEvents()
                    notifyAvailableCommandsIfChanged()
                }
            }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        post {
            when (property) {
                // Doubles, not INT64: mpv rounds an INT64 observation to whole seconds.
                "time-pos" -> {
                    // #445: after a lost connection mpv jumps to the last frame; the position
                    // stays where playback broke.
                    if (playerError == null) {
                        currentPositionMs = (value * C.MILLIS_PER_SECOND).toLong()
                        streamBreak.onPosition(currentPositionMs!!, currentDurationMs)
                    }
                }
                "duration" -> currentDurationMs = (value * C.MILLIS_PER_SECOND).toLong()
                "demuxer-cache-time" -> {
                    currentCacheDurationMs = (value * C.MILLIS_PER_SECOND).toLong()
                    streamBreak.onBufferedEnd(currentCacheDurationMs!!, SystemClock.elapsedRealtime())
                }
                "speed" -> {
                    playbackParameters = playbackParameters.withSpeed(value.toFloat())
                    listeners.sendEvent(EVENT_PLAYBACK_PARAMETERS_CHANGED) { listener ->
                        listener.onPlaybackParametersChanged(playbackParameters)
                    }
                }
            }
        }
    }

    override fun event(eventId: Int) {
        post {
            when (eventId) {
                MpvEvent.MPV_EVENT_START_FILE -> {
                    reloadingAfterError = false
                    loadState.startFile()
                    streamBreak.reset()
                    if (!isPlayerReady && initialCommands.isNotEmpty() && subtitlesLoadedGeneration != loadGeneration) {
                        subtitlesLoadedGeneration = loadGeneration
                        val commands = initialCommands.toList()
                        val currentGen = loadGeneration
                        subtitleExecutor.execute {
                            loadSubtitles(commands, currentGen)
                        }
                    }
                }
                MpvEvent.MPV_EVENT_FILE_LOADED -> {
                    loadState.fileLoaded()
                    errorRecovery.fileLoaded()
                    isSeekable = mpvLib.getPropertyBoolean("seekable") == true
                    currentDurationMs =
                        (mpvLib.getPropertyDouble("duration")?.times(C.MILLIS_PER_SECOND))?.toLong()
                    notifyAvailableCommandsIfChanged()
                }
                // Position discontinuities are reported from seekTo() (and the end-of-file
                // auto-advance), not here: mpv also raises this event for the internal start
                // position seek below, which must not look like a seek to listeners.
                MpvEvent.MPV_EVENT_SEEK -> {
                    setPlayerStateAndNotifyIfChanged(playbackState = STATE_BUFFERING)
                }
                MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                    if (!isPlayerReady) {
                        isPlayerReady = true
                        val passed = startPassedToLoadMs
                        startPassedToLoadMs = null
                        val seeked =
                            if (passed != null && passed == initialSeekTo) {
                                // mpv opened at the start position already.
                                initialSeekTo = 0L
                                false
                            } else {
                                // A seek to 0 is redundant unless mpv was opened elsewhere.
                                applyInitialSeek(skipZero = passed == null)
                            }
                        // Until now setPlayWhenReady only recorded the flag, and mpv kept the pause
                        // state of the previous file (or none), so apply it both ways.
                        val play =
                            playWhenReady &&
                                playbackSuppressionReason == PLAYBACK_SUPPRESSION_REASON_NONE
                        if (play) Timber.d("Starting playback...")
                        mpvLib.setPropertyBoolean("pause", !play)
                        for (videoListener in videoListeners) {
                            videoListener.onRenderedFirstFrame()
                        }
                        // #303: without a seek no second restart follows, and that restart was
                        // the only thing that set READY, so the player stayed BUFFERING forever.
                        if (!seeked) {
                            setPlayerStateAndNotifyIfChanged(playbackState = STATE_READY)
                        }
                    } else {
                        setPlayerStateAndNotifyIfChanged(playbackState = STATE_READY)
                    }
                }
                MpvEvent.MPV_EVENT_END_FILE -> {
                    Timber.d(
                        "mpv END_FILE: fileLoaded=${loadState.fileLoaded}, " +
                            "endFileExpected=${loadState.endFileExpected}"
                    )
                    val end =
                        loadState.endFile {
                            mpvLib.getPropertyInt("playlist-playing-pos").also {
                                Timber.d("mpv END_FILE: mpv is on playlist entry $it")
                            }
                        }
                    when (end) {
                        MpvLoadState.End.NOT_A_FAILURE -> Unit
                        MpvLoadState.End.FAILED -> onLoadError(loadState.failure)
                        MpvLoadState.End.HOLD -> {
                            // #576: mpv's error lines come after END_FILE here. mpv is stopped
                            // now, so it does not play on; the report waits for the first line.
                            Timber.d("mpv could not open the file, waiting for its error line")
                            mpvLib.command(arrayOf("stop", "keep-playlist"))
                            handler.removeCallbacks(loadErrorHoldTimeout)
                            handler.postDelayed(loadErrorHoldTimeout, MpvLoadState.HOLD_MS)
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    /**
     * mpv could not open the current file. Reported like ExoPlayer does: the error, then IDLE, with
     * playWhenReady kept. mpv moves on to the next playlist entry by itself after a failure, so it
     * is stopped here; prepare() loads the playlist again.
     */
    private fun onLoadError(failure: MpvLoadState.Failure) {
        val message = failure.message
        // #446: without an error line the message stays null, so the UI shows its own
        // (localized) text instead of an English sentence from here.
        Timber.e("mpv could not open the file: ${message ?: "no error logged before END_FILE"}")
        // #474: a reload after a network error that fails is a network error again.
        // #576: so is a first load that could not connect.
        val errorCode = errorRecovery.loadErrorCode(message, failure.errors)
        failPlayback(PlaybackException(message, null, errorCode))
    }

    /**
     * #445: the stream broke during playback ([MpvStreamBreak]). Reported like ExoPlayer does: a
     * network error and IDLE, at the position where playback broke, which progress and stop reports
     * then carry. No ENDED, so the item is not reported finished and nothing advances.
     *
     * #474: mpv is paused on the file, not stopped, so the last frame stays as on ExoPlayer.
     * keep-open holds it at the end of the file; prepare() loads it again.
     */
    private fun onStreamBroken(broken: MpvStreamBreak.Outcome.Broken) {
        Timber.e(
            "mpv lost the stream at ${broken.positionMs} / $currentDurationMs ms: ${broken.message}"
        )
        currentPositionMs = broken.positionMs
        failPlayback(
            PlaybackException(
                broken.message,
                null,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            ),
            stop = false,
        )
    }

    /**
     * Stops mpv on its current file, or with [stop] false pauses it there, and reports [error],
     * then IDLE.
     */
    private fun failPlayback(error: PlaybackException, stop: Boolean = true) {
        playerError = error
        errorRecovery.onError(currentMediaItemIndex, error.errorCode)
        loadState.expectEndFile()
        if (stop) {
            mpvLib.command(arrayOf("stop", "keep-playlist"))
        } else {
            mpvLib.setPropertyBoolean("pause", true)
        }
        listeners.queueEvent(EVENT_PLAYER_ERROR) { listener ->
            listener.onPlayerErrorChanged(error)
            listener.onPlayerError(error)
        }
        setPlayerStateAndNotifyIfChanged(playbackState = STATE_IDLE)
    }

    /** The broadcast only matters while playing, so it is only received then. */
    private fun setBecomingNoisyRegistered(registered: Boolean) {
        if (registered == becomingNoisyRegistered) return
        becomingNoisyRegistered = registered
        if (registered) {
            Util.registerReceiverNotExported(
                appContext,
                becomingNoisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            )
        } else {
            appContext.unregisterReceiver(becomingNoisyReceiver)
        }
    }

    private fun setPlayerStateAndNotifyIfChanged(
        playWhenReady: Boolean = getPlayWhenReady(),
        @Player.PlayWhenReadyChangeReason
        playWhenReadyChangeReason: Int = PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
        @Player.State playbackState: Int = getPlaybackState(),
    ) {
        var playerStateChanged = false
        val wasPlaying = isPlaying
        // An error holds the player in IDLE until prepare(): mpv's events for the file it was
        // stopped on (a seek, a restart, the cache) can still arrive after it.
        val state = if (playerError != null) STATE_IDLE else playbackState
        if (state != getPlaybackState()) {
            this.playbackState = state
            listeners.queueEvent(EVENT_PLAYBACK_STATE_CHANGED) { listener ->
                listener.onPlaybackStateChanged(state)
            }
            playerStateChanged = true
        }
        if (playWhenReady != getPlayWhenReady()) {
            this.currentPlayWhenReady = playWhenReady
            setBecomingNoisyRegistered(playWhenReady)
            listeners.queueEvent(EVENT_PLAY_WHEN_READY_CHANGED) { listener ->
                listener.onPlayWhenReadyChanged(playWhenReady, playWhenReadyChangeReason)
            }
            playerStateChanged = true
        }
        if (playerStateChanged) {
            // The deprecated combined callback, as ExoPlayer sends it. This used to be a second
            // onPlaybackStateChanged, so every state change arrived twice (ENDED too).
            listeners.queueEvent(C.INDEX_UNSET) { listener ->
                @Suppress("DEPRECATION")
                listener.onPlayerStateChanged(playWhenReady, state)
            }
        }
        if (wasPlaying != isPlaying) {
            listeners.queueEvent(EVENT_IS_PLAYING_CHANGED) { listener ->
                listener.onIsPlayingChanged(isPlaying)
            }
        }
        listeners.flushEvents()
    }

    /**
     * Select a Track or disable a [MPVTrackType] in the current player.
     *
     * @param trackType The [MPVTrackType]
     * @param id Id to select or [C.INDEX_UNSET] to disable [MPVTrackType]
     * @return true if the track is or was already selected
     */
    private fun selectTrack(trackType: MPVTrackType, id: String) {
        mpvLib.setPropertyString(trackType.type, id)
    }

    /**
     * CE (#617): loads external subtitles off the main thread.
     *
     * `sub-add` with `auto` causes mpv to re-evaluate auto track selection, which would switch away
     * from an already selected track or undo a track choice (e.g. forced subtitles of #597, or
     * subtitles disabled by the user). We preserve the active or configured selection across each
     * `sub-add`.
     */
    private fun loadSubtitles(commands: List<Array<String>>, generation: Long) {
        for (command in commands) {
            synchronized(mpvLock) {
                if (released || loadGeneration != generation) return
                val targetSid =
                    targetSubtitleTrack(
                        trackSelectionParameters,
                        mpvLib.getPropertyString("sid"),
                    )

                mpvLib.command(command)

                if (released || loadGeneration != generation) return
                if (targetSid != null && mpvLib.getPropertyString("sid") != targetSid) {
                    selectTrack(MPVTrackType.SUBTITLE, targetSid)
                }
            }
        }
    }

    // Timeline wrapper
    private val timeline: Timeline =
        object : Timeline() {
            /** Returns the number of windows in the timeline. */
            override fun getWindowCount(): Int {
                return internalMediaItems.size
            }

            /**
             * Populates a [Timeline.Window] with data for the window at the specified index.
             *
             * @param windowIndex The index of the window.
             * @param window The [Timeline.Window] to populate. Must not be null.
             * @param defaultPositionProjectionUs A duration into the future that the populated
             *   window's default start position should be projected.
             * @return The populated [Timeline.Window], for convenience.
             */
            override fun getWindow(
                windowIndex: Int,
                window: Window,
                defaultPositionProjectionUs: Long,
            ): Window {
                val currentMediaItem =
                    internalMediaItems.getOrNull(windowIndex) ?: MediaItem.Builder().build()
                return window.set(
                    /* uid = */ windowIndex,
                    /* mediaItem = */ currentMediaItem,
                    /* manifest = */ null,
                    /* presentationStartTimeMs = */ C.TIME_UNSET,
                    /* windowStartTimeMs = */ C.TIME_UNSET,
                    /* elapsedRealtimeEpochOffsetMs = */ C.TIME_UNSET,
                    /* isSeekable = */ isSeekable,
                    /* isDynamic = */ false,
                    // Not the item's liveConfiguration: it is never null (UNSET by default), and a
                    // non-null one makes the window live, so the media session hid the position.
                    /* liveConfiguration = */ null,
                    /* defaultPositionUs = */ 0,
                    /* durationUs = */ Util.msToUs(currentDurationMs ?: C.TIME_UNSET),
                    /* firstPeriodIndex = */ windowIndex,
                    /* lastPeriodIndex = */ windowIndex,
                    /* positionInFirstPeriodUs = */ 0,
                )
            }

            /** Returns the number of periods in the timeline. */
            override fun getPeriodCount(): Int {
                return internalMediaItems.size
            }

            /**
             * Populates a [Timeline.Period] with data for the period at the specified index.
             *
             * @param periodIndex The index of the period.
             * @param period The [Timeline.Period] to populate. Must not be null.
             * @param setIds Whether [Timeline.Period.id] and [Timeline.Period.uid] should be
             *   populated. If false, the fields will be set to null. The caller should pass false
             *   for efficiency reasons unless the fields are required.
             * @return The populated [Timeline.Period], for convenience.
             */
            override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
                return period.set(
                    periodIndex,
                    periodIndex,
                    periodIndex,
                    Util.msToUs(currentDurationMs ?: C.TIME_UNSET),
                    0,
                    AdPlaybackState.NONE,
                    false,
                )
            }

            /**
             * Returns the index of the period identified by its unique [Timeline.Period.uid], or
             * [ ][C.INDEX_UNSET] if the period is not in the timeline.
             *
             * @param uid A unique identifier for a period.
             * @return The index of the period, or [C.INDEX_UNSET] if the period was not found.
             */
            override fun getIndexOfPeriod(uid: Any): Int {
                return uid as Int
            }

            /**
             * Returns the unique id of the period identified by its index in the timeline.
             *
             * @param periodIndex The index of the period.
             * @return The unique id of the period.
             */
            override fun getUidOfPeriod(periodIndex: Int): Any {
                return periodIndex
            }
        }

    // OnAudioFocusChangeListener implementation.

    /**
     * Called on the listener to notify it the audio focus for this listener has been changed. The
     * focusChange value indicates whether the focus was gained, whether the focus was lost, and
     * whether that loss is transient, or whether the new focus holder will hold it for an unknown
     * amount of time. When losing focus, listeners can use the focus change information to decide
     * what behavior to adopt when losing focus. A music player could for instance elect to lower
     * the volume of its music stream (duck) for transient focus losses, and pause otherwise.
     *
     * @param focusChange the type of focus change, one of [AudioManager.AUDIOFOCUS_GAIN],
     *   [AudioManager.AUDIOFOCUS_LOSS], [AudioManager.AUDIOFOCUS_LOSS_TRANSIENT] and
     *   [AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK].
     */
    override fun onAudioFocusChange(focusChange: Int) {
        if (released) return
        when (focusChange) {
            // As ExoPlayer: a permanent loss pauses for good, a transient one keeps playWhenReady
            // and suppresses playback until the focus comes back. Gain used to unpause mpv while
            // playWhenReady stayed false, so the player said paused while audio played (#305).
            AudioManager.AUDIOFOCUS_LOSS -> {
                mpvLib.setPropertyBoolean("pause", true)
                setPlaybackSuppressionReason(PLAYBACK_SUPPRESSION_REASON_NONE)
                setPlayerStateAndNotifyIfChanged(
                    playWhenReady = false,
                    playWhenReadyChangeReason = PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
                )
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val oldAudioFocusCallback = audioFocusCallback
                mpvLib.setPropertyBoolean("pause", true)
                setPlaybackSuppressionReason(PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS)
                audioFocusCallback = {
                    oldAudioFocusCallback()
                    setPlaybackSuppressionReason(PLAYBACK_SUPPRESSION_REASON_NONE)
                    if (playWhenReady && isPlayerReady && playerError == null) {
                        mpvLib.setPropertyBoolean("pause", false)
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                mpvLib.command(arrayOf("multiply", "volume", "$AUDIO_FOCUS_DUCKING"))
                audioFocusCallback = {
                    mpvLib.command(arrayOf("multiply", "volume", "${1f / AUDIO_FOCUS_DUCKING}"))
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                audioFocusCallback()
                audioFocusCallback = {}
            }
        }
    }

    // Player implementation.

    /**
     * Returns the [Looper] associated with the application thread that's used to access the player
     * and on which player events are received.
     */
    override fun getApplicationLooper(): Looper {
        return handler.looper
    }

    /**
     * Registers a listener to receive all events from the player.
     *
     * @param listener The listener to register.
     */
    override fun addListener(listener: Player.Listener) {
        listeners.add(listener)
        videoListeners.add(listener)
    }

    /**
     * Unregister a listener registered through [.addListener]. The listener will no longer receive
     * events.
     *
     * @param listener The listener to unregister.
     */
    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
        videoListeners.remove(listener)
    }

    /**
     * Clears the playlist and adds the specified [MediaItems][MediaItem].
     *
     * @param mediaItems The new [MediaItems][MediaItem].
     * @param resetPosition Whether the playback position should be reset to the default position in
     *   the first [Timeline.Window]. If false, playback will start from the position defined by
     *   [.getCurrentWindowIndex] and [.getCurrentPosition].
     */
    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        startNewLoad(index = null)
        errorRecovery.forget()
        reloadingAfterError = false
        mpvLib.command(arrayOf("playlist-clear"))
        mpvLib.command(arrayOf("playlist-remove", "current"))
        // A copy: callers pass immutable lists (listOf, Guava), and addMediaItems adds to it.
        internalMediaItems = mediaItems.toMutableList()
    }

    /**
     * Clears the playlist and adds the specified [MediaItems][MediaItem].
     *
     * @param mediaItems The new [MediaItems][MediaItem].
     * @param startWindowIndex The window index to start playback from. If [C.INDEX_UNSET] is
     *   passed, the current position is not reset.
     * @param startPositionMs The position in milliseconds to start playback from. If
     *   [ ][C.TIME_UNSET] is passed, the default position of the given window is used. In any case,
     *   if `startWindowIndex` is set to [C.INDEX_UNSET], this parameter is ignored and the position
     *   is not reset at all.
     * @throws androidx.media3.common.IllegalSeekPositionException If the provided
     *   `startWindowIndex` is not within the bounds of the list of media items.
     */
    override fun setMediaItems(
        mediaItems: MutableList<MediaItem>,
        startWindowIndex: Int,
        startPositionMs: Long,
    ) {
        startNewLoad(index = null)
        errorRecovery.forget()
        reloadingAfterError = false
        mpvLib.command(arrayOf("playlist-clear"))
        mpvLib.command(arrayOf("playlist-remove", "current"))
        // A copy: callers pass immutable lists (listOf, Guava), and addMediaItems adds to it.
        internalMediaItems = mediaItems.toMutableList()
        // The old playlist is gone, so the tracked index no longer refers to
        // anything. prepare() loads the new index 0 with "replace", making 0
        // the effective playing position; without this reset a stale index
        // equal to startWindowIndex makes prepareMediaItem skip the
        // playlist-play-index command and the wrong entry keeps playing.
        currentMediaItemIndex = 0
        initialIndex = startWindowIndex
        initialSeekTo = startPositionMs
        // Before prepare() sends a state change: the media session checks its cached position
        // against the timeline then, and crashed on a shorter playlist (#453).
        itemTransitions.playlistReplaced(timeline, internalMediaItems, startWindowIndex)
        listeners.flushEvents()
    }

    /**
     * Adds a list of media items at the given index of the playlist.
     *
     * @param index The index at which to add the media items. If the index is larger than the size
     *   of the playlist, the media items are added to the end of the playlist.
     * @param mediaItems The [MediaItems][MediaItem] to add.
     */
    override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) {
        // BasePlayer.addMediaItem(item) passes Int.MAX_VALUE for "at the end".
        val insertAt = index.coerceIn(0, internalMediaItems.size)
        internalMediaItems.addAll(insertAt, mediaItems)
        mediaItems.forEachIndexed { offset, mediaItem ->
            mpvLib.command(
                arrayOf(
                    "loadfile",
                    "${mediaItem.localConfiguration?.uri}",
                    "insert-at",
                    (insertAt + offset).toString(),
                )
            )
        }
        // mpv's playlist-current-pos follows, but the index is needed right away by callers.
        if (insertAt <= currentMediaItemIndex && internalMediaItems.size > mediaItems.size) {
            currentMediaItemIndex += mediaItems.size
        }
    }

    /**
     * Moves the media item range to the new index.
     *
     * @param fromIndex The start of the range to move.
     * @param toIndex The first item not to be included in the range (exclusive).
     * @param newIndex The new index of the first media item of the range. If the new index is
     *   larger than the size of the remaining playlist after removing the range, the range is moved
     *   to the end of the playlist.
     */
    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) {
        // Not supported. COMMAND_CHANGE_MEDIA_ITEMS is not advertised, so no controller calls it.
        Timber.w("MPVPlayer does not support moveMediaItems")
    }

    override fun replaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: MutableList<MediaItem>,
    ) {
        // Not supported, as moveMediaItems.
        Timber.w("MPVPlayer does not support replaceMediaItems")
    }

    /**
     * Removes a range of media items from the playlist.
     *
     * @param fromIndex The index at which to start removing media items.
     * @param toIndex The index of the first item to be kept (exclusive). If the index is larger
     *   than the size of the playlist, media items to the end of the playlist are removed.
     */
    override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
        // clearMediaItems() passes Int.MAX_VALUE.
        val end = toIndex.coerceAtMost(internalMediaItems.size)
        if (fromIndex < 0 || fromIndex >= end) return
        val removesCurrent = currentMediaItemIndex in fromIndex until end
        if (removesCurrent) startNewLoad(index = null)
        // From the end, so the indexes of the entries still to remove don't move.
        for (index in end - 1 downTo fromIndex) {
            mpvLib.command(arrayOf("playlist-remove", "$index"))
        }
        internalMediaItems.subList(fromIndex, end).clear()
        when {
            internalMediaItems.isEmpty() -> {
                currentMediaItemIndex = 0
                setPlayerStateAndNotifyIfChanged(playbackState = STATE_ENDED)
            }
            // mpv plays the entry after the removed one, or stops after the last one.
            removesCurrent -> {
                currentMediaItemIndex = fromIndex.coerceAtMost(internalMediaItems.size - 1)
                if (fromIndex < internalMediaItems.size) {
                    setPlayerStateAndNotifyIfChanged(playbackState = STATE_BUFFERING)
                } else {
                    setPlayerStateAndNotifyIfChanged(playbackState = STATE_ENDED)
                }
            }
            currentMediaItemIndex >= end -> currentMediaItemIndex -= end - fromIndex
        }
    }

    /**
     * Returns the player's currently available [Commands].
     *
     * The returned [Commands] are not updated when available commands change. Use
     * [ ][Player.Listener.onAvailableCommandsChanged] to get an update when the available commands
     * change.
     *
     * Executing a command that is not available (for example, calling [.next] if
     * [ ][.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM] is unavailable) will neither throw an exception nor
     * generate a [.getPlayerError] player error}.
     *
     * [.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM] and [.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM] are unavailable
     * if there is no such [MediaItem].
     *
     * @return The currently available [Commands].
     * @see Player.Listener.onAvailableCommandsChanged
     */
    override fun getAvailableCommands(): Commands {
        return Commands.Builder()
            .addAll(permanentAvailableCommands)
            .addIf(COMMAND_SEEK_TO_DEFAULT_POSITION, !isPlayingAd)
            .addIf(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, isCurrentMediaItemSeekable && !isPlayingAd)
            .addIf(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, hasPreviousMediaItem() && !isPlayingAd)
            .addIf(
                COMMAND_SEEK_TO_PREVIOUS,
                !currentTimeline.isEmpty &&
                    (hasPreviousMediaItem() ||
                        !isCurrentMediaItemLive ||
                        isCurrentMediaItemSeekable) &&
                    !isPlayingAd,
            )
            .addIf(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, hasNextMediaItem() && !isPlayingAd)
            .addIf(
                COMMAND_SEEK_TO_NEXT,
                !currentTimeline.isEmpty &&
                    (hasNextMediaItem() || (isCurrentMediaItemLive && isCurrentMediaItemDynamic)) &&
                    !isPlayingAd,
            )
            .addIf(COMMAND_SEEK_TO_MEDIA_ITEM, !isPlayingAd)
            .addIf(COMMAND_SEEK_BACK, isCurrentMediaItemSeekable && !isPlayingAd)
            .addIf(COMMAND_SEEK_FORWARD, isCurrentMediaItemSeekable && !isPlayingAd)
            .build()
    }

    /**
     * Sends [Player.Listener.onAvailableCommandsChanged] when [getAvailableCommands] differs from
     * what was last sent. The media session caches the commands it shows to controllers (the
     * notification's buttons) and only refreshes them on this event.
     */
    private fun notifyAvailableCommandsIfChanged() {
        val commands = availableCommands
        if (commands == notifiedAvailableCommands) return
        notifiedAvailableCommands = commands
        listeners.sendEvent(EVENT_AVAILABLE_COMMANDS_CHANGED) { listener ->
            listener.onAvailableCommandsChanged(commands)
        }
    }

    /**
     * Forgets what belongs to the previous file. Not the playback state or playWhenReady: callers
     * change those through [setPlayerStateAndNotifyIfChanged], so listeners hear about it. Clearing
     * them here silently left playWhenReady false after every prepare (#305).
     */
    private fun resetInternalState() {
        isPlayerReady = false
        isSeekable = false
        currentPositionMs = null
        currentDurationMs = null
        currentCacheDurationMs = null
        currentTracks = Tracks.EMPTY
        playbackParameters = PlaybackParameters.DEFAULT
        initialCommands.clear()
        forcedSubtitleFiles.clear()
        loadGeneration++
        subtitlesLoadedGeneration = -1L
    }

    /** Prepares the player. */
    override fun prepare() {
        clearPlayerError()
        // #474: after an error, load the item it was on again instead of the whole playlist.
        errorRecovery.onPrepare(internalMediaItems.size)?.let { index ->
            reloadAfterError(index)
            return
        }
        startNewLoad(index = null)
        startPassedToLoadMs = null
        internalMediaItems.forEachIndexed { index, mediaItem ->
            val uri = "${mediaItem.localConfiguration?.uri}"
            if (index == 0 && initialIndex == 0 && initialSeekTo > 0L) {
                // mpv 0.38+ (the bundled build is 0.41): loadfile <url> <flags> <index> <options>.
                // An index of -1 is the default, the options are what is needed here.
                val startSeconds = initialSeekTo.toDouble().div(C.MILLIS_PER_SECOND)
                mpvLib.command(arrayOf("loadfile", uri, "replace", "-1", "start=$startSeconds"))
                startPassedToLoadMs = initialSeekTo
            } else {
                mpvLib.command(arrayOf("loadfile", uri, if (index == 0) "replace" else "append"))
            }
        }
        prepareMediaItem(initialIndex)
    }

    /**
     * #576: this player loads something or stops, at playlist entry [index] when it names one. Ends
     * the failure of the file before it ([MpvLoadState]) and drops a report that was still held.
     */
    private fun startNewLoad(index: Int?) {
        loadState.newLoad(index)
        handler.removeCallbacks(loadErrorHoldTimeout)
    }

    private fun clearPlayerError() {
        if (playerError == null) return
        playerError = null
        listeners.sendEvent(EVENT_PLAYER_ERROR) { listener -> listener.onPlayerErrorChanged(null) }
    }

    /**
     * #474: loads the item at [index], which an error stopped, again at the position playback got
     * to, as ExoPlayer does on prepare() after an error. The new entry goes in before the old one
     * and plays, then the old one is removed: the item keeps its index, so no item transition is
     * sent (#453), and mpv opens it at the position instead of seeking there.
     */
    private fun reloadAfterError(index: Int) {
        val positionMs = currentPosition
        val uri = "${internalMediaItems[index].localConfiguration?.uri}"
        Timber.d("mpv: loading item $index again at $positionMs ms after the error")
        // mpv keeps its speed for the new file; mpv sends no change for it, so keep it here too.
        val parameters = playbackParameters
        // The same index, so this sends no playlist-play-index: it resets the file state and the
        // external subtitles, and sets BUFFERING.
        prepareMediaItem(index)
        playbackParameters = parameters
        initialSeekTo = positionMs
        // #576: no start option at 0, as in prepare(): an item that never opened is loaded again
        // from its beginning, and mpv seeks for start=0 too.
        startPassedToLoadMs = positionMs.takeIf { it > 0L }
        val startSeconds = positionMs.toDouble().div(C.MILLIS_PER_SECOND)
        // The start option belongs to the new entry. insert-at-play would only play it when mpv
        // plays nothing, and after a stream break it still holds the old file.
        reloadingAfterError = true
        if (positionMs > 0L) {
            mpvLib.command(arrayOf("loadfile", uri, "insert-at", "$index", "start=$startSeconds"))
        } else {
            mpvLib.command(arrayOf("loadfile", uri, "insert-at", "$index"))
        }
        mpvLib.command(arrayOf("playlist-play-index", "$index"))
        mpvLib.command(arrayOf("playlist-remove", "${index + 1}"))
    }

    /**
     * Returns the current [playback state][Player.State] of the player.
     *
     * @return The current [playback state][Player.State].
     * @see Player.Listener.onPlaybackStateChanged
     */
    override fun getPlaybackState(): Int {
        return playbackState
    }

    /**
     * Returns the reason why playback is suppressed even though [.getPlayWhenReady] is `true`, or
     * [.PLAYBACK_SUPPRESSION_REASON_NONE] if playback is not suppressed.
     *
     * @return The current [playback suppression reason][Player.PlaybackSuppressionReason].
     * @see Player.Listener.onPlaybackSuppressionReasonChanged
     */
    override fun getPlaybackSuppressionReason(): Int {
        return suppressionReason
    }

    private fun setPlaybackSuppressionReason(@Player.PlaybackSuppressionReason reason: Int) {
        if (reason == suppressionReason) return
        val wasPlaying = isPlaying
        suppressionReason = reason
        listeners.queueEvent(EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED) { listener ->
            listener.onPlaybackSuppressionReasonChanged(reason)
        }
        if (wasPlaying != isPlaying) {
            listeners.queueEvent(EVENT_IS_PLAYING_CHANGED) { listener ->
                listener.onIsPlayingChanged(isPlaying)
            }
        }
        listeners.flushEvents()
    }

    /**
     * Returns the error that caused playback to fail. This is the same error that will have been
     * reported via [Player.Listener.onPlayerError] at the time of failure. It can be queried using
     * this method until the player is re-prepared.
     *
     * Note that this method will always return `null` if [.getPlaybackState] is not [.STATE_IDLE].
     *
     * @return The error, or `null`.
     * @see Player.Listener.onPlayerError
     */
    override fun getPlayerError(): PlaybackException? {
        return playerError
    }

    /**
     * Sets whether playback should proceed when [.getPlaybackState] == [.STATE_READY].
     *
     * If the player is already in the ready state then this method pauses and resumes playback.
     *
     * @param playWhenReady Whether playback should proceed when ready.
     */
    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (currentPlayWhenReady != playWhenReady) {
            setPlayerStateAndNotifyIfChanged(
                playWhenReady = playWhenReady,
                playWhenReadyChangeReason = PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            )
            // #474: in the error state mpv holds the broken file paused; playing it would run
            // what is left in the cache while the player reports the error. prepare() applies
            // playWhenReady when the item is loaded again.
            if (isPlayerReady && playerError == null) {
                // Request audio focus when starting playback
                if (handleAudioFocus && playWhenReady) {
                    val res = AudioManagerCompat.requestAudioFocus(audioManager, audioFocusRequest)
                    if (res != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        mpvLib.setPropertyBoolean("pause", true)
                    } else {
                        setPlaybackSuppressionReason(PLAYBACK_SUPPRESSION_REASON_NONE)
                        mpvLib.setPropertyBoolean("pause", false)
                    }
                } else {
                    mpvLib.setPropertyBoolean("pause", !playWhenReady)
                }
            }
        }
    }

    /**
     * Whether playback will proceed when [.getPlaybackState] == [.STATE_READY].
     *
     * @return Whether playback will proceed when ready.
     * @see Player.Listener.onPlayWhenReadyChanged
     */
    override fun getPlayWhenReady(): Boolean {
        return currentPlayWhenReady
    }

    /**
     * Sets the [Player.RepeatMode] to be used for playback.
     *
     * @param repeatMode The repeat mode.
     */
    override fun setRepeatMode(repeatMode: Int) {
        when (repeatMode) {
            REPEAT_MODE_OFF -> {
                mpvLib.setOptionString("loop-file", "no")
                mpvLib.setOptionString("loop-playlist", "no")
            }
            REPEAT_MODE_ONE -> {
                mpvLib.setOptionString("loop-file", "inf")
                mpvLib.setOptionString("loop-playlist", "no")
            }
            REPEAT_MODE_ALL -> {
                mpvLib.setOptionString("loop-file", "no")
                mpvLib.setOptionString("loop-playlist", "inf")
            }
        }
    }

    /**
     * Returns the current [Player.RepeatMode] used for playback.
     *
     * @return The current repeat mode.
     * @see Player.Listener.onRepeatModeChanged
     */
    override fun getRepeatMode(): Int {
        return repeatMode
    }

    /**
     * Sets whether shuffling of windows is enabled.
     *
     * @param shuffleModeEnabled Whether shuffling is enabled.
     */
    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        // Not supported; COMMAND_SET_SHUFFLE_MODE is not advertised.
        Timber.w("MPVPlayer does not support shuffle")
    }

    /**
     * Returns whether shuffling of windows is enabled.
     *
     * @see Player.Listener.onShuffleModeEnabledChanged
     */
    override fun getShuffleModeEnabled(): Boolean {
        return false
    }

    /**
     * Whether the player is currently loading the source.
     *
     * @return Whether the player is currently loading the source.
     * @see Player.Listener.onIsLoadingChanged
     */
    override fun isLoading(): Boolean {
        return false
    }

    /**
     * Seeks to a position specified in milliseconds in the specified window.
     *
     * @param mediaItemIndex The index of the window.
     * @param positionMs The seek position in the specified window, or [C.TIME_UNSET] to seek to the
     *   window's default position.
     * @param seekCommand The {@link Player.Command} used to trigger the seek.
     * @param isRepeatingCurrentItem Whether this seeks repeats the current item.
     * @throws androidx.media3.common.IllegalSeekPositionException If the player has a non-empty
     *   timeline and the provided `windowIndex` is not within the bounds of the current timeline.
     */
    override fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
        @Player.Command seekCommand: Int,
        isRepeatingCurrentItem: Boolean,
    ) {
        val oldPosition = currentPositionInfo()
        if (playerError != null) {
            if (mediaItemIndex == currentMediaItemIndex) {
                // #474: mpv holds the broken file; prepare() loads it again from here.
                val seekTo = if (positionMs != C.TIME_UNSET) positionMs else 0L
                currentPositionMs = seekTo
                initialSeekTo = seekTo
                notifyPositionDiscontinuity(
                    oldPosition,
                    positionInfo(mediaItemIndex, seekTo),
                    DISCONTINUITY_REASON_SEEK,
                )
                return
            }
            // Another item is a new load, as after setMediaItems.
            clearPlayerError()
            errorRecovery.forget()
        }
        if (mediaItemIndex == currentMediaItemIndex) {
            val seekTo = if (positionMs != C.TIME_UNSET) positionMs else initialSeekTo
            initialSeekTo = seekTo
            applyInitialSeek()
            notifyPositionDiscontinuity(
                oldPosition,
                positionInfo(mediaItemIndex, seekTo),
                DISCONTINUITY_REASON_SEEK,
            )
        } else {
            prepareMediaItem(mediaItemIndex)
            // prepareMediaItem is a no-op for an index outside the playlist; nothing moved then.
            if (internalMediaItems.getOrNull(mediaItemIndex) != null) {
                notifyPositionDiscontinuity(
                    oldPosition,
                    positionInfo(mediaItemIndex, initialSeekTo),
                    DISCONTINUITY_REASON_SEEK,
                )
            }
            play()
        }
    }

    /**
     * Issues the pending [initialSeekTo] to mpv once the current file is ready; until then it stays
     * pending and is applied from MPV_EVENT_PLAYBACK_RESTART. Does not notify listeners — callers
     * that represent an actual seek report the discontinuity themselves.
     *
     * @param skipZero Skip a seek to 0, for the initial seek right after the file loaded.
     * @return Whether a seek was issued.
     */
    private fun applyInitialSeek(skipZero: Boolean = false): Boolean {
        if (!isPlayerReady) return false
        if (skipZero && initialSeekTo <= 0L) {
            initialSeekTo = 0L
            return false
        }
        mpvLib.command(
            arrayOf("seek", "${initialSeekTo.toDouble().div(C.MILLIS_PER_SECOND)}", "absolute")
        )
        streamBreak.onSeek(initialSeekTo)
        initialSeekTo = 0L
        return true
    }

    private fun currentPositionInfo(): Player.PositionInfo =
        positionInfo(currentMediaItemIndex, currentPositionMs ?: 0L)

    private fun positionInfo(mediaItemIndex: Int, positionMs: Long): Player.PositionInfo =
        Player.PositionInfo(
            /* windowUid= */ mediaItemIndex,
            /* mediaItemIndex= */ mediaItemIndex,
            /* mediaItem= */ internalMediaItems.getOrNull(mediaItemIndex),
            /* periodUid= */ mediaItemIndex,
            /* periodIndex= */ mediaItemIndex,
            /* positionMs= */ positionMs,
            /* contentPositionMs= */ positionMs,
            /* adGroupIndex= */ C.INDEX_UNSET,
            /* adIndexInAdGroup= */ C.INDEX_UNSET,
        )

    /**
     * Notifies listeners of a position discontinuity through both [Player.Listener] overloads. In
     * media3 these are independent default methods, so — like ExoPlayer — both must be invoked;
     * listeners may override either one.
     */
    private fun notifyPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        @Player.DiscontinuityReason reason: Int,
    ) {
        listeners.sendEvent(EVENT_POSITION_DISCONTINUITY) { listener ->
            @Suppress("DEPRECATION")
            listener.onPositionDiscontinuity(reason)
            listener.onPositionDiscontinuity(oldPosition, newPosition, reason)
        }
    }

    private fun prepareMediaItem(index: Int) {
        internalMediaItems.getOrNull(index)?.let { mediaItem ->
            startNewLoad(index)
            resetInternalState()
            mediaItem.localConfiguration?.subtitleConfigurations?.forEach { subtitle ->
                initialCommands.add(
                    arrayOf(
                        /* command= */ "sub-add",
                        /* url= */ "${subtitle.uri}",
                        /* flags= */ "auto",
                        // CE (#597): a subtitle without a title has none, not the title "null".
                        /* title= */ subtitle.label.orEmpty(),
                        /* lang= */ subtitle.language.orEmpty(),
                    )
                )
                if (subtitle.selectionFlags and C.SELECTION_FLAG_FORCED != 0) {
                    forcedSubtitleFiles.add("${subtitle.uri}")
                }
            }
            // Only set the playlist index when the index is not the currently playing item.
            // Otherwise
            // playback will be restarted.
            // This is a problem on initial load when the first item is still loading causing
            // duplicate
            // external subtitle entries.
            if (currentMediaItemIndex != index) {
                mpvLib.command(arrayOf("playlist-play-index", "$index"))
            }
            setPlayerStateAndNotifyIfChanged(playbackState = STATE_BUFFERING)
        }
    }

    override fun getSeekBackIncrement(): Long {
        return seekBackIncrement
    }

    override fun getSeekForwardIncrement(): Long {
        return seekForwardIncrement
    }

    override fun getMaxSeekToPreviousPosition(): Long {
        return C.DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION_MS
    }

    /**
     * Attempts to set the playback parameters. Passing [PlaybackParameters.DEFAULT] resets the
     * player to the default, which means there is no speed or pitch adjustment.
     *
     * Playback parameters changes may cause the player to buffer.
     * [ ][Player.Listener.onPlaybackParametersChanged] will be called whenever the currently active
     * playback parameters change.
     *
     * @param playbackParameters The playback parameters.
     */
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        if (getPlaybackParameters().speed != playbackParameters.speed) {
            mpvLib.setPropertyDouble("speed", playbackParameters.speed.toDouble())
        }
    }

    /**
     * Returns the currently active playback parameters.
     *
     * @see Player.Listener.onPlaybackParametersChanged
     */
    override fun getPlaybackParameters(): PlaybackParameters {
        return playbackParameters
    }

    override fun stop() {
        startNewLoad(index = null)
        mpvLib.command(arrayOf("stop", "keep-playlist"))
    }

    /**
     * Releases the player. This method must be called when the player is no longer required. The
     * player must not be used after calling this method.
     */
    override fun release() {
        if (released) return
        applyFrameRateToSurface(currentSurface, 0f)
        // Detach while mpv still exists; surfaceDestroyed comes later and is a no-op then.
        if (currentSurface != null) detachVideoSurface()
        currentSurface = null
        if (handleAudioFocus) {
            AudioManagerCompat.abandonAudioFocusRequest(audioManager, audioFocusRequest)
        }
        setBecomingNoisyRegistered(false)
        resetInternalState()
        playbackState = STATE_IDLE
        currentPlayWhenReady = false
        mpvLib.removeObserver(this)
        mpvLib.removeLogObserver(logObserver)
        // Events mpv posted before removeObserver would otherwise still run, after destroy().
        handler.removeCallbacksAndMessages(null)
        listeners.release()
        released = true
        subtitleExecutor.shutdownNow()
        synchronized(mpvLock) {
            mpvLib.destroy()
        }
    }

    private fun detachVideoSurface() {
        // #460: deselect video first. With vo=null and video still selected, mpv re-creates the
        // hardware decoder in copy mode for a picture nobody sees; on the emulator's decoder that
        // new codec never answered, and every later libmpv call on the main thread waited on it.
        selectTrack(MPVTrackType.VIDEO, "no")
        Timber.d("Surface detached: vid=no, vo=null")
        mpvLib.setOptionString("vo", "null")
        mpvLib.setOptionString("force-window", "no")
        mpvLib.detachSurface()
    }

    override fun getCurrentTracks(): Tracks {
        return currentTracks
    }

    override fun getTrackSelectionParameters(): TrackSelectionParameters {
        return trackSelectionParameters
    }

    override fun setTrackSelectionParameters(parameters: TrackSelectionParameters) {
        trackSelectionParameters = parameters

        // Disabled track types
        val disabledTrackTypes =
            parameters.disabledTrackTypes.map { MPVTrackType.fromMedia3TrackType(it) }

        // Overrides
        val notOverriddenTypes =
            mutableSetOf(MPVTrackType.VIDEO, MPVTrackType.AUDIO, MPVTrackType.SUBTITLE)
        // #460: without a surface, video stays deselected (see detachVideoSurface) until
        // surfaceCreated selects it from these parameters.
        if (currentSurface == null) notOverriddenTypes.remove(MPVTrackType.VIDEO)
        for (override in parameters.overrides) {
            val trackType = MPVTrackType.fromMedia3TrackType(override.key.type)
            notOverriddenTypes.remove(trackType)
            if (trackType == MPVTrackType.VIDEO && currentSurface == null) continue
            val id = override.key.getFormat(0).id ?: continue

            selectTrack(trackType, id)
        }
        for (notOverriddenType in notOverriddenTypes) {
            if (notOverriddenType in disabledTrackTypes) {
                selectTrack(notOverriddenType, "no")
            } else {
                selectTrack(notOverriddenType, "auto")
            }
        }
    }

    /**
     * Returns the current combined [MediaMetadata], or [MediaMetadata.EMPTY] if not supported.
     *
     * This [MediaMetadata] is a combination of the [MediaItem.mediaMetadata] and the static and
     * dynamic metadata sourced from [Player.Listener.onMediaMetadataChanged].
     */
    override fun getMediaMetadata(): MediaMetadata {
        return currentMediaItem?.mediaMetadata ?: MediaMetadata.EMPTY
    }

    override fun getPlaylistMetadata(): MediaMetadata {
        return MediaMetadata.EMPTY
    }

    override fun setPlaylistMetadata(mediaMetadata: MediaMetadata) {
        // Not supported; COMMAND_SET_PLAYLIST_METADATA is not advertised.
        Timber.w("MPVPlayer does not support playlist metadata")
    }

    /**
     * Returns the current [Timeline]. Never null, but may be empty.
     *
     * @see Player.Listener.onTimelineChanged
     */
    override fun getCurrentTimeline(): Timeline {
        return timeline
    }

    /** Returns the index of the period currently being played. */
    override fun getCurrentPeriodIndex(): Int {
        return currentMediaItemIndex
    }

    override fun getCurrentMediaItemIndex(): Int {
        return currentMediaItemIndex
    }

    /**
     * Returns the duration of the current content window or ad in milliseconds, or
     * [ ][C.TIME_UNSET] if the duration is not known.
     */
    override fun getDuration(): Long {
        return timeline.getWindow(currentMediaItemIndex, window).durationMs
    }

    /**
     * Returns the playback position in the current content window or ad, in milliseconds, or the
     * prospective position in milliseconds if the [current timeline][.getCurrentTimeline] is empty.
     */
    override fun getCurrentPosition(): Long {
        // Before mpv's first time-pos, report the pending start position like ExoPlayer does,
        // never TIME_UNSET: the media session and the seek-by-offset methods use it as is.
        return (currentPositionMs ?: initialSeekTo).coerceAtLeast(0L)
    }

    /**
     * Returns an estimate of the position in the current content window or ad up to which data is
     * buffered, in milliseconds.
     */
    override fun getBufferedPosition(): Long {
        return currentCacheDurationMs ?: contentPosition
    }

    /**
     * Returns an estimate of the total buffered duration from the current position, in
     * milliseconds. This includes pre-buffered data for subsequent ads and windows.
     */
    override fun getTotalBufferedDuration(): Long {
        return bufferedPosition
    }

    /** Returns whether the player is currently playing an ad. */
    override fun isPlayingAd(): Boolean {
        return false
    }

    /**
     * If [.isPlayingAd] returns true, returns the index of the ad group in the period currently
     * being played. Returns [C.INDEX_UNSET] otherwise.
     */
    override fun getCurrentAdGroupIndex(): Int {
        return C.INDEX_UNSET
    }

    /**
     * If [.isPlayingAd] returns true, returns the index of the ad in its ad group. Returns
     * [C.INDEX_UNSET] otherwise.
     */
    override fun getCurrentAdIndexInAdGroup(): Int {
        return C.INDEX_UNSET
    }

    /**
     * If [.isPlayingAd] returns `true`, returns the content position that will be played once all
     * ads in the ad group have finished playing, in milliseconds. If there is no ad playing, the
     * returned position is the same as that returned by [.getCurrentPosition].
     */
    override fun getContentPosition(): Long {
        return currentPosition
    }

    /**
     * If [.isPlayingAd] returns `true`, returns an estimate of the content position in the current
     * content window up to which data is buffered, in milliseconds. If there is no ad playing, the
     * returned position is the same as that returned by [.getBufferedPosition].
     */
    override fun getContentBufferedPosition(): Long {
        return bufferedPosition
    }

    /** Returns the attributes for audio playback. */
    override fun getAudioAttributes(): AudioAttributes {
        return AudioAttributes.DEFAULT
    }

    /**
     * Sets the audio volume, with 0 being silence and 1 being unity gain (signal unchanged).
     *
     * @param audioVolume Linear output gain to apply to all audio channels.
     */
    override fun setVolume(audioVolume: Float) {
        // Not supported; COMMAND_SET_VOLUME is not advertised.
        Timber.w("MPVPlayer does not support setVolume")
    }

    /**
     * Returns the audio volume, with 0 being silence and 1 being unity gain (signal unchanged).
     *
     * @return The linear gain applied to all audio channels.
     */
    override fun getVolume(): Float {
        return mpvLib.getPropertyInt("volume")?.div(100f) ?: 0f
    }

    /**
     * Clears any [Surface], [SurfaceHolder], [SurfaceView] or [TextureView] currently set on the
     * player.
     */
    override fun clearVideoSurface() {
        // Only SurfaceView and SurfaceHolder are supported, see setVideoSurfaceHolder.
        Timber.w("MPVPlayer does not support clearVideoSurface")
    }

    /**
     * Clears the [Surface] onto which video is being rendered if it matches the one passed. Else
     * does nothing.
     *
     * @param surface The surface to clear.
     */
    override fun clearVideoSurface(surface: Surface?) {
        // Only SurfaceView and SurfaceHolder are supported, see setVideoSurfaceHolder.
        Timber.w("MPVPlayer does not support clearVideoSurface")
    }

    /**
     * Sets the [Surface] onto which video will be rendered. The caller is responsible for tracking
     * the lifecycle of the surface, and must clear the surface by calling `setVideoSurface(null)`
     * if the surface is destroyed.
     *
     * If the surface is held by a [SurfaceView], [TextureView] or [ ] then it's recommended to use
     * [.setVideoSurfaceView], [ ][.setVideoTextureView] or [.setVideoSurfaceHolder] rather than
     * this method, since passing the holder allows the player to track the lifecycle of the surface
     * automatically.
     *
     * @param surface The [Surface].
     */
    override fun setVideoSurface(surface: Surface?) {
        // Only SurfaceView and SurfaceHolder are supported, see setVideoSurfaceHolder.
        Timber.w("MPVPlayer does not support setVideoSurface, no video will show")
    }

    /**
     * Sets the [SurfaceHolder] that holds the [Surface] onto which video will be rendered. The
     * player will track the lifecycle of the surface automatically.
     *
     * @param surfaceHolder The surface holder.
     */
    override fun setVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        if (released) return
        surfaceHolder?.addCallback(this.surfaceHolder)
    }

    /**
     * Clears the [SurfaceHolder] that holds the [Surface] onto which video is being rendered if it
     * matches the one passed. Else does nothing.
     *
     * @param surfaceHolder The surface holder to clear.
     */
    override fun clearVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        surfaceHolder?.removeCallback(this.surfaceHolder)
    }

    /**
     * Sets the [SurfaceView] onto which video will be rendered. The player will track the lifecycle
     * of the surface automatically.
     *
     * @param surfaceView The surface view.
     */
    override fun setVideoSurfaceView(surfaceView: SurfaceView?) {
        if (released) return
        surfaceView?.holder?.addCallback(surfaceHolder)
    }

    /**
     * Clears the [SurfaceView] onto which video is being rendered if it matches the one passed.
     * Else does nothing.
     *
     * @param surfaceView The texture view to clear.
     */
    override fun clearVideoSurfaceView(surfaceView: SurfaceView?) {
        surfaceView?.holder?.removeCallback(surfaceHolder)
    }

    /**
     * Sets the [TextureView] onto which video will be rendered. The player will track the lifecycle
     * of the surface automatically.
     *
     * @param textureView The texture view.
     */
    override fun setVideoTextureView(textureView: TextureView?) {
        // Not supported. COMMAND_SET_VIDEO_SURFACE stays advertised because PlayerView only
        // calls setVideoSurfaceView with it, so a PlayerView with surface_type="texture_view"
        // gets no video instead of a crash.
        Timber.w("MPVPlayer does not support TextureView, no video will show")
    }

    /**
     * Clears the [TextureView] onto which video is being rendered if it matches the one passed.
     * Else does nothing.
     *
     * @param textureView The texture view to clear.
     */
    override fun clearVideoTextureView(textureView: TextureView?) {
        // Not supported, see setVideoTextureView.
    }

    /**
     * Gets the size of the video.
     *
     * The video's width and height are `0` if there is no video or its size has not been determined
     * yet.
     *
     * @see Player.Listener.onVideoSizeChanged
     */
    override fun getVideoSize(): VideoSize {
        val width = mpvLib.getPropertyInt("width")
        val height = mpvLib.getPropertyInt("height")
        if (width == null || height == null) return VideoSize.UNKNOWN
        return VideoSize(width, height)
    }

    override fun getSurfaceSize(): Size {
        val mpvSize =
            mpvLib.getPropertyString("android-surface-size")?.split("x") ?: return Size.UNKNOWN
        return try {
            Size(mpvSize[0].toInt(), mpvSize[1].toInt())
        } catch (_: IndexOutOfBoundsException) {
            Size.UNKNOWN
        }
    }

    /** Returns the current [CueGroup]. This list may be empty. */
    override fun getCurrentCues(): CueGroup {
        return CueGroup(emptyList(), 0)
    }

    /** Gets the device information. */
    override fun getDeviceInfo(): DeviceInfo {
        return DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_LOCAL)
            .setMaxVolume(0)
            .setMaxVolume(100)
            .build()
    }

    /**
     * Gets the current volume of the device.
     *
     * For devices with [local playback][DeviceInfo.PLAYBACK_TYPE_LOCAL], the volume returned by
     * this method varies according to the current [stream type][C.StreamType]. The stream type is
     * determined by [AudioAttributes.usage] which can be converted to stream type with
     * [Util.getStreamTypeForAudioUsage].
     *
     * For devices with [remote playback][DeviceInfo.PLAYBACK_TYPE_REMOTE], the volume of the remote
     * device is returned.
     */
    override fun getDeviceVolume(): Int {
        return mpvLib.getPropertyInt("volume") ?: 0
    }

    /** Gets whether the device is muted or not. */
    override fun isDeviceMuted(): Boolean {
        return mpvLib.getPropertyBoolean("mute") ?: false
    }

    /**
     * Sets the volume of the device.
     *
     * @param volume The volume to set.
     */
    @Deprecated("Deprecated in Java")
    override fun setDeviceVolume(volume: Int) {
        throw IllegalArgumentException(
            "You should use global volume controls. Check out AUDIO_SERVICE."
        )
    }

    override fun setDeviceVolume(volume: Int, flags: Int) {
        mpvLib.setPropertyInt("volume", volume)
    }

    /** Increases the volume of the device. */
    @Deprecated("Deprecated in Java")
    override fun increaseDeviceVolume() {
        throw IllegalArgumentException(
            "You should use global volume controls. Check out AUDIO_SERVICE."
        )
    }

    override fun increaseDeviceVolume(flags: Int) {
        throw IllegalArgumentException(
            "You should use global volume controls. Check out AUDIO_SERVICE."
        )
    }

    /** Decreases the volume of the device. */
    @Deprecated("Deprecated in Java")
    override fun decreaseDeviceVolume() {
        throw IllegalArgumentException(
            "You should use global volume controls. Check out AUDIO_SERVICE."
        )
    }

    override fun decreaseDeviceVolume(flags: Int) {
        throw IllegalArgumentException(
            "You should use global volume controls. Check out AUDIO_SERVICE."
        )
    }

    /** Sets the mute state of the device. */
    @Deprecated("Deprecated in Java")
    override fun setDeviceMuted(muted: Boolean) {
        throw IllegalArgumentException(
            "You should use global volume controls. Check out AUDIO_SERVICE."
        )
    }

    override fun setDeviceMuted(muted: Boolean, flags: Int) {
        return mpvLib.setPropertyBoolean("mute", muted)
    }

    override fun mute() {
        mpvLib.setPropertyBoolean("mute", true)
    }

    override fun unmute() {
        mpvLib.setPropertyBoolean("mute", false)
    }

    override fun setAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean) {
        // Not supported; the attributes are fixed by the builder.
        Timber.w("MPVPlayer does not support setAudioAttributes")
    }


    /**
     * Hints the surface with the video frame rate so the OS can pick a matching display rate,
     * as ExoPlayer does by default (fixed source: an exact match or a multiple, so the OS does
     * not settle on a rate like 20Hz for 23.976fps). Seamless switches only (a 120Hz phone panel
     * dropping to 48Hz for 24fps, #338) unless the TV "match refresh rate" setting is on, which
     * also allows a visible mode switch.
     */
    private fun applyFrameRateToSurface(surface: Surface?, frameRate: Float) {
        if (surface == null || !surface.isValid) return
        Timber.d("Surface frame rate hint: ${frameRate}fps (force=$matchRefreshRate)")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val strategy =
                        if (matchRefreshRate) Surface.CHANGE_FRAME_RATE_ALWAYS
                        else Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                    surface.setFrameRate(
                        frameRate,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                        strategy,
                    )
                } else {
                    surface.setFrameRate(frameRate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to set surface frame rate")
            }
        }
    }

    /** CE (#300, #308): applies [options] from [mpvSubtitleOptions] to the playing subtitles. */
    fun updateSubtitleOptions(options: Map<String, String>) {
        options.forEach { (name, value) -> mpvLib.setOptionString(name, value) }
    }

    fun updateZoomMode(enabled: Boolean) {
        if (enabled) {
            mpvLib.setOptionString("panscan", "1")
            mpvLib.setOptionString("sub-use-margins", "yes")
            mpvLib.setOptionString("sub-ass-force-margins", "yes")
        } else {
            mpvLib.setOptionString("panscan", "0")
            mpvLib.setOptionString("sub-use-margins", "no")
            mpvLib.setOptionString("sub-ass-force-margins", "no")
        }
    }

    private val surfaceHolder: SurfaceHolder.Callback =
        object : SurfaceHolder.Callback {
            /**
             * This is called immediately after the surface is first created. Implementations of
             * this should start up whatever rendering code they desire. Note that only one thread
             * can ever draw into a [Surface], so you should not draw into the Surface here if your
             * normal rendering will be in another thread.
             *
             * @param holder The SurfaceHolder whose surface is being created.
             */
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (released) return
                currentSurface = holder.surface
                if (currentFrameRate > 0f) {
                    applyFrameRateToSurface(holder.surface, currentFrameRate)
                }
                mpvLib.attachSurface(holder.surface)
                mpvLib.setOptionString("force-window", "yes")
                mpvLib.setOptionString("vo", videoOutput)
                // #460: detaching deselected video; select what the parameters ask for again.
                val vid = videoTrackSelection(trackSelectionParameters)
                selectTrack(MPVTrackType.VIDEO, vid)
                Timber.d("Surface attached: vo=$videoOutput, vid=$vid")
            }

            /**
             * This is called immediately after any structural changes (format or size) have been
             * made to the surface. You should at this point update the imagery in the surface. This
             * method is always called at least once, after [.surfaceCreated].
             *
             * @param holder The SurfaceHolder whose surface has changed.
             * @param format The new [android.graphics.PixelFormat] of the surface.
             * @param width The new width of the surface.
             * @param height The new height of the surface.
             */
            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) {
                if (released) return
                mpvLib.setPropertyString("android-surface-size", "${width}x$height")
            }

            /**
             * This is called immediately before a surface is being destroyed. After returning from
             * this call, you should no longer try to access this surface. If you have a rendering
             * thread that directly accesses the surface, you must ensure that thread is no longer
             * touching the Surface before returning from this function.
             *
             * @param holder The SurfaceHolder whose surface is being destroyed.
             */
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // release() detached the surface already.
                if (released) return
                applyFrameRateToSurface(holder.surface, 0f)
                currentSurface = null
                detachVideoSurface()
            }
        }

    companion object {
        /** Fraction to which audio volume is ducked on loss of audio focus */
        private const val AUDIO_FOCUS_DUCKING = 0.5f

        private val permanentAvailableCommands: Commands =
            Commands.Builder()
                .addAll(
                    COMMAND_PLAY_PAUSE,
                    // #474: Play after an error loads the item again, and the player waits for
                    // the network after a lost connection (NetworkRecovery).
                    COMMAND_PREPARE,
                    COMMAND_SET_SPEED_AND_PITCH,
                    COMMAND_GET_CURRENT_MEDIA_ITEM,
                    // Without it the media session publishes no queue.
                    COMMAND_GET_TIMELINE,
                    COMMAND_GET_METADATA,
                    COMMAND_GET_AUDIO_ATTRIBUTES,
                    // Not COMMAND_CHANGE_MEDIA_ITEMS: move and replace are not supported. The app
                    // calls addMediaItems directly, which does not check commands.
                    COMMAND_SET_VIDEO_SURFACE,
                    COMMAND_GET_TRACKS,
                    COMMAND_SET_TRACK_SELECTION_PARAMETERS,
                    COMMAND_RELEASE,
                )
                .build()

        private fun JSONObject.optNullableString(name: String): String? {
            return if (this.has(name) && !this.isNull(name)) {
                this.getString(name)
            } else {
                null
            }
        }

        private fun JSONObject.optNullableDouble(name: String): Double? {
            return if (this.has(name) && !this.isNull(name)) {
                this.getDouble(name)
            } else {
                null
            }
        }

        private fun createTracksGroupfromMpvJson(
            json: JSONObject,
            forcedSubtitleFiles: Set<String>,
        ): Tracks.Group {
            val trackType = MPVTrackType.entries.first { it.type == json.optString("type") }

            // CE (#597): mpv names a file added without a title after the file, and the "file" of
            // a subtitle the server delivers is an address with the access key in it. Such a track
            // has no title.
            val externalFile = json.optNullableString("external-filename")
            val title =
                json.optNullableString("title")?.takeUnless {
                    externalFile != null && externalFile.endsWith(it)
                }

            // Base format shared between video, audio and subtitles
            val baseFormat =
                Format.Builder()
                    .setId(json.optInt("id"))
                    .setLabel(title)
                    .setLanguage(json.optNullableString("lang"))
                    .setSelectionFlags(
                        (if (json.optBoolean("default")) C.SELECTION_FLAG_DEFAULT else 0) or
                            // CE (#451): the track dialog marks forced subtitles, as on ExoPlayer.
                            // CE (#597): or a subtitle file added as forced, which mpv cannot
                            // be told.
                            (if (
                                json.optBoolean("forced") || externalFile in forcedSubtitleFiles
                            ) {
                                C.SELECTION_FLAG_FORCED
                            } else {
                                0
                            })
                    )
                    .setCodecs(json.optNullableString("codec"))
                    .build()

            // Video, audio and subtitle specific values
            val format =
                when (trackType) {
                    MPVTrackType.VIDEO -> {
                        baseFormat
                            .buildUpon()
                            .setSampleMimeType("video/${baseFormat.codecs}")
                            .setWidth(json.optInt("demux-w", Format.NO_VALUE))
                            .setHeight(json.optInt("demux-h", Format.NO_VALUE))
                            .setFrameRate(
                                (json.optNullableDouble("demux-fps") ?: Format.NO_VALUE).toFloat()
                            )
                            .build()
                    }

                    MPVTrackType.AUDIO -> {
                        baseFormat
                            .buildUpon()
                            .setSampleMimeType("audio/${baseFormat.codecs}")
                            .setChannelCount(json.optInt("demux-channel-count", Format.NO_VALUE))
                            .setSampleRate(json.optInt("demux-samplerate", Format.NO_VALUE))
                            .build()
                    }

                    MPVTrackType.SUBTITLE -> {
                        baseFormat
                            .buildUpon()
                            .setSampleMimeType("text/${baseFormat.codecs}")
                            .build()
                    }
                }

            val trackGroup = TrackGroup(format)

            return Tracks.Group(
                trackGroup,
                false,
                IntArray(trackGroup.length) { C.FORMAT_HANDLED },
                BooleanArray(trackGroup.length) { json.optBoolean("selected") },
            )
        }

        private fun getTracks(trackList: String, forcedSubtitleFiles: Set<String>): Tracks {
            var tracks = Tracks.EMPTY
            val trackGroups = mutableListOf<Tracks.Group>()
            try {
                val currentTrackList = JSONArray(trackList)
                for (index in 0 until currentTrackList.length()) {
                    val tracksGroup =
                        createTracksGroupfromMpvJson(
                            currentTrackList.getJSONObject(index),
                            forcedSubtitleFiles,
                        )
                    trackGroups.add(tracksGroup)
                }
                if (trackGroups.isNotEmpty()) {
                    tracks = Tracks(trackGroups)
                }
            } catch (_: JSONException) {}
            return tracks
        }
    }
}
