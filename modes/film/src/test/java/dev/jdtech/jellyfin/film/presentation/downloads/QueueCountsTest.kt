package dev.jdtech.jellyfin.film.presentation.downloads

import dev.jdtech.jellyfin.core.presentation.downloader.DownloadProgress
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadStatus
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import org.junit.Assert.assertEquals
import org.junit.Test

/** #546: the Downloads header counts failed downloads apart from the ones still on their way. */
class QueueCountsTest {
    private fun entries(vararg statuses: DownloadStatus) =
        statuses.map { status ->
            ActiveDownload(
                item = dummyEpisode,
                progress = DownloadProgress(status = status),
                downloadId = null,
            )
        }

    @Test
    fun `every entry failed`() {
        val counts = entries(DownloadStatus.FAILED, DownloadStatus.FAILED).queueCounts()

        assertEquals(QueueCounts(active = 0, failed = 2), counts)
    }

    @Test
    fun `running, waiting and failed entries`() {
        val counts =
            entries(
                    DownloadStatus.DOWNLOADING,
                    DownloadStatus.PAUSED,
                    DownloadStatus.QUEUED,
                    DownloadStatus.FAILED,
                )
                .queueCounts()

        assertEquals(QueueCounts(active = 3, failed = 1), counts)
    }

    @Test
    fun `nothing failed`() {
        val counts =
            entries(DownloadStatus.PENDING, DownloadStatus.DOWNLOADING, DownloadStatus.COMPLETED)
                .queueCounts()

        assertEquals(QueueCounts(active = 3, failed = 0), counts)
    }
}
