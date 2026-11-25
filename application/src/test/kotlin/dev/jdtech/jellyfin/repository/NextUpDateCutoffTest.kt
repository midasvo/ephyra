package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.toItemId
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class NextUpDateCutoffTest {
    private val now = LocalDateTime.of(2026, 11, 1, 12, 0)

    @Test
    fun `the Home row has no cutoff by default`() {
        assertNull(nextUpDateCutoff(seriesId = null, maxDays = 0, now = now))
    }

    @Test
    fun `the Home row looks back the chosen number of days`() {
        assertEquals(
            LocalDateTime.of(2025, 11, 1, 12, 0),
            nextUpDateCutoff(seriesId = null, maxDays = 365, now = now),
        )
    }

    @Test
    fun `a single series has no cutoff`() {
        assertNull(
            nextUpDateCutoff(
                seriesId = UUID.randomUUID().toString().toItemId(),
                maxDays = 365,
                now = now,
            )
        )
    }
}
