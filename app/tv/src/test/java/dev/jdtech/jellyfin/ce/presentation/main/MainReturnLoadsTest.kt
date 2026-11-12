package dev.jdtech.jellyfin.ce.presentation.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainReturnLoadsTest {
    private val now = 100 * LIBRARIES_REFRESH_NANOS

    // #408: a PIN set or removed in Settings changes the same user's row; the drawer's face
    // must read it again on the way back.
    @Test
    fun `the same user is loaded again on every return`() {
        val loads =
            loadsOnReturn(
                sameUser = true,
                librariesLoadedAtNanos = now - 1,
                hasLibraries = true,
                nowNanos = now,
            )
        assertEquals(ReturnLoads(user = true, libraries = LibrariesLoad.NONE), loads)
    }

    @Test
    fun `the libraries load in full the first time`() {
        val loads =
            loadsOnReturn(
                sameUser = false,
                librariesLoadedAtNanos = null,
                hasLibraries = false,
                nowNanos = now,
            )
        assertEquals(LibrariesLoad.FULL, loads.libraries)
    }

    @Test
    fun `an empty library list loads in full`() {
        val loads =
            loadsOnReturn(
                sameUser = true,
                librariesLoadedAtNanos = now - 1,
                hasLibraries = false,
                nowNanos = now,
            )
        assertEquals(LibrariesLoad.FULL, loads.libraries)
    }

    @Test
    fun `another user refreshes the libraries in the background`() {
        val loads =
            loadsOnReturn(
                sameUser = false,
                librariesLoadedAtNanos = now - 1,
                hasLibraries = true,
                nowNanos = now,
            )
        assertEquals(LibrariesLoad.SILENT, loads.libraries)
    }

    // #513: a start without the server leaves the drawer without libraries until Retry.
    @Test
    fun `retry on Home loads the libraries when there are none`() {
        assertTrue(reloadsLibrariesOnRetry(hasLibraries = false, librariesLoading = false))
    }

    @Test
    fun `retry on Home leaves libraries that are there or loading`() {
        assertFalse(reloadsLibrariesOnRetry(hasLibraries = true, librariesLoading = false))
        assertFalse(reloadsLibrariesOnRetry(hasLibraries = false, librariesLoading = true))
    }

    @Test
    fun `old libraries refresh in the background`() {
        val loads =
            loadsOnReturn(
                sameUser = true,
                librariesLoadedAtNanos = now - LIBRARIES_REFRESH_NANOS - 1,
                hasLibraries = true,
                nowNanos = now,
            )
        assertEquals(LibrariesLoad.SILENT, loads.libraries)
    }
}
