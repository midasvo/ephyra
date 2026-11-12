package dev.jdtech.jellyfin

import android.app.Application
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #455: in split-screen the status bar and the taskbar stay visible over the player whatever it
 * asks. The controls were padded for the display cutout only, so the seek bar sat under the
 * taskbar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerControlsInsetsTest {
    /** As a window reports them: a hidden bar keeps its size only ignoring visibility. */
    private fun insets(barsVisible: Boolean, cutout: Insets = Insets.NONE): WindowInsetsCompat {
        val status = Insets.of(0, 48, 0, 0)
        val taskbar = Insets.of(0, 0, 0, 120)
        val statusBars = WindowInsetsCompat.Type.statusBars()
        val navigationBars = WindowInsetsCompat.Type.navigationBars()
        return WindowInsetsCompat.Builder()
            .setInsetsIgnoringVisibility(statusBars, status)
            .setInsetsIgnoringVisibility(navigationBars, taskbar)
            .setInsets(statusBars, if (barsVisible) status else Insets.NONE)
            .setInsets(navigationBars, if (barsVisible) taskbar else Insets.NONE)
            .setVisible(WindowInsetsCompat.Type.systemBars(), barsVisible)
            .setInsets(WindowInsetsCompat.Type.displayCutout(), cutout)
            .build()
    }

    @Test
    fun `visible bars pad the controls`() {
        assertEquals(Insets.of(0, 48, 0, 120), playerControlsInsets(insets(barsVisible = true)))
    }

    @Test
    fun `hidden bars leave only the cutout`() {
        assertEquals(Insets.NONE, playerControlsInsets(insets(barsVisible = false)))
        assertEquals(
            Insets.of(0, 0, 90, 0),
            playerControlsInsets(insets(barsVisible = false, cutout = Insets.of(0, 0, 90, 0))),
        )
    }

    @Test
    fun `cutout and bars on the same side take the larger`() {
        assertEquals(
            Insets.of(0, 96, 0, 120),
            playerControlsInsets(insets(barsVisible = true, cutout = Insets.of(0, 96, 0, 0))),
        )
    }
}
