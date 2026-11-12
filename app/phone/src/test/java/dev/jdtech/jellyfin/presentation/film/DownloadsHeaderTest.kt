package dev.jdtech.jellyfin.presentation.film

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadProgress
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadStatus
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.film.presentation.downloads.ActiveDownload
import dev.jdtech.jellyfin.film.presentation.downloads.DownloadsState
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #546: the Downloads header counts what is still on its way as "Downloading" and the failed
 * downloads apart, on the phone and in the capped row of a tablet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadsHeaderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val downloading
        get() = rule.activity.getString(CoreR.string.downloads_downloading)

    @Test
    fun `every download failed`() {
        show(List(7) { DownloadStatus.FAILED })

        rule.onNodeWithText("7 failed").assertIsDisplayed()
    }

    @Test
    fun `running, waiting and failed downloads`() {
        show(
            listOf(
                DownloadStatus.DOWNLOADING,
                DownloadStatus.PAUSED,
                DownloadStatus.FAILED,
                DownloadStatus.FAILED,
                DownloadStatus.FAILED,
            )
        )

        rule.onNodeWithText("$downloading · 2 · 3 failed").assertIsDisplayed()
    }

    @Test
    fun `nothing failed`() {
        show(List(5) { DownloadStatus.DOWNLOADING })

        rule.onNodeWithText("$downloading · 5").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun `the tablet's header reads the same`() {
        show(listOf(DownloadStatus.QUEUED, DownloadStatus.FAILED))

        rule.onNodeWithText("$downloading · 1 · 1 failed").assertIsDisplayed()
    }

    private fun show(statuses: List<DownloadStatus>) {
        rule.setContent {
            FindroidTheme {
                DownloadsScreenLayout(
                    state =
                        DownloadsState(
                            queueItems =
                                statuses.map { status ->
                                    ActiveDownload(
                                        item = dummyEpisode.copy(id = UUID.randomUUID()),
                                        progress = DownloadProgress(status = status),
                                        downloadId = 1L,
                                    )
                                }
                        ),
                    onItemClick = {},
                    onShowClick = {},
                    onItemsDelete = {},
                    onSortOrderChange = {},
                    onCancelDownload = {},
                    onDismissDownload = {},
                    onRetryDownload = {},
                    onRetryClick = {},
                    onBrowseLibraryClick = {},
                    onStorageClick = {},
                )
            }
        }
    }
}
