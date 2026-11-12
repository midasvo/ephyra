package dev.jdtech.jellyfin.repository

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
}
