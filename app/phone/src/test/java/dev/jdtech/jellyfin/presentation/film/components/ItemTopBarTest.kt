package dev.jdtech.jellyfin.presentation.film.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #437: the top bar darkens what is under it only when that is artwork. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class ItemTopBarTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `over artwork the bar has its dark scrim`() {
        show(overArtwork = true)

        assertNotEquals(Color.White, topLeftPixel())
    }

    @Test
    fun `without artwork the bar leaves the page as it is`() {
        show(overArtwork = false)

        assertEquals(Color.White, topLeftPixel())
    }

    private fun topLeftPixel(): Color = rule.onNodeWithTag("page").captureToImage().toPixelMap()[0, 0]

    private fun show(overArtwork: Boolean) {
        rule.setContent {
            FindroidTheme {
                Box(modifier = Modifier.background(Color.White).testTag("page")) {
                    ItemTopBar(
                        hasBackButton = true,
                        hasHomeButton = false,
                        overArtwork = overArtwork,
                    )
                }
            }
        }
    }
}
