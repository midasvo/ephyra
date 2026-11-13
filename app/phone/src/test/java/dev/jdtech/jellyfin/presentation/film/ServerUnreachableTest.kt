package dev.jdtech.jellyfin.presentation.film

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderState
import dev.jdtech.jellyfin.core.presentation.dummy.dummyHomeSection
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.core.presentation.dummy.dummyServer
import dev.jdtech.jellyfin.core.presentation.localnetwork.LocalLocalNetworkBlock
import dev.jdtech.jellyfin.core.presentation.localnetwork.LocalNetworkBlock
import dev.jdtech.jellyfin.film.presentation.collection.CollectionAction
import dev.jdtech.jellyfin.film.presentation.collection.CollectionState
import dev.jdtech.jellyfin.film.presentation.home.HomeAction
import dev.jdtech.jellyfin.film.presentation.home.HomeState
import dev.jdtech.jellyfin.models.FindroidTrailer
import dev.jdtech.jellyfin.presentation.film.components.ErrorState
import dev.jdtech.jellyfin.presentation.film.components.ItemButtonsBar
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.utils.LocalOfflineMode
import dev.jdtech.jellyfin.presentation.utils.LocalServerStatus
import dev.jdtech.jellyfin.presentation.utils.ServerStatusUi
import dev.jdtech.jellyfin.setup.R as SetupR
import java.net.ConnectException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CE (#281): while the server does not answer, the screens say so in a banner that leads to
 * Downloads, instead of "Error loading data" and no way on. Nothing is blocked by it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h914dp")
class ServerUnreachableTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var status by mutableStateOf(ServerStatusUi())
    private var homeState by mutableStateOf(HomeState())
    private var error: Exception by mutableStateOf(ConnectException("Failed to connect"))
    private var localNetworkBlocked by mutableStateOf(false)
    private var offlineMode by mutableStateOf(false)
    private var downloadsOpened = 0
    private var checks = 0
    private var retries = 0

    private fun string(id: Int) = rule.activity.getString(id)

    private val unreachableText
        get() = string(CoreR.string.download_error_server_unreachable)

    private val downloadsLine
        get() = string(CoreR.string.ce_server_unreachable_downloads)

    private fun unreachable(confirmed: Boolean = true, hasDownloads: Boolean = true) =
        ServerStatusUi(
            unreachable = true,
            confirmedUnreachable = confirmed,
            hasDownloads = hasDownloads,
            check = { checks++ },
            openDownloads = { downloadsOpened++ },
        )

    private val loaded =
        HomeState(
            server = dummyServer.copy(name = "Stable Demo"),
            resumeSection = dummyHomeSection,
            isLoading = false,
        )

    private fun setContent(content: @Composable () -> Unit) {
        rule.setContent {
            FindroidTheme {
                CompositionLocalProvider(
                    LocalOfflineMode provides offlineMode,
                    LocalServerStatus provides status,
                    LocalLocalNetworkBlock provides
                        LocalNetworkBlock(blocked = localNetworkBlocked, request = {}),
                ) {
                    content()
                }
            }
        }
        settle()
    }

    private fun settle() {
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    private fun setHome(initial: HomeState) {
        homeState = initial
        setContent {
            HomeScreenLayout(
                state = homeState,
                onAction = { if (it is HomeAction.OnRetryClick) retries++ },
            )
        }
    }

    private fun setErrorState() = setContent {
        ErrorState(error = error, onRetryClick = { retries++ })
    }

    private fun assertAbsent(text: String) = rule.onAllNodes(hasText(text)).assertCountEquals(0)

    // Home

    @Test
    fun homeShowsNoBannerWhileTheServerAnswers() {
        setHome(loaded)

        assertAbsent(unreachableText)
    }

    @Test
    fun homeShowsTheBannerWithoutAnyFailedLoad() {
        status = unreachable()
        setHome(loaded)

        rule.onNodeWithText(unreachableText).assertIsDisplayed()
        rule.onNodeWithText(downloadsLine).assertIsDisplayed()
        // The rows stay: nothing is taken away.
        rule.onNodeWithText("Stable Demo").assertIsDisplayed()
    }

    @Test
    fun theBannerLeadsToDownloadsAndRetries() {
        status = unreachable()
        setHome(loaded)

        rule.onNodeWithText(string(CoreR.string.title_download)).performClick()
        assertEquals(1, downloadsOpened)

        rule.onNodeWithText(string(CoreR.string.retry)).performClick()
        // Retry asks the server again and loads the screen again.
        assertEquals(1, checks)
        assertEquals(1, retries)
    }

    @Test
    fun theBannerStandsInForTheRedCardWhenTheLoadFailedForThatReason() {
        status = unreachable()
        setHome(loaded.copy(error = ConnectException("Failed to connect")))

        rule.onNodeWithText(unreachableText).assertIsDisplayed()
        assertAbsent(string(CoreR.string.error_loading_data))
    }

    @Test
    fun anAnswerWithAServerErrorKeepsTheRedCard() {
        // HTTP 500 came from somewhere: that is an error to show, not "can't reach".
        status = unreachable()
        setHome(loaded.copy(error = InvalidStatusException(500, null)))

        rule.onNodeWithText(string(CoreR.string.error_loading_data)).assertIsDisplayed()
        assertAbsent(unreachableText)
    }

    @Test
    fun withoutDownloadsTheBannerOnlySaysSoAndOffersRetry() {
        status = unreachable(hasDownloads = false)
        setHome(loaded)

        rule.onNodeWithText(unreachableText).assertIsDisplayed()
        rule.onNodeWithText(string(CoreR.string.retry)).assertIsDisplayed()
        assertAbsent(downloadsLine)
        assertAbsent(string(CoreR.string.title_download))
    }

    @Test
    fun theRowsStartUnderTheBanner() {
        status = unreachable()
        setHome(loaded)

        val bannerBottom =
            rule.onNodeWithText(string(CoreR.string.title_download)).getBoundsInRoot().bottom
        val rowTop = rule.onNodeWithText("Continue watching").getBoundsInRoot().top
        assertTrue("rows at $rowTop, banner ends at $bannerBottom", rowTop > bannerBottom)
    }

    @Test
    fun theBannerGoesWhenTheServerAnswersAndHomeLoadsAgainOnce() {
        status = unreachable()
        setHome(loaded.copy(error = ConnectException("Failed to connect")))
        assertEquals(0, retries)

        status = ServerStatusUi()
        settle()

        assertAbsent(unreachableText)
        assertEquals(1, retries)

        // The reload failed for another reason: that error shows, and nothing retries again.
        homeState = loaded.copy(error = InvalidStatusException(500, null))
        settle()
        rule.onNodeWithText(string(CoreR.string.error_loading_data)).assertIsDisplayed()
        assertEquals(1, retries)
    }

    @Test
    fun aBannerWithNothingFailedReloadsNothingWhenTheServerReturns() {
        status = unreachable()
        setHome(loaded)

        status = ServerStatusUi()
        settle()

        assertEquals(0, retries)
    }

    @Test
    fun anEmptyHomeThatFailedShowsOnePageNotTheBannerToo() {
        status = unreachable()
        setHome(
            HomeState(
                server = dummyServer,
                isLoading = false,
                error = ConnectException("Failed to connect"),
            )
        )

        // The Home cache is off or empty: the page says it once, with the way to Downloads.
        rule.onAllNodes(hasText(unreachableText)).assertCountEquals(1)
        rule.onNodeWithText(downloadsLine).assertIsDisplayed()
        rule.onNodeWithText(string(CoreR.string.title_download)).performClick()
        assertEquals(1, downloadsOpened)
    }

    @Test
    fun theLocalNetworkPermissionKeepsItsOwnCard() {
        // Android 17 with local network access off (#571): that card names the permission.
        localNetworkBlocked = true
        status = unreachable()
        setHome(loaded.copy(error = ConnectException("Failed to connect")))

        rule.onNodeWithText(string(SetupR.string.ce_local_network_off)).assertIsDisplayed()
        assertAbsent(unreachableText)
    }

    // Favorites

    private fun setFavorites(state: CollectionState) = setContent {
        CollectionScreenLayout(
            collectionName = "Favorites",
            state = state,
            onAction = { if (it is CollectionAction.OnRetryClick) retries++ },
            emptyTitle = "No favorites",
            showNavigationIcon = false,
            serverBanner = true,
        )
    }

    @Test
    fun favoritesWithNothingInThemStillSayTheServerIsAway() {
        status = unreachable()
        setFavorites(CollectionState(isLoading = false))

        rule.onNodeWithText(unreachableText).assertIsDisplayed()
        rule.onNodeWithText("No favorites").assertIsDisplayed()
        rule.onNodeWithText(string(CoreR.string.title_download)).performClick()
        assertEquals(1, downloadsOpened)
    }

    @Test
    fun favoritesThatFailedWithNothingToShowSayItOnce() {
        status = unreachable()
        setFavorites(CollectionState(isLoading = false, error = ConnectException("Failed")))

        rule.onAllNodes(hasText(unreachableText)).assertCountEquals(1)
    }

    // The error page of the other screens

    @Test
    fun theErrorPageLeadsToDownloadsWhileTheServerIsAway() {
        status = unreachable()
        setErrorState()

        rule.onNodeWithText(unreachableText).assertIsDisplayed()
        rule.onNodeWithText(downloadsLine).assertIsDisplayed()
        assertAbsent(string(CoreR.string.show_stacktrace))

        rule.onNodeWithText(string(CoreR.string.title_download)).performClick()
        assertEquals(1, downloadsOpened)

        rule.onNodeWithText(string(CoreR.string.retry)).performClick()
        assertEquals(1, checks)
        assertEquals(1, retries)
    }

    @Test
    fun theErrorPageWithoutDownloadsOffersRetryOnly() {
        status = unreachable(hasDownloads = false)
        setErrorState()

        rule.onNodeWithText(unreachableText).assertIsDisplayed()
        rule.onNodeWithText(string(CoreR.string.retry)).assertIsDisplayed()
        assertAbsent(string(CoreR.string.title_download))
        assertAbsent(downloadsLine)
    }

    @Test
    fun theErrorPageOnTheDownloadsTabDoesNotPointAtItself() {
        status = unreachable().copy(openDownloads = null)
        setErrorState()

        rule.onNodeWithText(unreachableText).assertIsDisplayed()
        assertAbsent(string(CoreR.string.title_download))
    }

    @Test
    fun theErrorPageIsTheUsualOneWhileTheServerAnswers() {
        setErrorState()

        rule.onNodeWithText(string(CoreR.string.error_loading_data)).assertIsDisplayed()
        rule.onNodeWithText(string(CoreR.string.show_stacktrace)).assertIsDisplayed()
        assertAbsent(string(CoreR.string.title_download))
    }

    @Test
    fun anErrorTheServerSentKeepsTheUsualPageWhileTheBannerIsUp() {
        status = unreachable()
        error = InvalidStatusException(500, null)
        setErrorState()

        rule.onNodeWithText(string(CoreR.string.error_loading_data)).assertIsDisplayed()
        rule.onNodeWithText(string(CoreR.string.show_stacktrace)).assertIsDisplayed()
    }

    @Test
    fun theErrorPageLoadsAgainOnceWhenTheServerReturns() {
        status = unreachable()
        setErrorState()
        assertEquals(0, retries)

        status = ServerStatusUi()
        settle()
        assertEquals(1, retries)

        // Still failing, for whatever reason: the usual page, and no loop.
        settle()
        assertEquals(1, retries)
        rule.onNodeWithText(string(CoreR.string.error_loading_data)).assertIsDisplayed()
    }

    // Download and trailer buttons

    private fun setButtons() = setContent {
        ItemButtonsBar(
            item =
                dummyMovie.copy(
                    canDownload = true,
                    trailers = listOf(FindroidTrailer(name = null, url = "https://example.org/t")),
                ),
            onPlayClick = {},
            onMarkAsPlayedClick = {},
            onMarkAsFavoriteClick = {},
            onDownloadClick = {},
            onDownloadCancelClick = {},
            onDownloadDeleteClick = {},
            onTrailerClick = {},
            downloaderState = DownloaderState(),
            isDownloaded = false,
        )
    }

    private fun assertButtons(shown: Boolean) {
        val count = if (shown) 1 else 0
        rule
            .onAllNodes(hasContentDescription(string(CoreR.string.download_button_description)))
            .assertCountEquals(count)
        rule
            .onAllNodes(hasContentDescription(string(CoreR.string.trailer_button_description)))
            .assertCountEquals(count)
    }

    @Test
    fun downloadAndTrailerShowWhileTheServerAnswers() {
        setButtons()

        assertButtons(shown = true)
    }

    @Test
    fun oneFailedProbeAloneDoesNotTakeTheButtonsAway() {
        // A slow server looks like this for a moment: the banner may show, the buttons stay.
        status = unreachable(confirmed = false)
        setButtons()

        assertButtons(shown = true)
    }

    @Test
    fun theButtonsGoOnceTheServerIsKnownToBeAwayAndComeBack() {
        status = unreachable(confirmed = true)
        setButtons()
        assertButtons(shown = false)
        // Play stays: a download plays without the server.
        rule.onNodeWithContentDescription(string(CoreR.string.play_button_description)).assertExists()

        status = ServerStatusUi()
        settle()
        assertButtons(shown = true)
    }

    @Test
    fun offlineModeHidesTheButtonsAsBefore() {
        offlineMode = true
        setButtons()

        assertButtons(shown = false)
    }
}
