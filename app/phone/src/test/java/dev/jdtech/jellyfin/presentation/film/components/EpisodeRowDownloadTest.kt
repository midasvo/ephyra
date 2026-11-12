package dev.jdtech.jellyfin.presentation.film.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadProgress
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadStatus
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #373: an episode row of the season page offers Download only when the server allows the episode
 * to be downloaded. A download that is already there, running or failed keeps its control.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EpisodeRowDownloadTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val allowed = dummyEpisode.copy(canDownload = true)
    private val notAllowed = dummyEpisode.copy(canDownload = false)

    @Test
    fun `an allowed episode offers Download`() {
        assertNotNull(episodeRowDownloadClick(allowed, removalPending = false) {})
    }

    @Test
    fun `an episode the server does not allow offers no Download`() {
        assertNull(episodeRowDownloadClick(notAllowed, removalPending = false) {})
    }

    @Test
    fun `an episode whose removal waits on Undo offers no Download`() {
        assertNull(episodeRowDownloadClick(allowed, removalPending = true) {})
    }

    @Test
    fun `the row of an episode the server does not allow has no Download button`() {
        showRow(DownloadProgress())

        rule
            .onAllNodesWithContentDescription(text(CoreR.string.download_button_description))
            .assertCountEquals(0)
    }

    @Test
    fun `a downloaded episode the server does not allow still offers Remove`() {
        showRow(DownloadProgress(status = DownloadStatus.COMPLETED, progress = 1f))

        rule.onNodeWithContentDescription(text(CoreR.string.remove_download)).assertExists()
    }

    @Test
    fun `a running download of an episode the server does not allow still offers Cancel`() {
        showRow(DownloadProgress(status = DownloadStatus.DOWNLOADING, progress = 0.4f))

        rule.onNodeWithContentDescription(text(CoreR.string.cancel_download)).assertExists()
    }

    @Test
    fun `a failed download of an episode the server does not allow still offers Retry`() {
        showRow(DownloadProgress(status = DownloadStatus.FAILED))

        rule.onNodeWithContentDescription(text(CoreR.string.retry_download)).assertExists()
    }

    private fun showRow(progress: DownloadProgress) {
        rule.setContent {
            EpisodeDownloadControl(
                downloadProgress = progress,
                onDownloadClick = episodeRowDownloadClick(notAllowed, removalPending = false) {},
                onCancelClick = {},
                onRetryClick = {},
                onRemoveClick = {},
            )
        }
    }

    private fun text(id: Int) = rule.activity.getString(id)
}
