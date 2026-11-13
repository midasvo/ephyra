package dev.jdtech.jellyfin.player.local.presentation

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream

/**
 * CE (#619): Offsets the playback timestamp fed to ExoPlayer's subtitle [Renderer] so
 * that subtitles are shifted earlier or later without restarting playback or rebuffering.
 *
 * [itemDurationUsProvider] gives the duration of the playing item, or [C.TIME_UNSET].
 */
@OptIn(UnstableApi::class)
class SubtitleOffsetRenderer(
    baseRenderer: Renderer,
    private val subtitleDelayUsProvider: () -> Long,
    private val itemDurationUsProvider: () -> Long,
) : ForwardingRenderer(baseRenderer) {

    private var streamOffsetUs = 0L
    private var positionUs = C.TIME_UNSET

    override fun enable(
        configuration: RendererConfiguration,
        formats: Array<out Format>,
        stream: SampleStream,
        positionUs: Long,
        joining: Boolean,
        mayRenderStartOfStream: Boolean,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        streamOffsetUs = offsetUs
        this.positionUs = C.TIME_UNSET
        super.enable(
            configuration,
            formats,
            stream,
            adjust(positionUs),
            joining,
            mayRenderStartOfStream,
            startPositionUs,
            offsetUs,
            mediaPeriodId,
        )
    }

    override fun replaceStream(
        formats: Array<out Format>,
        stream: SampleStream,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        streamOffsetUs = offsetUs
        super.replaceStream(formats, stream, startPositionUs, offsetUs, mediaPeriodId)
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        // Positive delay means the user wants subtitles to show later in playback,
        // so we subtract delayUs from the query timestamp.
        this.positionUs = positionUs
        super.render(adjust(positionUs), elapsedRealtimeUs)
    }

    override fun resetPosition(positionUs: Long, playing: Boolean) {
        this.positionUs = positionUs
        super.resetPosition(adjust(positionUs), playing)
    }

    /**
     * With a positive delay the shifted position trails the video, and the text renderer reads
     * the end of its stream only when that position reaches the end of the item: the item would
     * stay on its last frame for up to the delay after the video ended. The item ends when the
     * video and audio do; the cues still due then are left out, as with a stop.
     */
    override fun isEnded(): Boolean {
        if (super.isEnded()) return true
        if (subtitleDelayUsProvider() <= 0 || positionUs == C.TIME_UNSET) return false
        val durationUs = itemDurationUsProvider()
        return durationUs != C.TIME_UNSET && positionUs - streamOffsetUs >= durationUs
    }

    override fun supportsResetPositionWithoutKeyFrameReset(positionUs: Long): Boolean =
        super.supportsResetPositionWithoutKeyFrameReset(adjust(positionUs))

    private fun adjust(positionUs: Long): Long =
        (positionUs - subtitleDelayUsProvider()).coerceAtLeast(0L)
}
