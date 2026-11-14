package dev.jdtech.jellyfin.player.cast

import dev.jdtech.jellyfin.player.cast.models.CastMediaItem
import dev.jdtech.jellyfin.player.core.domain.models.Track
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStreamType

/** Keeps subtitle track ids apart from the audio stream indices the dialogs also use. */
internal const val SUBTITLE_TRACK_ID_OFFSET = 100

/** A subtitle file the receiver is told about. */
internal data class ReceiverTextTrack(
    val id: Long,
    val url: String,
    val language: String?,
    val name: String?,
)

internal data class CastTracks(
    val receiverTracks: List<ReceiverTextTrack>,
    val subtitles: List<Track>,
    val audio: List<Track>,
) {
    /** The tracks the receiver shows from the start. */
    val activeTrackIds: List<Long>
        get() = subtitles.filter { it.selected && it.supported }.map { it.id.toLong() }
}

/** The id of the subtitle track the server picks for this user, `null` when it picks none. */
internal fun defaultSubtitleTrackId(mediaSource: MediaSourceInfo): Int? =
    mediaSource.defaultSubtitleStreamIndex
        ?.takeIf { it >= 0 }
        ?.let { it + SUBTITLE_TRACK_ID_OFFSET }

/**
 * The tracks of [mediaSource], for the receiver and for the track dialogs. [audioStreamIndex] is
 * the audio stream that plays (`null`: the source's default), [subtitleTrackId] the subtitle track
 * that shows (`null`: none). Subtitle track ids are the stream index plus
 * [SUBTITLE_TRACK_ID_OFFSET]; the receiver gets only those the server delivers as a file.
 */
internal fun castTracks(
    mediaSource: MediaSourceInfo,
    baseUrl: String,
    audioStreamIndex: Int?,
    subtitleTrackId: Int?,
): CastTracks {
    val receiverTracks = mutableListOf<ReceiverTextTrack>()
    val subtitles = mutableListOf<Track>()
    val audio = mutableListOf<Track>()

    val streams = mediaSource.mediaStreams.orEmpty()
    val playingAudioIndex =
        audioStreamIndex
            ?: mediaSource.defaultAudioStreamIndex
            ?: streams.firstOrNull { it.type == MediaStreamType.AUDIO }?.index

    streams.forEach { stream ->
        if (stream.type == MediaStreamType.SUBTITLE) {
            val trackId = stream.index + SUBTITLE_TRACK_ID_OFFSET
            val trackUrl = stream.deliveryUrl?.let { receiverUrl(baseUrl, it) }

            if (trackUrl != null) {
                receiverTracks.add(
                    ReceiverTextTrack(
                        id = trackId.toLong(),
                        url = trackUrl,
                        language = stream.language,
                        name = stream.title ?: stream.displayTitle,
                    )
                )
            }

            subtitles.add(
                Track(
                    id = trackId,
                    label = stream.title ?: stream.displayTitle,
                    language = stream.language,
                    codec = stream.codec,
                    selected = trackUrl != null && trackId == subtitleTrackId,
                    supported = trackUrl != null,
                    isExternal = stream.isExternal,
                    isForced = stream.isForced,
                    isHearingImpaired = stream.isHearingImpaired,
                    isDefault = stream.isDefault,
                )
            )
        } else if (stream.type == MediaStreamType.AUDIO) {
            audio.add(
                Track(
                    id = stream.index,
                    label = stream.title ?: stream.displayTitle,
                    language = stream.language,
                    codec = stream.codec,
                    selected = stream.index == playingAudioIndex,
                    supported = true,
                    isExternal = stream.isExternal,
                    isForced = stream.isForced,
                    isHearingImpaired = stream.isHearingImpaired,
                    isDefault = stream.isDefault,
                    channelLayout = stream.channelLayout,
                )
            )
        }
    }

    return CastTracks(receiverTracks, subtitles, audio)
}

/** This item with [trackIds] as the subtitle tracks that show. */
internal fun CastMediaItem.withSubtitle(trackIds: Collection<Long>): CastMediaItem =
    copy(subtitleTracks = subtitleTracks.map { it.copy(selected = it.id.toLong() in trackIds) })

/** This item with the audio track [trackId] as the one that plays. */
internal fun CastMediaItem.withAudio(trackId: Int): CastMediaItem =
    copy(audioTracks = audioTracks.map { it.copy(selected = it.id == trackId) })
