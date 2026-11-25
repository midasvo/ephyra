package dev.jdtech.jellyfin.domain.ports

import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.MediaSourceId
import dev.jdtech.jellyfin.models.PlaybackTarget
import dev.jdtech.jellyfin.models.Segment
import dev.jdtech.jellyfin.repository.ResumeThresholds

/** What to ask the server for the sources of [itemId] (#757). */
data class PlaybackRequest(
    val itemId: ItemId,
    val target: PlaybackTarget,
    val mediaSourceId: MediaSourceId? = null,
    /**
     * For a local player, only together with [mediaSourceId]. For a Cast receiver, as it is: the
     * receiver cannot pick a track itself, so a chosen one comes out of the server (#767).
     */
    val audioStreamIndex: Int? = null,
    /** Bits per second; null is no cap. */
    val maxBitrate: Int? = null,
    /**
     * Ask for the URL of file sources too (downloads need it). A Cast receiver always gets every
     * URL it needs, ready for it to fetch without a session (token included), whatever this says.
     */
    val includePath: Boolean = false,
)

/**
 * A start, progress or stop report of [itemId] (#757). [transcoded] is whether the server's
 * transcode with [playSessionId] plays; the repository's `PlaySessions` decides both. A stop sends
 * neither [isPaused] nor [transcoded].
 *
 * [positionTicks] is where the player is when the report is sent; a start sends it as the web
 * client does (#868). Only a start may leave it `null`, which sends no position: the Cast start,
 * whose receiver's position is not read.
 *
 * [mediaSourceId] is the version that plays (#909), as the web client sends it: without it the
 * server shows the item's first version. `null` names none.
 */
data class PlaybackReport(
    val itemId: ItemId,
    val positionTicks: Long?,
    val isPaused: Boolean,
    val transcoded: Boolean,
    val playSessionId: String?,
    val mediaSourceId: MediaSourceId? = null,
)

/**
 * Playback on a media server: its sources, what the player shows alongside, and the reports (#757).
 * Knows nothing about downloads. A failure the adapter recognizes as the server's (an error status,
 * a timeout, a TLS failure) is thrown as `ServerException` with the original as its cause; any
 * other exception, such as a reset connection, leaves as it is, as with [ItemPort].
 */
interface PlaybackPort {
    /**
     * The server's sources for [request], with finished URLs. `ItemSource.transcoded` and
     * `.playSessionId` say what the server chose; a download's sources carry no play session.
     */
    suspend fun sources(request: PlaybackRequest): List<ItemSource>

    suspend fun segments(itemId: ItemId): List<Segment>

    /**
     * Sheet [index] of the trickplay images [width] wide of [mediaSourceId], the version that is
     * played (#860). Null asks for the item's own version. Null when the server has no such sheet.
     */
    suspend fun trickplayTile(
        itemId: ItemId,
        width: Int,
        index: Int,
        mediaSourceId: MediaSourceId?,
    ): ByteArray?

    /** Null when no server is set. */
    fun chapterImageUrl(itemId: ItemId, chapterIndex: Int, tag: String): String?

    /** Null when no server is set. */
    fun seasonBackdropUrl(seasonId: ItemId): String?

    suspend fun reportStart(report: PlaybackReport)

    suspend fun reportProgress(report: PlaybackReport)

    suspend fun reportStopped(report: PlaybackReport)

    /** Null when the server has none. */
    suspend fun resumeThresholds(): ResumeThresholds?
}
