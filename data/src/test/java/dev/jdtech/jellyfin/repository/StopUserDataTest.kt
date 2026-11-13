package dev.jdtech.jellyfin.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [stopUserData]: what a playback stop writes to the local database. CE (#637): the
 * same as the server's `UserDataManager.UpdatePlayState`, with the server's resume settings.
 */
class StopUserDataTest {
    private val seconds = 10_000_000L

    /** A 53-minute episode, as the server's runtime. */
    private val runtime = 3219 * seconds

    /** The settings of the server this was found on: 2 %, 93 %, 2 seconds. */
    private val server = ResumeThresholds(2, 93, 2)

    @Test
    fun `a stop the server keeps is kept locally too`() {
        // 66 s of 3219 s is 2.05 %: the server kept it, the old fixed 10 % threw it away.
        assertEquals(
            StopUserData(positionTicks = 66 * seconds, played = null),
            stopUserData(66 * seconds, 2, runtime, server),
        )
    }

    @Test
    fun `before the minimum the position is dropped and played is left alone`() {
        assertEquals(
            StopUserData(positionTicks = 0L, played = null),
            stopUserData(60 * seconds, 1, runtime, server),
        )
    }

    @Test
    fun `after the maximum the item is played`() {
        assertEquals(
            StopUserData(positionTicks = 0L, played = true),
            stopUserData(3000 * seconds, 93, runtime, server),
        )
    }

    @Test
    fun `the exact percentage decides, not the rounded one`() {
        // 93.5 % is over 93 %, though the player's whole percentage says 93.
        val position = runtime * 935 / 1000
        assertEquals(
            StopUserData(positionTicks = 0L, played = true),
            stopUserData(position, 93, runtime, server),
        )
    }

    @Test
    fun `within a second of the end the item is played`() {
        val noMaximum = server.copy(maxResumePct = 100)
        assertEquals(
            StopUserData(positionTicks = 0L, played = true),
            stopUserData(runtime - seconds / 2, 99, runtime, noMaximum),
        )
    }

    @Test
    fun `an item shorter than the minimum duration is played instead of resumed`() {
        val short = 200 * seconds
        assertEquals(
            StopUserData(positionTicks = 0L, played = true),
            stopUserData(100 * seconds, 50, short, ResumeThresholds.DEFAULT),
        )
    }

    @Test
    fun `in between the position is saved and played is left alone`() {
        assertEquals(
            StopUserData(positionTicks = 1600 * seconds, played = null),
            stopUserData(1600 * seconds, 49, runtime, server),
        )
    }

    @Test
    fun `without a stored runtime the player's percentage decides`() {
        assertEquals(
            StopUserData(positionTicks = 0L, played = null),
            stopUserData(5_000L, 4, runtimeTicks = null),
        )
        assertEquals(
            StopUserData(positionTicks = 5_000L, played = null),
            stopUserData(5_000L, 5, runtimeTicks = null),
        )
        assertEquals(
            StopUserData(positionTicks = 0L, played = true),
            stopUserData(5_000L, 91, runtimeTicks = null),
        )
    }

    @Test
    fun `a stop at the start keeps no position and leaves played alone`() {
        assertEquals(StopUserData(positionTicks = 0L, played = null), stopUserData(0L, 0, runtime))
    }

    @Test
    fun `no percentage writes nothing`() {
        // #461: an item left while loading keeps its local position, played flag and sync flag.
        assertNull(stopUserData(38_461_200_000L, null))
        assertNull(stopUserData(0L, null))
    }
}
