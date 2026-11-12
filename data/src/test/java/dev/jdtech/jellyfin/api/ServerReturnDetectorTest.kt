package dev.jdtech.jellyfin.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CE (#552): the server coming back is told from the app's own requests. */
class ServerReturnDetectorTest {
    private val detector = ServerReturnDetector()

    @Test
    fun `answers without a failure before them are not a return`() {
        assertFalse(detector.onResponse(200))
        assertFalse(detector.onResponse(404))
    }

    @Test
    fun `the first answer after a failure is a return, once`() {
        detector.onFailure()
        detector.onFailure()

        assertTrue(detector.onResponse(200))
        assertFalse(detector.onResponse(200))
    }

    @Test
    fun `a refused token is still the server answering`() {
        detector.onFailure()

        assertTrue(detector.onResponse(401))
    }

    @Test
    fun `a server error is not a return and counts as a failure`() {
        detector.onFailure()
        assertFalse(detector.onResponse(502))

        assertTrue(detector.onResponse(200))

        assertFalse(detector.onResponse(503))
        assertTrue(detector.onResponse(200))
    }
}
