package dev.jdtech.jellyfin.presentation.film

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.width
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyHomeSection
import dev.jdtech.jellyfin.core.presentation.dummy.dummyServer
import dev.jdtech.jellyfin.film.presentation.home.HomeState
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.utils.LocalOfflineMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h914dp")
class HomeScreenLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var state by mutableStateOf(HomeState())

    private fun setLayout(initial: HomeState) {
        state = initial
        rule.setContent {
            FindroidTheme {
                CompositionLocalProvider(LocalOfflineMode provides false) {
                    HomeScreenLayout(state = state, onAction = {})
                }
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
    }

    private fun string(id: Int) = rule.activity.getString(id)

    private val loaded =
        HomeState(
            server = dummyServer.copy(name = "Stable Demo"),
            resumeSection = dummyHomeSection,
            isLoading = false,
        )

    /** #391: a reload showed the pull indicator and a second spinner in the header. */
    @Test
    fun aReloadShowsOneLoadingIndicator() {
        setLayout(
            HomeState(server = dummyServer, resumeSection = dummyHomeSection, isLoading = true)
        )

        rule
            .onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertCountEquals(1)
    }

    /** #391: the first load after a cold start, with nothing on screen yet, still shows one. */
    @Test
    fun theFirstLoadShowsOneLoadingIndicator() {
        setLayout(HomeState(server = dummyServer, isLoading = true))

        rule
            .onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertCountEquals(1)
    }

    /**
     * #392: a failed refresh added an error and a retry button to the header, which squeezed the
     * server name to the logo and a dot. The header keeps its width; the error is a card instead.
     */
    @Test
    fun aFailedRefreshLeavesTheServerNameItsRoom() {
        setLayout(loaded)
        val nameWidth = rule.onNodeWithText("Stable Demo").getBoundsInRoot().width

        state = loaded.copy(error = Exception("Failed to connect to /10.0.2.2:8096"))
        rule.mainClock.advanceTimeBy(1_000)

        assertEquals(nameWidth, rule.onNodeWithText("Stable Demo").getBoundsInRoot().width)
        rule.onNodeWithText(string(CoreR.string.error_loading_data)).assertIsDisplayed()
    }

    /** #392: the card keeps what #377 gave the Home error: details in words, Retry, Share, Close. */
    @Test
    fun theErrorCardOpensTheErrorDetails() {
        setLayout(loaded.copy(error = Exception("Failed to connect to /10.0.2.2:8096")))

        rule.onNodeWithContentDescription(string(CoreR.string.show_stacktrace)).performClick()
        rule.mainClock.advanceTimeBy(1_000)

        rule.onNodeWithText(string(CoreR.string.retry)).assertIsDisplayed()
        rule.onNodeWithText(string(CoreR.string.share)).assertIsDisplayed()
        rule.onNodeWithText(string(CoreR.string.close)).assertIsDisplayed()
        // No stack trace on screen: its frames name this test class.
        rule.onAllNodes(hasText("HomeScreenLayoutTest", substring = true)).assertCountEquals(0)
    }
}
