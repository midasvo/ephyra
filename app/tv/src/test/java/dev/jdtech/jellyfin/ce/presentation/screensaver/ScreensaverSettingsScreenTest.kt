package dev.jdtech.jellyfin.ce.presentation.screensaver

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The screensaver settings put each value at the right of its row, apart from its label (#422). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1920dp-h1080dp")
class ScreensaverSettingsScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun theValueStandsApartFromItsLabel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appPreferences =
            AppPreferences(context.getSharedPreferences("screensaver_test", Context.MODE_PRIVATE))
        rule.setContent {
            FindroidTheme { ScreensaverSettingsScreen(appPreferences = appPreferences, onBack = {}) }
        }
        rule.waitForIdle()

        listOf("Artwork source" to "Random", "Switch interval" to "20 seconds").forEach {
            (label, value) ->
            // On a row about 1300 dp wide, the value sits at the far end, not right after the
            // label.
            val gap = bounds(value).left - bounds(label).right
            assertTrue("$label: gap between label and value is $gap", gap > 400.dp)
        }
    }

    private fun bounds(text: String) =
        rule.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot()
}
