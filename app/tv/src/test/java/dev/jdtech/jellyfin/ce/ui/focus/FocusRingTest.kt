package dev.jdtech.jellyfin.ce.ui.focus

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.MaterialTheme
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.util.withJson
import dev.jdtech.jellyfin.R
import dev.jdtech.jellyfin.ce.presentation.settings.about.ABOUT_LICENSES_TAG
import dev.jdtech.jellyfin.ce.presentation.settings.about.AboutScreenLayout
import dev.jdtech.jellyfin.ce.presentation.settings.about.LICENSE_TEXT_TAG
import dev.jdtech.jellyfin.ce.presentation.settings.about.LicenseScreen
import dev.jdtech.jellyfin.ce.presentation.settings.about.previewInfo
import dev.jdtech.jellyfin.ce.ui.TvSafeArea
import dev.jdtech.jellyfin.ce.ui.components.TV_LOAD_ERROR_RETRY_TAG
import dev.jdtech.jellyfin.ce.ui.components.TvLoadError
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.viewmodels.AboutState
import java.net.SocketTimeoutException
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Ephyra focus ring (VISION §6) on the controls that had tv-material's default look or a white
 * border (#540), and the About buttons inside the bottom safe area when focused (#539). At mdpi one
 * dp is one pixel.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp")
class FocusRingTest {
    @get:Rule val rule = createComposeRule()

    private var primary = Color.Unspecified

    private fun isRing(color: Color): Boolean =
        abs(color.red - primary.red) < 0.08f &&
            abs(color.green - primary.green) < 0.08f &&
            abs(color.blue - primary.blue) < 0.08f

    private fun PixelMap.count(area: Rect, test: (Color) -> Boolean): Int {
        var found = 0
        for (x in area.left.roundToInt().coerceAtLeast(0) until
            area.right.roundToInt().coerceAtMost(width)) {
            for (y in area.top.roundToInt().coerceAtLeast(0) until
                area.bottom.roundToInt().coerceAtMost(height)) {
                if (test(this[x, y])) found++
            }
        }
        return found
    }

    private fun bounds(tag: String): Rect {
        val box = rule.onNode(hasTestTag(tag) and isFocusable()).getBoundsInRoot()
        return Rect(box.left.value, box.top.value, box.right.value, box.bottom.value)
    }

    @Test
    fun theFocusedRetryButtonHasTheRing() {
        rule.setContent {
            FindroidTheme {
                primary = MaterialTheme.colorScheme.primary
                Box(Modifier.fillMaxSize()) {
                    TvLoadError(
                        error = SocketTimeoutException("failed to connect"),
                        onRetry = {},
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
        rule.waitForIdle()
        val retry = hasTestTag(TV_LOAD_ERROR_RETRY_TAG) and isFocusable()
        rule.onNode(retry).assertIsFocused()

        val pixels = rule.onRoot().captureToImage().toPixelMap()
        val ring = pixels.count(bounds(TV_LOAD_ERROR_RETRY_TAG).inflate(12f), ::isRing)
        assertTrue("$ring ring-coloured pixels around Retry", ring > 100)
    }

    @Test
    fun theFocusedLicenseTextHasTheRingNotAWhiteBorder() {
        val library =
            Libs.Builder()
                .withJson(ApplicationProvider.getApplicationContext(), R.raw.aboutlibraries)
                .build()
                .libraries
                .first()
        rule.setContent {
            FindroidTheme {
                primary = MaterialTheme.colorScheme.primary
                LicenseScreen(uniqueId = library.uniqueId)
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(LICENSE_TEXT_TAG).assertIsFocused()

        val box = bounds(LICENSE_TEXT_TAG)
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        // The left edge of the box, 3 px wide, half its height.
        val edge = Rect(box.left, box.center.y - 50f, box.left + 3f, box.center.y + 50f)
        assertEquals(300, pixels.count(edge, ::isRing))
        // #539: the box ends inside the bottom safe area.
        assertTrue("the box ends at ${box.bottom}", box.bottom <= 540f - TvSafeArea.vertical.value)
    }

    @Test
    fun theFocusedAboutButtonsStayInsideTheSafeArea() {
        rule.setContent {
            FindroidTheme {
                primary = MaterialTheme.colorScheme.primary
                AboutScreenLayout(
                    state = AboutState(info = previewInfo),
                    onLicensesClick = {},
                    onDeviceProfileClick = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(ABOUT_LICENSES_TAG).assertIsFocused()

        val button = bounds(ABOUT_LICENSES_TAG)
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        val ring = pixels.count(button.inflate(12f), ::isRing)
        assertTrue("$ring ring-coloured pixels around Open-source licenses", ring > 100)

        // Below the safe area, under the button: only the page's background.
        val background = pixels[pixels.width - 1, pixels.height - 1]
        val safeBottom = 540f - TvSafeArea.vertical.value
        val margin = Rect(button.left - 20f, safeBottom, button.right + 20f, 540f)
        assertEquals(0, pixels.count(margin) { it != background })
    }
}
