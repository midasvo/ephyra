package dev.jdtech.jellyfin.player.local.engine.exo

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput
import dev.jdtech.jellyfin.models.PlayerEngineId
import dev.jdtech.jellyfin.player.local.engine.EngineDefaults
import dev.jdtech.jellyfin.player.local.engine.EnginePlayer
import dev.jdtech.jellyfin.player.local.engine.PlayerEngine
import dev.jdtech.jellyfin.player.local.engine.PlayerEngineConfig
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Inject

/** ExoPlayer, with the platform decoders first and ffmpeg behind them (#764). */
class ExoEngine
@Inject
constructor(private val application: Application, private val appPreferences: AppPreferences) :
    PlayerEngine {
    override val id = PlayerEngineId.EXOPLAYER

    override fun create(config: PlayerEngineConfig): EnginePlayer {
        val trackSelector = EngineDefaults.trackSelector(application, appPreferences)
        val audioDecoderFallback =
            AudioDecoderFallback(
                build = Build.FINGERPRINT,
                broken = appPreferences.getValue(appPreferences.playerBrokenAudioDecoders),
                isTv = application.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
                onBrokenChanged = {
                    appPreferences.setValue(appPreferences.playerBrokenAudioDecoders, it)
                },
            )
        val audioAttributes = EngineDefaults.audioAttributes()

        // ON (not PREFER) so the platform MediaCodec decodes audio ahead of the
        // bundled ffmpeg extension: only the platform pipeline does Atmos spatial
        // audio and E-AC-3/Atmos passthrough over HDMI, and it costs less battery.
        // ffmpeg always decodes to PCM. It picks up what no platform decoder
        // handles, and what a broken one fails on, see AudioDecoderFallback.
        val renderersFactory =
            object : DefaultRenderersFactory(application) {
                    override fun buildTextRenderers(
                        context: Context,
                        output: TextOutput,
                        outputLooper: Looper,
                        extensionRendererMode: Int,
                        out: ArrayList<Renderer>,
                    ) {
                        val startIndex = out.size
                        super.buildTextRenderers(
                            context,
                            output,
                            outputLooper,
                            extensionRendererMode,
                            out,
                        )
                        for (index in startIndex until out.size) {
                            out[index] =
                                SubtitleOffsetRenderer(
                                    baseRenderer = out[index],
                                    subtitleDelayUsProvider = config.subtitleDelayUs,
                                    itemDurationUsProvider = config.itemDurationUs,
                                )
                        }
                    }
                }
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setMediaCodecSelector(audioDecoderFallback)
        // Keep ExoPlayer's default frame rate strategy (ONLY_IF_SEAMLESS): it hints
        // the surface with the video frame rate so the OS can drop a 120Hz phone
        // panel to a multiple of 24fps, as upstream does. Forcing it OFF when the
        // TV-only "match refresh rate" setting is unset left phones at their
        // maximum rate for the whole playback (#338). The TV setting only drives
        // the non-seamless mode switch in DisplayRefreshRateManager.
        val player =
            ExoPlayer.Builder(application, renderersFactory)
                .setAudioAttributes(audioAttributes, true)
                .setHandleAudioBecomingNoisy(true)
                .setMediaSourceFactory(
                    SubtitleDelayMediaSource.Factory(
                        buildMediaSourceFactory(application, config.httpClient),
                        config.subtitleDelayUsFor,
                    )
                )
                .setTrackSelector(trackSelector)
                .setSeekBackIncrementMs(appPreferences.getValue(appPreferences.playerSeekBackInc))
                .setSeekForwardIncrementMs(
                    appPreferences.getValue(appPreferences.playerSeekForwardInc)
                )
                .setPauseAtEndOfMediaItems(true)
                .build()
        val tunnelingFallback =
            TunnelingFallback(
                tunnelingEnabled = { trackSelector.parameters.tunnelingEnabled },
                setTunnelingEnabled = { enabled ->
                    trackSelector.setParameters(
                        trackSelector.buildUponParameters().setTunnelingEnabled(enabled)
                    )
                },
            )
        return ExoEnginePlayer(player, audioDecoderFallback, tunnelingFallback)
    }
}

private class ExoEnginePlayer(
    override val player: Player,
    private val audioDecoderFallback: AudioDecoderFallback,
    private val tunnelingFallback: TunnelingFallback,
) : EnginePlayer {
    init {
        player.addListener(
            object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    tunnelingFallback.onMediaItemTransition(mediaItem?.mediaId)
                }
            }
        )
    }

    /** [SubtitleOffsetRenderer] reads the delay through [PlayerEngineConfig.subtitleDelayUs]. */
    override fun setSubtitleDelayMs(delayMs: Int) = Unit

    override fun onPlayerError(error: PlaybackException): Boolean =
        audioDecoderFallback.onPlayerError(error) ||
            tunnelingFallback.onPlayerError(error.errorCode, player.currentMediaItem?.mediaId)
}
