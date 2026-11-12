package dev.jdtech.jellyfin.player.local.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [PlaybackStartGate] (#601): the start report waits for the item to load, and a
 * reload sends exactly the starts it sent before (one per transition, none without one).
 */
class PlaybackStartGateTest {
    /** Drives a gate through player events and records the starts it lets out. */
    private class Player {
        val gate = PlaybackStartGate<String>()
        val starts = mutableListOf<String>()
        var ready = false

        fun transition(item: String) {
            gate.onTransition(item, ready)?.let(starts::add)
        }

        fun buffering() {
            ready = false
        }

        fun ready() {
            ready = true
            gate.onReady()?.let(starts::add)
        }

        fun error() {
            ready = false
        }
    }

    @Test
    fun `an item that loads gets its start when it is ready, not before`() {
        val player = Player()
        player.transition("movie")
        player.buffering()
        assertEquals(emptyList<String>(), player.starts)

        player.ready()
        assertEquals(listOf("movie"), player.starts)
    }

    @Test
    fun `an item that never opens gets no start`() {
        val player = Player()
        player.transition("broken")
        player.buffering()
        player.error()

        assertEquals(emptyList<String>(), player.starts)
    }

    @Test
    fun `rebuffering and the reload after a lost connection send no second start`() {
        val player = Player()
        player.transition("movie")
        player.ready()
        player.buffering()
        player.ready()
        // NetworkRecovery reloads with prepare(): no transition.
        player.error()
        player.buffering()
        player.ready()

        assertEquals(listOf("movie"), player.starts)
    }

    @Test
    fun `ExoPlayer's quality switch sends a second start, as before`() {
        // ExoPlayer reports a transition to the same item when setMediaItems replaces the
        // playlist; main sent a start for it, and still does, once the new stream is ready.
        val player = Player()
        player.transition("movie")
        player.ready()
        player.buffering()
        player.transition("movie")
        assertEquals(listOf("movie"), player.starts)
        player.ready()
        assertEquals(listOf("movie", "movie"), player.starts)
    }

    @Test
    fun `a quality switch that fails to load sends no second start`() {
        val player = Player()
        player.transition("movie")
        player.ready()
        player.buffering()
        player.transition("movie")
        player.error()

        assertEquals(listOf("movie"), player.starts)
    }

    @Test
    fun `mpv's quality switch sends one start, for the item it plays on`() {
        // mpv replaces the playlist with the previous episode first: transitions E3, E2, E3. The
        // E2 transition is handled after E3's (it waited for E3's stop), and is no longer
        // current. Main sent one start, for E3; so does the gate.
        val player = Player()
        player.transition("E3")
        player.ready()
        player.buffering()
        player.transition("E3")
        player.gate.onTransition("E2", ready = false, current = false)?.let(player.starts::add)
        player.ready()

        assertEquals(listOf("E3", "E3"), player.starts)
    }

    @Test
    fun `a transition while the player is ready sends the start at once`() {
        // Auto-advance to an item that buffered ahead.
        val player = Player()
        player.transition("episode 1")
        player.ready()
        player.transition("episode 2")

        assertEquals(listOf("episode 1", "episode 2"), player.starts)
        // The next ready state owes nothing more.
        player.ready()
        assertEquals(listOf("episode 1", "episode 2"), player.starts)
    }

    @Test
    fun `an item left before it loaded gets no start`() {
        // Next pressed while episode 1 was still loading.
        val player = Player()
        player.transition("episode 1")
        player.buffering()
        player.transition("episode 2")
        player.ready()

        assertEquals(listOf("episode 2"), player.starts)
    }

    @Test
    fun `nothing is owed once the player closed`() {
        val player = Player()
        player.transition("movie")
        player.gate.clear()
        player.ready()

        assertEquals(emptyList<String>(), player.starts)
    }
}
