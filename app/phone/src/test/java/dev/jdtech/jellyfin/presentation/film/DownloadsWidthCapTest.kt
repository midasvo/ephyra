package dev.jdtech.jellyfin.presentation.film

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.Constants
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadProgress
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadStatus
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.film.presentation.downloads.ActiveDownload
import dev.jdtech.jellyfin.film.presentation.downloads.DownloadsState
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #434: a row on the Downloads screen never exceeds 720 dp (docs/tablet/4-design.md §5), also
 * the items that span the whole grid (the storage summary, the Downloading group and its header),
 * which the grid measures at the full width of the line. They stay at the start, in line with the
 * columns below.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class DownloadsWidthCapTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val movies =
        List(4) { index -> dummyMovie.copy(id = UUID.randomUUID(), name = "Movie ${index + 1}") }

    // The grid's 16 dp content padding plus the 720 dp cap, and a little for rounding.
    private val capRightEdge = 16.dp + 720.dp + 1.dp

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun `the full-width items stop at 720 dp on a 1280 dp window`() {
        show()

        // The Downloading header's speed sits at the end of the header, the cancel button at the
        // end of the active row, the free space at the end of the storage summary's legend.
        val speed = rule.onNodeWithText("/s", substring = true).getUnclippedBoundsInRoot()
        val cancel =
            rule
                .onNodeWithContentDescription(
                    rule.activity.getString(CoreR.string.cancel_download)
                )
                .getUnclippedBoundsInRoot()
        assertTrue("speed ends at ${speed.right}", speed.right <= capRightEdge)
        assertTrue("cancel ends at ${cancel.right}", cancel.right <= capRightEdge)
        // Aligned to the start like the columns below, not centred.
        assertTrue("cancel starts at ${cancel.left}", cancel.left < 720.dp)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun `the finished downloads stay in two columns`() {
        show()

        val titles = movies.take(2).map { movie ->
            rule.onNodeWithText(movie.name).getUnclippedBoundsInRoot()
        }
        assertTrue("second column starts at ${titles[1].left}", titles[1].left > 600.dp)
    }

    @Test
    @Config(qualifiers = "w915dp-h412dp-land")
    fun `a single column is capped too`() {
        show()

        val cancel =
            rule
                .onNodeWithContentDescription(
                    rule.activity.getString(CoreR.string.cancel_download)
                )
                .getUnclippedBoundsInRoot()
        assertTrue("cancel ends at ${cancel.right}", cancel.right <= capRightEdge)
    }

    private fun show() {
        rule.setContent {
            FindroidTheme {
                DownloadsScreenLayout(
                    state =
                        DownloadsState(
                            queueItems =
                                listOf(
                                    ActiveDownload(
                                        item = dummyEpisode,
                                        progress =
                                            DownloadProgress(
                                                status = DownloadStatus.DOWNLOADING,
                                                progress = 0.33f,
                                            ),
                                        downloadId = 1L,
                                        bytesDownloaded = 164_000_000L,
                                        totalBytes = 498_000_000L,
                                        bytesPerSecond = 6_400_000L,
                                    )
                                ),
                            sections =
                                listOf(
                                    CollectionSection(
                                        id = Constants.FAVORITE_TYPE_MOVIES,
                                        name = UiText.StringResource(CoreR.string.movies_label),
                                        items = movies,
                                    )
                                ),
                            moviesBytes = 1_500_000_000L,
                            storageUsedBytes = 1_500_000_000L,
                            storageFreeBytes = 10_000_000_000L,
                            storageTotalBytes = 64_000_000_000L,
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
