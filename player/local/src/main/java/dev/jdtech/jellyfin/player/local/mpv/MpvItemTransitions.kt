package dev.jdtech.jellyfin.player.local.mpv

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.ListenerSet

/**
 * CE (#453): the media item transitions [MPVPlayer] reports, and the events for a replaced
 * playlist. Events are queued on [listeners]; the caller flushes them.
 *
 * [lastItem] is the item the last transition named, so a transition is only sent when the media id
 * changes, whether the change comes from [playlistReplaced] or from mpv's `playlist-current-pos`.
 */
internal class MpvItemTransitions(private val listeners: ListenerSet<Player.Listener>) {

    var lastItem: MediaItem? = null
        private set

    /**
     * mpv's `playlist-current-pos` moved to [index], playing [item]. Queues a transition when the
     * item changed. When only the index changed, the timeline is sent again instead, so the media
     * session takes the new index: setMediaItems resets the index to 0 until mpv reports where it
     * plays, and a quality switch reloads the same item at its old index, so without this the
     * session kept index 0 and showed the previous episode's title.
     */
    fun currentPositionChanged(timeline: Timeline, item: MediaItem?, indexChanged: Boolean) {
        if (transitionIfChanged(item, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)) return
        if (indexChanged) {
            listeners.queueEvent(Player.EVENT_TIMELINE_CHANGED) { listener ->
                listener.onTimelineChanged(timeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
            }
        }
    }

    /** Queues a transition to [item] when its media id differs from [lastItem]. */
    private fun transitionIfChanged(
        item: MediaItem?,
        @Player.MediaItemTransitionReason reason: Int,
    ): Boolean {
        if (lastItem?.mediaId == item?.mediaId) return false
        lastItem = item
        listeners.queueEvent(Player.EVENT_MEDIA_ITEM_TRANSITION) { listener ->
            listener.onMediaItemTransition(item, reason)
        }
        // The media session reads the notification's title and artwork from this event.
        listeners.queueEvent(Player.EVENT_MEDIA_METADATA_CHANGED) { listener ->
            listener.onMediaMetadataChanged(item?.mediaMetadata ?: MediaMetadata.EMPTY)
        }
        return true
    }

    /**
     * The playlist was replaced by [items], to start at [startIndex]. Sends the new [timeline] at
     * once, as ExoPlayer does inside setMediaItems: the media session checks its cached position
     * against its cached timeline on the next event, and [MPVPlayer]'s timeline is a live view of
     * the playlist, so a shorter list with the old position crashed the app. Then the transition to
     * the item that will play, if it is another item. mpv's own `playlist-count` and
     * `playlist-current-pos` events come later, or not at all when the index stays the same.
     */
    fun playlistReplaced(timeline: Timeline, items: List<MediaItem>, startIndex: Int) {
        listeners.queueEvent(Player.EVENT_TIMELINE_CHANGED) { listener ->
            listener.onTimelineChanged(timeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
        }
        val playing = items.getOrNull(if (startIndex == C.INDEX_UNSET) 0 else startIndex)
        transitionIfChanged(playing, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
    }
}
