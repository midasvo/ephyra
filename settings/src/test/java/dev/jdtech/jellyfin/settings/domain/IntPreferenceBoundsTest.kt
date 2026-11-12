package dev.jdtech.jellyfin.settings.domain

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

/** #559: the still-watching prompt timeout is saved and shown as at least 5 seconds. */
class IntPreferenceBoundsTest {
    // bounded() only compares preferences, it never reads or writes them.
    private val prefs =
        AppPreferences(
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, _, _ ->
                error("not used")
            } as SharedPreferences
        )

    @Test
    fun `a prompt timeout of 0 becomes 5 seconds`() {
        assertEquals(5, prefs.bounded(prefs.stillWatchingPromptTimeoutSeconds, 0))
        assertEquals(5, prefs.bounded(prefs.stillWatchingPromptTimeoutSeconds, 4))
    }

    @Test
    fun `a prompt timeout of 5 seconds or more stays`() {
        assertEquals(5, prefs.bounded(prefs.stillWatchingPromptTimeoutSeconds, 5))
        assertEquals(30, prefs.bounded(prefs.stillWatchingPromptTimeoutSeconds, 30))
    }

    @Test
    fun `the other limits are unchanged`() {
        assertEquals(1, prefs.bounded(prefs.smartDownloadsKeepReady, 0))
        assertEquals(
            Constants.SubtitleStyle.FONT_SCALE_MAX,
            prefs.bounded(prefs.subtitleFontScale, 10_000),
        )
        // A setting where 0 means off keeps its 0.
        assertEquals(0, prefs.bounded(prefs.stillWatchingAfterEpisodes, 0))
    }
}
