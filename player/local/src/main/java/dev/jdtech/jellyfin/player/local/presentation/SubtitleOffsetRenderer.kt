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
 * The text renderer only moves its output on when the position passes its next cue change, and
 * after a seek its stream starts at the seek position. So when the shifted position goes back (a
 * seek with a delay, or a larger delay), the renderer is reset at the shifted position and its
 * stream, a [SubtitleReplaySampleStream], delivers the cues it already had again. A delay change
 * takes effect on the next render, also while paused: no seek needed.
 *
 * [itemDurationUsProvider] gives the duration of the playing item, or [C.TIME_UNSET].
 */
@OptIn(UnstableApi::class)
class SubtitleOffsetRenderer(
    baseRenderer: Renderer,
    private val subtitleDelayUsProvider: () -> Long,
    private val itemDurationUsProvider: () -> Long,
) : ForwardingRenderer(baseRenderer) {

    private var stream: SubtitleReplaySampleStream? = null
    private var streamOffsetUs = 0L
    private var positionUs = C.TIME_UNSET
    private var appliedDelayUs = 0L

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
        val replayStream = SubtitleReplaySampleStream(stream)
        this.stream = replayStream
        streamOffsetUs = offsetUs
        this.positionUs = C.TIME_UNSET
        appliedDelayUs = subtitleDelayUsProvider()
        super.enable(
            configuration,
            formats,
            replayStream,
            adjust(positionUs, appliedDelayUs),
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
        val replayStream = SubtitleReplaySampleStream(stream)
        this.stream = replayStream
        streamOffsetUs = offsetUs
        super.replaceStream(formats, replayStream, startPositionUs, offsetUs, mediaPeriodId)
    }

    /** The player compares this with the stream it handed over, not with the wrapper. */
    override fun getStream(): SampleStream? = stream?.upstream ?: super.getStream()

    override fun disable() {
        super.disable()
        stream = null
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        this.positionUs = positionUs
        val delayUs = subtitleDelayUsProvider()
        if (delayUs != appliedDelayUs) {
            appliedDelayUs = delayUs
            // Keep a final stream final: the reset clears it, and the item could not end.
            val streamFinal = isCurrentStreamFinal
            rewindStream(adjust(positionUs, delayUs))
            super.resetPosition(adjust(positionUs, delayUs), true)
            if (streamFinal) super.setCurrentStreamFinal()
        }
        // Positive delay means the user wants subtitles to show later in playback,
        // so we subtract delayUs from the query timestamp.
        super.render(adjust(positionUs, delayUs), elapsedRealtimeUs)
    }

    override fun resetPosition(positionUs: Long, sampleStreamIsResetToKeyFrame: Boolean) {
        this.positionUs = positionUs
        appliedDelayUs = subtitleDelayUsProvider()
        // Without a delay the stream starts where the player seeked to, as always.
        if (appliedDelayUs != 0L) rewindStream(adjust(positionUs, appliedDelayUs))
        super.resetPosition(adjust(positionUs, appliedDelayUs), sampleStreamIsResetToKeyFrame)
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
        super.supportsResetPositionWithoutKeyFrameReset(
            adjust(positionUs, subtitleDelayUsProvider())
        )

    /** Replays the cues that may still be showing at [rendererPositionUs]. */
    private fun rewindStream(rendererPositionUs: Long) {
        stream?.rewind(rendererPositionUs - streamOffsetUs - REPLAY_LOOKBACK_US)
    }

    private fun adjust(positionUs: Long, delayUs: Long): Long =
        (positionUs - delayUs).coerceAtLeast(0L)

    private companion object {
        /** How far back a cue may start and still be showing. */
        const val REPLAY_LOOKBACK_US = 30_000_000L
    }
}
