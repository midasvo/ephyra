package dev.jdtech.jellyfin.presentation.utils

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import dev.jdtech.jellyfin.utils.LocalPlaybackStopped
import dev.jdtech.jellyfin.utils.ReloadAfterSync
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CE (#775): Favorites loads again when the sync has sent what was changed while the server was
 * away, and not on entry: its own first load does that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReloadAfterSyncTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theSyncsEndReloadsOnceAndEntryDoesNot() {
        val synced = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var reloads = 0
        rule.setContent {
            CompositionLocalProvider(LocalPlaybackStopped provides synced) {
                ReloadAfterSync { reloads++ }
            }
        }
        rule.waitForIdle()
        assertEquals(0, reloads)

        synced.tryEmit(Unit)
        rule.waitForIdle()

        assertEquals(1, reloads)
    }
}
