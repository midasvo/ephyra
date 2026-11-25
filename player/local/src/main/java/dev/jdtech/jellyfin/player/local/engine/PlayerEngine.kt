package dev.jdtech.jellyfin.player.local.engine

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import dev.jdtech.jellyfin.models.PlayerEngineId
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient

/**
 * What an engine needs from the player session that builds it (#764). The engine reads its own
 * settings from `AppPreferences`; this holds the rest.
 *
 * @param httpClient the client media is streamed through (`MediaHttpClient`).
 * @param subtitleDelayUs the subtitle delay of the playing item, read on the playback thread.
 * @param itemDurationUs the playing item's duration, read on the playback thread.
 * @param subtitleDelayUsFor the subtitle delay of an item by its media id, read on the playback
 *   thread: only the playing item has one.
 * @param scope lives as long as the player session: the engine collects settings in it.
 */
data class PlayerEngineConfig(
    val httpClient: OkHttpClient,
    val subtitleDelayUs: () -> Long,
    val itemDurationUs: () -> Long,
    val subtitleDelayUsFor: (mediaId: String) -> Long,
    val scope: CoroutineScope,
)

/** A running engine. [player] is what the rest of the player uses. */
interface EnginePlayer {
    val player: Player

    /** Shifts subtitles by [delayMs] (positive = later, negative = earlier). */
    fun setSubtitleDelayMs(delayMs: Int)

    /**
     * CE (#870): whether replacing the playlist reports a media item transition also when the
     * playing item stays the same (a quality or transcode audio switch), as ExoPlayer does with
     * `MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED`. Without one, the caller reports the new
     * stream itself.
     */
    val transitionsOnReplacedPlaylist: Boolean
        get() = true

    /** Called when an item becomes ready, for settings the engine drops on load. */
    fun onItemReady() {}

    /**
     * Zooms the picture to fill the screen, or back to fit.
     *
     * @return `false` when the engine leaves the zoom to the view (the `PlayerView` resize mode).
     */
    fun setZoomMode(enabled: Boolean): Boolean = false

    /**
     * True when preparing the player again can recover from [error] (ExoPlayer has ruled out the
     * audio decoder that failed, or turned tunneling off for an audio track the tunnel output
     * refused, #924). The caller then calls `player.prepare()` and stops handling the error; the
     * engine does not retry itself. False for everything else.
     */
    fun onPlayerError(error: PlaybackException): Boolean = false
}

/** Builds an engine. Hilt binds one per [PlayerEngineId] (`engine.di.EngineModule`). */
interface PlayerEngine {
    val id: PlayerEngineId

    /** Called once per player session: the [EnginePlayer.player] lives as long as the session. */
    fun create(config: PlayerEngineConfig): EnginePlayer
}
