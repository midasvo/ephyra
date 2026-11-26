package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.domain.ports.ActiveServer
import dev.jdtech.jellyfin.domain.ports.PlaybackPort
import dev.jdtech.jellyfin.domain.ports.PlaybackReport
import dev.jdtech.jellyfin.domain.ports.PlaybackRequest
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.MediaSourceId
import dev.jdtech.jellyfin.models.PlaybackTarget
import dev.jdtech.jellyfin.models.PlayerEngineId
import dev.jdtech.jellyfin.models.Segment
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.repository.ResumeThresholds
import dev.jdtech.jellyfin.server.jellyfin.cast.toReceiverSource
import dev.jdtech.jellyfin.server.jellyfin.mappers.toItemSource
import dev.jdtech.jellyfin.server.jellyfin.mappers.toSegment
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.serializer.toUUIDOrNull

/**
 * [PlaybackPort] over the Jellyfin SDK (#757). No Room: the repository answers with a download
 * before it asks here. Throws [dev.jdtech.jellyfin.models.ServerException] for every failure the
 * SDK reports as one (see [asServerException]); anything else leaves as it is.
 *
 * #899: with a [pin], every call and URL is of that value's server and user, whatever is switched
 * to meanwhile; without one, of the holder's value at each read.
 */
class JellyfinPlayback(
    private val api: JellyfinApi,
    private val deviceProfiles: JellyfinDeviceProfiles,
    private val pin: ActiveServer? = null,
) : PlaybackPort {
    /** #899: every call of this port goes through here, held to [pin]. */
    private suspend fun <T> call(block: suspend CoroutineScope.() -> T): T =
        api.pinnedCall(pin, block)

    override suspend fun sources(request: PlaybackRequest): List<ItemSource> = call {
        val playbackInfo =
            api.mediaInfoApi
                .getPostedPlaybackInfo(request.itemId.toUuid(), playbackInfoOf(request))
                .content
        // CE (#728): each transcode carries its play session; the player records it when it loads
        // the stream (setPlaySession), not here: a switch that fails keeps the old one. A download
        // is fetched once and has no session to end.
        val playSessionId =
            playbackInfo.playSessionId
                ?.takeIf { it.isNotBlank() }
                ?.takeIf { request.target !is PlaybackTarget.Download }
        playbackInfo.mediaSources.map {
            val source =
                if (request.target is PlaybackTarget.CastReceiver) {
                    it.toReceiverSource(api.mapperContext(), request.itemId, api.api.accessToken)
                } else {
                    it.toItemSource(api.mapperContext(), request.itemId, request.includePath)
                }
            if (source.transcoded && playSessionId != null) {
                source.copy(playSessionId = playSessionId)
            } else {
                source
            }
        }
    }

    /**
     * The body of the playback info request. The Jellyfin fields that follow from the target and
     * the cap (the profile, transcoding, stream copies, the live stream) are derived here.
     */
    private fun playbackInfoOf(request: PlaybackRequest): PlaybackInfoDto {
        val maxBitrate = request.maxBitrate
        val capped = maxBitrate != null
        return when (val target = request.target) {
            is PlaybackTarget.LocalPlayer -> {
                // mpv software-decodes practically everything, so it stays on the permissive
                // direct-play profile. ExoPlayer gets the honest, hardware-probed profile so the
                // server transcodes anything it cannot direct-play, such as Dolby Vision without a
                // DV decoder (JellyfinDeviceProfiles.profileFor). A cap transcodes files above it;
                // files below it still direct-play. mpv's plain profile has no transcoding
                // profiles, so a cap switches it to the variant that carries the HLS fallback.
                val deviceProfile =
                    deviceProfiles.profileFor(
                        target,
                        bitrateCapped = capped,
                        decoders = request.decoders,
                    )
                val enableTranscoding = target.engine != PlayerEngineId.MPV || capped
                PlaybackInfoDto(
                    userId = api.currentUserId,
                    deviceProfile = deviceProfile,
                    maxStreamingBitrate = maxBitrate ?: deviceProfile.maxStreamingBitrate,
                    enableTranscoding = enableTranscoding,
                    allowVideoStreamCopy = true,
                    allowAudioStreamCopy = true,
                    autoOpenLiveStream = enableTranscoding,
                    // CE (#580): the server ignores the index without the source's id.
                    mediaSourceId =
                        request.mediaSourceId?.asString().takeIf {
                            request.audioStreamIndex != null
                        },
                    audioStreamIndex =
                        request.audioStreamIndex.takeIf { request.mediaSourceId != null },
                )
            }
            is PlaybackTarget.Download ->
                // The profile direct-plays everything (→ original file) unless
                // transcodeDolbyVision is set (Dolby Vision routed through a progressive H.264
                // transcode) or the user picked a download quality cap (files over the cap
                // transcoded down to it). Everything else stays the original file.
                PlaybackInfoDto(
                    userId = api.currentUserId,
                    deviceProfile = deviceProfiles.profileFor(target, bitrateCapped = capped),
                    maxStreamingBitrate = maxBitrate ?: DOWNLOAD_MAX_BITRATE,
                    enableTranscoding = target.transcodeDolbyVision || capped,
                    // Force a real video re-encode when a transcode happens — never let the server
                    // copy the DV bitstream into the container, and never let a stream copy defeat
                    // the cap.
                    allowVideoStreamCopy = false,
                    allowAudioStreamCopy = true,
                )
            is PlaybackTarget.CastReceiver ->
                // As the Cast controller asks today: the source with or without an audio index, and
                // no maximum bitrate when uncapped (#767).
                PlaybackInfoDto(
                    userId = api.currentUserId,
                    mediaSourceId = request.mediaSourceId?.asString(),
                    deviceProfile = deviceProfiles.profileFor(target, bitrateCapped = capped),
                    maxStreamingBitrate = maxBitrate,
                    audioStreamIndex = request.audioStreamIndex,
                    // The receiver plays a file's own default audio track and cannot be told to
                    // play another, so a chosen track has to come out of the server.
                    enableDirectPlay = request.audioStreamIndex == null,
                    enableDirectStream = true,
                    enableTranscoding = true,
                    allowAudioStreamCopy = true,
                    allowVideoStreamCopy = true,
                )
        }
    }

    override suspend fun segments(itemId: ItemId): List<Segment> = call {
        api.mediaSegmentApi.getItemSegments(itemId.toUuid()).content.items.map { it.toSegment() }
    }

    override suspend fun trickplayTile(
        itemId: ItemId,
        width: Int,
        index: Int,
        mediaSourceId: MediaSourceId?,
    ): ByteArray? = call {
        api.trickplaySheetApi
            .getTrickplayTileImage(
                itemId.toUuid(),
                width,
                index,
                trickplayVersion(itemId, mediaSourceId),
            )
            .content
    }

    override fun chapterImageUrl(itemId: ItemId, chapterIndex: Int, tag: String): String? =
        chapterImageUrl(api.pinnedNow(pin) { api.baseUrl() }, itemId, chapterIndex, tag)

    override fun seasonBackdropUrl(seasonId: ItemId): String? =
        seasonBackdropUrl(api.pinnedNow(pin) { api.baseUrl() }, seasonId)

    override suspend fun reportStart(report: PlaybackReport) {
        call {
            api.sessionApi.reportPlaybackStart(
                PlaybackStartInfo(
                    itemId = report.itemId.toUuid(),
                    canSeek = true,
                    isPaused = report.isPaused,
                    isMuted = false,
                    playMethod = report.playMethod(),
                    playSessionId = report.playSessionId,
                    // CE (#909): the version that plays; without it the server shows the first.
                    mediaSourceId = report.mediaSourceId?.asString(),
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                    // CE (#868): where the player is, as the web client sends it. Without it the
                    // server shows the item at 0 until the first progress report.
                    positionTicks = report.positionTicks,
                )
            )
        }
    }

    override suspend fun reportProgress(report: PlaybackReport) {
        call {
            api.sessionApi.reportPlaybackProgress(
                PlaybackProgressInfo(
                    itemId = report.itemId.toUuid(),
                    canSeek = true,
                    isPaused = report.isPaused,
                    isMuted = false,
                    playMethod = report.playMethod(),
                    playSessionId = report.playSessionId,
                    mediaSourceId = report.mediaSourceId?.asString(),
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                    positionTicks = report.positionTicks,
                )
            )
        }
    }

    override suspend fun reportStopped(report: PlaybackReport) {
        call {
            api.sessionApi.reportPlaybackStopped(
                PlaybackStopInfo(
                    itemId = report.itemId.toUuid(),
                    positionTicks = report.positionTicks,
                    failed = false,
                    // With it the server ends the item's transcode now, not a minute later.
                    playSessionId = report.playSessionId,
                    mediaSourceId = report.mediaSourceId?.asString(),
                )
            )
        }
    }

    /** CE (#637): Jellyfin always has resume settings, in its configuration. */
    override suspend fun resumeThresholds(): ResumeThresholds? = call {
        api.systemApi.getConfiguration().content.toResumeThresholds()
    }

    private companion object {
        /** A download without a cap: no limit the server would apply. */
        const val DOWNLOAD_MAX_BITRATE = 1_000_000_000
    }
}

/**
 * CE (#860): the `mediaSourceId` of a trickplay request for [mediaSourceId] of [itemId]. Jellyfin
 * (`TrickplayController`) looks the images up under `mediaSourceId ?? itemId`: an alternate version
 * is an item of its own, and the item's own version has the item's id as its source id. So the
 * parameter is sent only for another version, and the request for an item's own version stays the
 * one it always was. A source id that is not an id sends none either.
 */
internal fun trickplayVersion(itemId: ItemId, mediaSourceId: MediaSourceId?): UUID? =
    mediaSourceId?.asString()?.toUUIDOrNull()?.takeIf { it.toItemId() != itemId }

/** CE (#599): Transcode with the transcode's play session, else DirectPlay (see PlaySessions). */
private fun PlaybackReport.playMethod(): PlayMethod =
    if (transcoded) PlayMethod.TRANSCODE else PlayMethod.DIRECT_PLAY
