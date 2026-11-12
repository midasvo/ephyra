package dev.jdtech.jellyfin.player.local.presentation

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer

/**
 * CE (#619): Offsets the playback timestamp fed to ExoPlayer's subtitle [Renderer] so
 * that subtitles are shifted earlier or later without restarting playback or rebuffering.
 */
@OptIn(UnstableApi::class)
class SubtitleOffsetRenderer(
    baseRenderer: Renderer,
    private val subtitleDelayUsProvider: () -> Long,
) : ForwardingRenderer(baseRenderer) {

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        // Positive delay means the user wants subtitles to show later in playback,
        // so we subtract delayUs from the query timestamp.
        val adjustedPositionUs = (positionUs - subtitleDelayUsProvider()).coerceAtLeast(0L)
        super.render(adjustedPositionUs, elapsedRealtimeUs)
    }

    override fun resetPosition(positionUs: Long, playing: Boolean) {
        val adjustedPositionUs = (positionUs - subtitleDelayUsProvider()).coerceAtLeast(0L)
        super.resetPosition(adjustedPositionUs, playing)
    }

    override fun supportsResetPositionWithoutKeyFrameReset(positionUs: Long): Boolean {
        val adjustedPositionUs = (positionUs - subtitleDelayUsProvider()).coerceAtLeast(0L)
        return super.supportsResetPositionWithoutKeyFrameReset(adjustedPositionUs)
    }
}
