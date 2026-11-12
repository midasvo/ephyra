package dev.jdtech.jellyfin.repository

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.jellyfin.sdk.model.api.PlayMethod

/**
 * The play session the server opened for each item it transcodes. The stop report names it, which
 * is what makes the server end the transcode: without it ffmpeg runs on until the server's own
 * timer ends it, a minute after the last segment was fetched.
 */
internal class PlaySessions {
    private val sessions = ConcurrentHashMap<UUID, String>()

    /**
     * Records the answer to a playback info request for [itemId]. A source that plays directly has
     * no transcode to end, so its session is not kept. A later request for the same item (a quality
     * switch) replaces the session: the stream it replaces is left to the server's timer, the SDK
     * has no call to end it.
     */
    fun onPlaybackInfo(itemId: UUID, playSessionId: String?, transcodes: Boolean) {
        val session = playSessionId?.takeIf { transcodes && it.isNotBlank() }
        if (session != null) sessions[itemId] = session else sessions.remove(itemId)
    }

    /**
     * The transcode session of [itemId], or `null` when it plays directly. Kept after a stop: the
     * player can go back to an item it left, on the stream it already has.
     */
    fun sessionOf(itemId: UUID): String? = sessions[itemId]

    /**
     * CE (#599): the play method and play session for a start or progress report of [itemId].
     * [transcoded] is whether the stream playing is the server's transcode.
     *
     * The server never offers a direct stream (a remux): a source it cannot direct-play gets a
     * transcoding URL, remux or not, and its dashboard tells a remux from the transcode's own
     * details, which it keeps only while the reports say Transcode. So a transcode is reported as
     * such, never as DirectStream. The server accepts Transcode only together with the play session
     * of a running transcode job, and takes it for DirectPlay otherwise; a start sent before the
     * player fetched the stream is therefore DirectPlay until the first progress report.
     */
    fun playReport(itemId: UUID, transcoded: Boolean): PlayReport {
        val session = sessionOf(itemId)
        return if (transcoded && session != null) PlayReport(PlayMethod.TRANSCODE, session)
        else PlayReport(PlayMethod.DIRECT_PLAY, null)
    }
}

/** The play method of a start or progress report, and the play session it names. */
internal data class PlayReport(val playMethod: PlayMethod, val playSessionId: String?)
