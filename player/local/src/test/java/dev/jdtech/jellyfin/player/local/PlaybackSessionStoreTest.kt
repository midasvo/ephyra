package dev.jdtech.jellyfin.player.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #677: exactly one of the two progress loops runs. The player screen's loop runs while the
 * activity is started; the view model's background loop whenever [PlaybackSessionStore.isUiStarted]
 * is false, which includes an activity that is stopped but kept (after Home).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackSessionStoreTest {

    private val store = PlaybackSessionStore()

    @Test
    fun `a started player screen reports itself`() {
        store.onUiAttached()
        store.onUiStarted()

        assertTrue(store.isUiStarted)
    }

    @Test
    fun `after Home the attached but stopped screen leaves reporting to the background loop`() {
        store.onUiAttached()
        store.onUiStarted()
        store.onUiStopped()

        assertTrue(store.isUiAttached)
        assertFalse(store.isUiStarted)
    }

    @Test
    fun `coming back to the player hands reporting back to the screen`() {
        store.onUiAttached()
        store.onUiStarted()
        store.onUiStopped()
        store.onUiStarted()

        assertTrue(store.isUiStarted)
    }

    @Test
    fun `a new screen that starts before the old one stops stays started`() {
        store.onUiStarted()
        store.onUiStarted()
        store.onUiStopped()

        assertTrue(store.isUiStarted)
    }

    @Test
    fun `an extra stop does not hide a screen that starts later`() {
        store.onUiStopped()
        store.onUiStarted()

        assertTrue(store.isUiStarted)
    }

    @Test
    fun `a destroyed screen leaves reporting to the background loop`() {
        store.onSessionCreated()
        store.onUiAttached()
        store.onUiStarted()
        store.onUiStopped()
        store.onUiDetached(isPlaying = true)

        assertFalse(store.isUiStarted)
    }
}
