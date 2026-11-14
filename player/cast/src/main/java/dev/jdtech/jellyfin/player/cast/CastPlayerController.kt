package dev.jdtech.jellyfin.player.cast

import dev.jdtech.jellyfin.player.cast.models.CastError
import dev.jdtech.jellyfin.player.cast.models.CastMediaItem
import dev.jdtech.jellyfin.player.cast.models.CastPlayerState
import dev.jdtech.jellyfin.player.core.domain.models.Track
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface CastPlayerController {
    val currentItem: StateFlow<CastMediaItem?>
    val playerState: StateFlow<CastPlayerState>

    /** Playback that could not start or go on, for a message to the user. */
    val errors: Flow<CastError>

    /**
     * Plays [itemId] on the receiver, with the rest of its playlist queued behind it.
     * [mediaSourceId] is the version the user chose, `null` for the default one. CE (#638): with
     * [shuffle], a series or season plays in random order.
     */
    fun playItem(
        itemId: UUID,
        itemKind: String,
        startFromBeginning: Boolean,
        mediaSourceId: String? = null,
        shuffle: Boolean = false,
    )

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
