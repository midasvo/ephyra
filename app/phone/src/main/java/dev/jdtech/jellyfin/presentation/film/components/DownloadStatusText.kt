package dev.jdtech.jellyfin.presentation.film.components

import androidx.annotation.StringRes
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadStatus
import dev.jdtech.jellyfin.utils.download.DownloadStatus as EngineStatus

/**
 * CE (#558): the words for a download's state, the same in the Downloads row and on the item page.
 * A paused download is always waiting for the network: no connection, or a metered or roaming one
 * the download may not use. Null for no download.
 */
@StringRes
internal fun downloadStatusTextRes(status: DownloadStatus): Int? =
    when (status) {
        DownloadStatus.QUEUED -> CoreR.string.download_queued
        DownloadStatus.PENDING -> CoreR.string.download_pending
        DownloadStatus.DOWNLOADING -> CoreR.string.download_downloading
        DownloadStatus.PAUSED -> CoreR.string.download_waiting_for_network
        DownloadStatus.COMPLETED -> CoreR.string.download_completed
        DownloadStatus.FAILED -> CoreR.string.download_failed
        DownloadStatus.NONE -> null
    }

/** The item page's status (the engine's integers) as the Downloads screen's. */
internal fun downloadStatusOf(engineStatus: Int): DownloadStatus =
    when (engineStatus) {
        EngineStatus.PENDING -> DownloadStatus.PENDING
        EngineStatus.RUNNING -> DownloadStatus.DOWNLOADING
        EngineStatus.PAUSED -> DownloadStatus.PAUSED
        EngineStatus.SUCCESSFUL -> DownloadStatus.COMPLETED
        EngineStatus.FAILED -> DownloadStatus.FAILED
        else -> DownloadStatus.NONE
    }
