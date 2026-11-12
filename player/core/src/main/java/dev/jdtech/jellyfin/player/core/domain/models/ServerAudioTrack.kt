package dev.jdtech.jellyfin.player.core.domain.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * CE (#596): an audio stream of the file as the server lists it. A transcode carries one of them,
 * so the audio dialog lists these instead of the player's tracks, and picking one asks the server
 * for a new stream by [index].
 */
@Parcelize
data class ServerAudioTrack(
    /** The server's number for the stream, which a playback request names. */
    val index: Int,
    val title: String?,
    val language: String?,
    val codec: String?,
    val channels: Int?,
) : Parcelable
