package dev.jdtech.jellyfin.player.local.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #452: the phone player's title was "S1:E3 - Name" without the series. It is now the series name
 * on a line of its own above "S1:E3 · Name".
 */
class PlayerTitleTest {

    @Test
    fun `an episode has the series line and the numbered title`() {
        assertEquals(
            PlayerTitle("Breaking Bad", "S1:E6 · Crazy Handful of Nothin'"),
            playerTitle("Crazy Handful of Nothin'", "Breaking Bad", 1, 6, null),
        )
    }

    @Test
    fun `a double episode names both numbers`() {
        assertEquals(
            PlayerTitle("The Office", "S2:E1-2 · The Dundies"),
            playerTitle("The Dundies", "The Office", 2, 1, 2),
        )
    }

    @Test
    fun `an end number equal to the start is a single episode`() {
        assertEquals("S1:E4 · Name", playerTitle("Name", "Show", 1, 4, 4).title)
    }

    @Test
    fun `a movie is one line`() {
        assertEquals(
            PlayerTitle(null, "Long Movie (2016)"),
            playerTitle("Long Movie (2016)", null, null, null, null),
        )
    }

    @Test
    fun `a blank series name gives no series line`() {
        assertEquals(PlayerTitle(null, "S1:E1 · Pilot"), playerTitle("Pilot", "  ", 1, 1, null))
    }

    @Test
    fun `an episode without numbers keeps its series line`() {
        assertEquals(PlayerTitle("Show", "Special"), playerTitle("Special", "Show", null, 3, null))
    }
}
