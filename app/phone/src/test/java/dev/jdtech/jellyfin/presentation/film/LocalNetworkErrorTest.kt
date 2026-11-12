package dev.jdtech.jellyfin.presentation.film

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyHomeSection
import dev.jdtech.jellyfin.core.presentation.dummy.dummyServer
import dev.jdtech.jellyfin.core.presentation.localnetwork.LocalLocalNetworkBlock
import dev.jdtech.jellyfin.core.presentation.localnetwork.LocalNetworkBlock
import dev.jdtech.jellyfin.film.presentation.home.HomeState
import dev.jdtech.jellyfin.presentation.components.ErrorDialog
import dev.jdtech.jellyfin.presentation.film.components.ErrorCard
import dev.jdtech.jellyfin.presentation.film.components.ErrorState
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.utils.LocalOfflineMode
import dev.jdtech.jellyfin.setup.R as SetupR
import java.net.SocketTimeoutException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CE (#571): with local network access revoked on Android 17, a server on the local network only
 * gave "Error loading data". The error card, the error page and the details dialog now say that
 * access is off and offer to allow it, and the screen loads again by itself once access is back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h914dp")
class LocalNetworkErrorTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var blocked by mutableStateOf(true)
    private var error: Exception by mutableStateOf(SocketTimeoutException("failed to connect"))
    private var requests = 0
    private var refreshes = 0
    private var retries = 0

    private fun string(id: Int, vararg args: Any) = rule.activity.getString(id, *args)

    private val deniedText: String
        get() =
            string(SetupR.string.ce_local_network_denied) +
                " " +
                string(SetupR.string.ce_local_network_denied_where, "Nearby devices")

    private fun setContent(fontScale: Float = 1f, content: @Composable () -> Unit) {
        rule.setContent {
            val block =
                LocalNetworkBlock(
                    blocked = blocked,
                    settingName = "Nearby devices",
                    refresh = { refreshes++ },
                    request = { requests++ },
                )
            val density = LocalDensity.current
            FindroidTheme {
                CompositionLocalProvider(
                    LocalLocalNetworkBlock provides block,
                    LocalOfflineMode provides false,
                    LocalDensity provides Density(density.density, fontScale),
                ) {
                    content()
                }
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    private fun setErrorState() = setContent {
        ErrorState(error = error, onRetryClick = { retries++ })
    }

    @Test
    fun theErrorPageNamesThePermissionAndOffersToAllowIt() {
        setErrorState()

        rule.onNodeWithText(deniedText).assertIsDisplayed()
        rule.onNodeWithText(string(CoreR.string.retry)).assertIsDisplayed()
        rule.onAllNodes(hasText(string(CoreR.string.show_stacktrace))).assertCountEquals(0)

        rule.onNodeWithText(string(SetupR.string.ce_local_network_allow)).performClick()
        assertEquals(1, requests)
        assertEquals(0, retries)
    }

    @Test
    fun aServerThatIsNotBlockedKeepsTheUsualPage() {
        blocked = false
        setErrorState()

        rule.onNodeWithText(string(CoreR.string.error_loading_data_body)).assertIsDisplayed()
        rule.onNodeWithText(string(CoreR.string.show_stacktrace)).assertIsDisplayed()
        rule.onAllNodes(hasText(string(SetupR.string.ce_local_network_allow))).assertCountEquals(0)
        assertEquals(0, retries)
    }

    @Test
    fun anAnswerFromTheServerIsNotBlamedOnThePermission() {
        // Access is denied, but the server answered: HTTP 500 is not the permission's doing.
        error = InvalidStatusException(500, null)
        setErrorState()

        rule.onNodeWithText(string(CoreR.string.show_stacktrace)).assertIsDisplayed()
        rule.onAllNodes(hasText(string(SetupR.string.ce_local_network_allow))).assertCountEquals(0)
    }

    @Test
    fun aConnectionErrorLooksAtThePermissionAgain() {
        blocked = false
        setErrorState()

        // The server may have changed since the last resume.
        assertEquals(1, refreshes)
    }

    @Test
    fun accessComingBackLoadsAgainOnce() {
        setErrorState()
        assertEquals(0, retries)

        blocked = false
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()

        assertEquals(1, retries)
    }

    @Test
    fun aRetryThatFailsForAnotherReasonShowsThatErrorAndStops() {
        setErrorState()
        blocked = false
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(1, retries)

        // The retry reached the network and failed again: the server is down as well.
        error = SocketTimeoutException("failed to connect, again")
        rule.mainClock.advanceTimeBy(5_000)
        rule.waitForIdle()
        error = InvalidStatusException(503, null)
        rule.mainClock.advanceTimeBy(5_000)
        rule.waitForIdle()

        assertEquals(1, retries)
        rule.onNodeWithText(string(CoreR.string.error_loading_data_body)).assertIsDisplayed()
        rule.onNodeWithText(string(CoreR.string.show_stacktrace)).assertIsDisplayed()
    }

    @Test
    fun theErrorCardNamesThePermissionAndOffersToAllowIt() {
        setContent {
            ErrorCard(onShowStacktrace = {}, onRetryClick = { retries++ }, error = error)
        }

        rule.onNodeWithText(string(SetupR.string.ce_local_network_off)).assertIsDisplayed()
        rule.onNodeWithContentDescription(string(CoreR.string.retry)).assertIsDisplayed()
        rule.onNodeWithText(string(SetupR.string.ce_local_network_allow)).performClick()
        assertEquals(1, requests)

        blocked = false
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()

        assertEquals(1, retries)
        rule.onNodeWithText(string(CoreR.string.error_loading_data)).assertIsDisplayed()
        rule.onAllNodes(hasText(string(SetupR.string.ce_local_network_allow))).assertCountEquals(0)
    }

    @Test
    fun theDetailsDialogNamesThePermissionAndKeepsShareAndClose() {
        var dismissed = 0
        setContent {
            ErrorDialog(exception = error, onDismissRequest = { dismissed++ }, onRetry = {})
        }

        rule.onNodeWithText(deniedText).assertIsDisplayed()
        rule.onNodeWithText(string(CoreR.string.share)).assertIsDisplayed()
        rule.onNodeWithText(string(CoreR.string.close)).assertIsDisplayed()

        rule.onNodeWithText(string(SetupR.string.ce_local_network_allow)).performClick()
        assertEquals(1, requests)
        assertEquals(1, dismissed)
    }

    /** Home lays the card over its rows and moves them down by the card's real height. */
    @Test
    fun homeRowsStartUnderTheTallerCard() {
        assertRowsStartUnderTheCard(fontScale = 1f)
    }

    @Test
    fun homeRowsStartUnderTheCardAtTwiceTheFontSize() {
        assertRowsStartUnderTheCard(fontScale = 2f)
    }

    private fun assertRowsStartUnderTheCard(fontScale: Float) {
        val state =
            HomeState(
                server = dummyServer,
                resumeSection = dummyHomeSection,
                isLoading = false,
                error = error,
            )
        setContent(fontScale) { HomeScreenLayout(state = state, onAction = {}) }
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()

        val allow = rule.onNodeWithText(string(SetupR.string.ce_local_network_allow))
        allow.assertIsDisplayed()
        val cardBottom = allow.getBoundsInRoot().bottom
        val rowTop = rule.onNodeWithText("Continue watching").getBoundsInRoot().top
        assertTrue("rows at $rowTop start above the card's end, $cardBottom", rowTop >= cardBottom)
    }
}
