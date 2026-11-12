package dev.jdtech.jellyfin.presentation.film.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadProgress
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadStatus
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderState
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.film.presentation.downloads.ActiveDownload
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.utils.download.DownloadStatus as EngineStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #558: a download waiting for the network says so on the item page as in the Downloads row. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadStatusTextTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val waiting
        get() = rule.activity.getString(CoreR.string.download_waiting_for_network)

    @Test
    fun `the item page's card says Waiting for network`() {
        rule.setContent {
            FindroidTheme {
                DownloaderCard(
                    state = DownloaderState(status = EngineStatus.PAUSED),
                    onCancelClick = {},
                    onRetryClick = {},
                )
            }
        }

        rule.onNodeWithText(waiting).assertIsDisplayed()
    }

    @Test
    fun `the Downloads row says Waiting for network`() {
        rule.setContent {
            FindroidTheme {
                ActiveDownloadRow(
                    activeDownload =
                        ActiveDownload(
                            item = dummyEpisode,
                            progress = DownloadProgress(status = DownloadStatus.PAUSED),
                            downloadId = 1L,
                        ),
                    shape = RoundedCornerShape(0),
                    onCancelClick = {},
                    onRetryClick = {},
                )
            }
        }

        rule.onNodeWithText(waiting, substring = true).assertIsDisplayed()
    }

    @Test
    fun `every engine status reads as the Downloads screen's`() {
        val expected =
            mapOf(
                EngineStatus.PENDING to CoreR.string.download_pending,
                EngineStatus.RUNNING to CoreR.string.download_downloading,
                EngineStatus.PAUSED to CoreR.string.download_waiting_for_network,
                EngineStatus.SUCCESSFUL to CoreR.string.download_completed,
                EngineStatus.FAILED to CoreR.string.download_failed,
            )
        expected.forEach { (engineStatus, text) ->
            assertEquals(text, downloadStatusTextRes(downloadStatusOf(engineStatus)))
        }
        assertEquals(null, downloadStatusTextRes(downloadStatusOf(0)))
    }
}
