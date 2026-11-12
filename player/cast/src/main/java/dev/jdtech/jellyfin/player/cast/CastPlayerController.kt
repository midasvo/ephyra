package dev.jdtech.jellyfin.player.cast

import dev.jdtech.jellyfin.player.cast.models.CastMediaItem
import dev.jdtech.jellyfin.player.cast.models.CastPlayerState
import dev.jdtech.jellyfin.player.core.domain.models.Track
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow

interface CastPlayerController {
    val currentItem: StateFlow<CastMediaItem?>
    val playerState: StateFlow<CastPlayerState>

    fun playItem(itemId: UUID, itemKind: String, startFromBeginning: Boolean)

    fun play()

    fun pause()

    fun seekTo(position: Long)

    fun seekToNext()

    fun seekToPrevious()

    fun setVolume(volume: Float)

    fun setAudioTrack(track: Track?, itemId: UUID?)

    fun setSubtitleTrack(track: Track?)

    fun stop()
}
