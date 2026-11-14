package dev.jdtech.jellyfin.player.local.mpv

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.Clock
import androidx.media3.common.util.ListenerSet
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #453: picking another episode replaces MPVPlayer's playlist. The media session must hear about
 * the new timeline before any state change, and listeners must get one transition to the item that
 * will play, or the app crashed (playing item at index 1 or later) or kept the old item's title,
 * queue and server reports (playing item at index 0).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MpvItemTransitionsTest {

    private val events = mutableListOf<String>()
    private val listeners =
        ListenerSet<Player.Listener>(Looper.getMainLooper(), Clock.DEFAULT) { _, _ -> }
    private val transitions = MpvItemTransitions(listeners)

    init {
        listeners.add(
            object : Player.Listener {
                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    events += "timeline ${timeline.windowCount} reason $reason"
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    events += "transition ${mediaItem?.mediaId} reason $reason"
                }
            }
        )
    }

    private fun item(id: String) = MediaItem.Builder().setMediaId(id).build()

    /** A timeline with one window per item, standing in for MPVPlayer's live one. */
    private fun timelineOf(items: List<MediaItem>) =
        object : Timeline() {
            override fun getWindowCount() = items.size

            override fun getWindow(index: Int, window: Window, projectionUs: Long) =
                window.set(
                    index,
                    items[index],
                    null,
                    C.TIME_UNSET,
                    C.TIME_UNSET,
                    C.TIME_UNSET,
                    true,
                    false,
                    null,
                    0,
                    C.TIME_UNSET,
                    index,
                    index,
                    0,
                )

            override fun getPeriodCount() = items.size

            override fun getPeriod(index: Int, period: Period, setIds: Boolean) =
                period.set(index, index, index, C.TIME_UNSET, 0)

            override fun getIndexOfPeriod(uid: Any) = uid as? Int ?: C.INDEX_UNSET

            override fun getUidOfPeriod(index: Int): Any = index
        }

    private fun replace(items: List<MediaItem>, startIndex: Int) {
        transitions.playlistReplaced(timelineOf(items), items, startIndex)
        listeners.flushEvents()
    }

    /** mpv's playlist-current-pos, as MPVPlayer handles it. */
    private fun mpvCurrentPos(
        item: MediaItem,
        timeline: Timeline = timelineOf(listOf(item)),
        indexChanged: Boolean = true,
    ) {
        transitions.currentPositionChanged(timeline, item, indexChanged)
        listeners.flushEvents()
    }

    private val timelineReplaced = "reason ${Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED}"
    private val playlistChanged = "reason ${Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED}"

    @Test
    fun `a jump from the second item sends the one-item timeline, then the new item`() {
        val e3 = item("e3")
        val e4 = item("e4")
        val e5 = item("e5")
        mpvCurrentPos(e3)
        mpvCurrentPos(e4) // E4 at index 1, E3 queued in front of it
        events.clear()

        replace(listOf(e5), 0)
        mpvCurrentPos(e5, indexChanged = false) // mpv's own event, later

        assertEquals(
            listOf("timeline 1 $timelineReplaced", "transition e5 $playlistChanged"),
            events,
        )
    }

    @Test
    fun `a jump from the first item still sends the transition`() {
        val e1 = item("e1")
        val e3 = item("e3")
        mpvCurrentPos(e1)
        events.clear()

        // mpv's playlist-current-pos stays 0, so mpv sends nothing for the new item.
        replace(listOf(e3), 0)

        assertEquals(
            listOf("timeline 1 $timelineReplaced", "transition e3 $playlistChanged"),
            events,
        )
    }

    @Test
    fun `a list starting at index 2 sends one transition, for item 2`() {
        val items = listOf(item("a"), item("b"), item("c"))

        replace(items, 2)
        // mpv then reports index 2 (MPVPlayer said 0 until then): the timeline again, no second
        // transition.
        mpvCurrentPos(items[2], timelineOf(items))

        assertEquals(
            listOf(
                "timeline 3 $timelineReplaced",
                "transition c $playlistChanged",
                "timeline 3 $timelineReplaced",
            ),
            events,
        )
    }

    @Test
    fun `reloading the same item, as a quality switch does, sends no transition`() {
        val e1 = item("e1")
        val e2 = item("e2")
        val e3 = item("e3")
        mpvCurrentPos(e1)
        mpvCurrentPos(e2)
        events.clear()

        val reloaded = listOf(item("e1"), item("e2"), e3)
        replace(reloaded, 1)
        // setMediaItems reported index 0 until mpv says it plays index 1: the timeline again, so
        // the media session takes index 1 and keeps E2's title.
        mpvCurrentPos(reloaded[1], timelineOf(reloaded))

        assertEquals(listOf("timeline 3 $timelineReplaced", "timeline 3 $timelineReplaced"), events)
    }

    @Test
    fun `mpv reporting the same item at the same index sends nothing`() {
        val e1 = item("e1")
        mpvCurrentPos(e1)
        events.clear()

        mpvCurrentPos(e1, indexChanged = false)

        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `the first load sends one transition`() {
        val e1 = item("e1")

        replace(listOf(e1), 0)
        mpvCurrentPos(e1, indexChanged = false)

        assertEquals(
            listOf("timeline 1 $timelineReplaced", "transition e1 $playlistChanged"),
            events,
        )
    }
}
