package dev.jdtech.jellyfin.utils

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * When [ReloadOnResume] reloads: on the screen's appearance, and on a later resume of the app only
 * when the screen is out of date. CE (#660): on TV a later resume is the TV waking from standby or
 * the screensaver.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReloadOnResumeTest {
    @get:Rule val rule = createComposeRule()

    private val app = TestLifecycleOwner()
    private var outOfDate = true
    private var reloads = 0

    private fun reload() {
        reloads++
        outOfDate = false
    }

    /** The app goes to the background (standby, the screensaver) and comes back. */
    private fun sleepAndWake() {
        rule.runOnIdle {
            for (event in
                listOf(
                    Lifecycle.Event.ON_PAUSE,
                    Lifecycle.Event.ON_STOP,
                    Lifecycle.Event.ON_START,
                    Lifecycle.Event.ON_RESUME,
                )) {
                app.registry.handleLifecycleEvent(event)
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `a screen loads when it appears, and on a later resume only when out of date`() {
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides app) {
                ReloadOnResume(shouldReload = { outOfDate }) { reload() }
            }
        }
        rule.waitForIdle()
        assertEquals(1, reloads)

        sleepAndWake()
        assertEquals(1, reloads)

        outOfDate = true
        sleepAndWake()
        assertEquals(2, reloads)
    }

    @Test
    fun `a screen that loads by itself is left alone when it appears, and reloaded on a later resume`() {
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides app) {
                ReloadOnResume(shouldReload = { outOfDate }, loadOnEntry = false) { reload() }
            }
        }
        rule.waitForIdle()
        assertEquals(0, reloads)

        sleepAndWake()
        assertEquals(1, reloads)

        sleepAndWake()
        assertEquals(1, reloads)
    }

    @Test
    fun `coming back to the screen is appearing again, not a resume`() {
        var shown by mutableStateOf(true)
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides app) {
                if (shown) {
                    ReloadOnResume(shouldReload = { outOfDate }, loadOnEntry = false) { reload() }
                }
            }
        }
        rule.waitForIdle()

        // Another screen on top (the player), then back.
        shown = false
        rule.waitForIdle()
        shown = true
        rule.waitForIdle()
        assertEquals(0, reloads)

        sleepAndWake()
        assertEquals(1, reloads)
    }
}

/** An app that is in the foreground, moved by hand. */
private class TestLifecycleOwner : LifecycleOwner {
    // Unsafe: the test moves it from the test thread.
    val registry: LifecycleRegistry =
        LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle
        get() = registry
}
