package dev.jdtech.jellyfin.models

import dev.jdtech.jellyfin.repository.JellyfinRepository
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.VideoRangeType

data class FindroidMediaStream(
    val title: String,
    val displayTitle: String?,
    val language: String,
    val type: MediaStreamType,
    val codec: String,
    val profile: String?,
    val isExternal: Boolean,
    val path: String?,
    val channelLayout: String?,
    val videoRangeType: VideoRangeType?,
    val height: Int?,
    val width: Int?,
    val videoDoViTitle: String?,
    // CE (#510): what the media info sheet shows. Not stored with a download, so a local
    // stream has none of it.
    val bitRate: Int? = null,
    val frameRate: Float? = null,
    val bitDepth: Int? = null,
    val sampleRate: Int? = null,
    /** CE (#580): the server's number for the stream, which a playback request names. */
    val index: Int? = null,
    /** CE (#596): the channel count of an audio stream, for its name in the audio dialog. */
    val channels: Int? = null,
    /**
     * CE (#597): how the server delivers a subtitle stream for the source it is listed with.
     * [SubtitleDeliveryMethod.EXTERNAL] with a [path] is a file of its own, also for a track that
     * is inside the video file: the server extracts it.
     */
    val deliveryMethod: SubtitleDeliveryMethod? = null,
    val isForced: Boolean = false,
    val isDefault: Boolean = false,
)

fun MediaStream.toFindroidMediaStream(jellyfinRepository: JellyfinRepository): FindroidMediaStream {
    return FindroidMediaStream(
        title = title.orEmpty(),
        displayTitle = displayTitle,
        language = language.orEmpty(),
        type = type,
        codec = codec.orEmpty(),
        profile = profile,
        isExternal = isExternal,
        path = deliveryUrl?.let { serverUrl(jellyfinRepository.getBaseUrl(), it) },
        channelLayout = channelLayout,
        videoRangeType = videoRangeType,
        height = height,
        width = width,
        videoDoViTitle = videoDoViTitle,
        bitRate = bitRate,
        // The average is what the server puts in the display title; the "real" rate is the
        // stream's time base and can be far off for variable frame rate video.
        frameRate = averageFrameRate ?: realFrameRate,
        bitDepth = bitDepth,
        sampleRate = sampleRate,
        index = index,
        channels = channels,
        deliveryMethod = deliveryMethod,
        isForced = isForced,
        isDefault = isDefault,
    )
}

fun FindroidMediaStreamDto.toFindroidMediaStream(): FindroidMediaStream {
    return FindroidMediaStream(
        title = title,
        displayTitle = displayTitle,
        language = language,
        type = type,
        codec = codec,
        profile = profile,
        isExternal = isExternal,
        path = path,
        channelLayout = channelLayout,
        videoRangeType = VideoRangeType.fromNameOrNull(videoRangeType ?: ""),
        height = height,
        width = width,
        videoDoViTitle = videoDoViTitle,
    )
}
