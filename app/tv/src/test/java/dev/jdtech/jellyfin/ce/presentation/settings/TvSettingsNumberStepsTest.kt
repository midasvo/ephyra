package dev.jdtech.jellyfin.ce.presentation.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The steps of a TV number setting (#420): whole seconds for milliseconds, never below zero. */
class TvSettingsNumberStepsTest {
    @Test
    fun countsStepByOneAndTen() {
        assertEquals(listOf(1L, -1L, 10L, -10L), numberSteps(inMilliseconds = false))
    }

    @Test
    fun millisecondsStepByOneAndTenSeconds() {
        assertEquals(listOf(1000L, -1000L, 10000L, -10000L), numberSteps(inMilliseconds = true))
    }

    @Test
    fun aTimeoutGoesFromThirtyToSixtySecondsInThreePresses() {
        var value = 30000L
        repeat(3) { value = stepValue(value, 10000L)!! }
        assertEquals(60000L, value)
    }

    @Test
    fun aMinusStepNeverGoesBelowZero() {
        assertEquals(0L, stepValue(1000L, -1000L))
        assertEquals(0L, stepValue(10000L, -10000L))
        assertNull(stepValue(500L, -1000L))
        assertNull(stepValue(9999L, -10000L))
        assertNull(stepValue(0L, -1000L))
        assertNull(stepValue(0L, -1L))
        assertEquals(1L, stepValue(0L, 1L))
    }
}
