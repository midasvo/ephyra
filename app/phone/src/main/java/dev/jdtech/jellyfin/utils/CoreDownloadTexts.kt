package dev.jdtech.jellyfin.utils

import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.utils.download.DownloadTexts

/**
 * CE (#901): the texts of core's resources that the download module shows. Lives in `app/phone`
 * because only phone builds link `:downloads` (TV ADR 0007, #1048).
 */
object CoreDownloadTexts : DownloadTexts {
    override fun startError(error: Throwable): UiText = downloadErrorText(error)

    override fun storageUnavailable(): UiText =
        UiText.StringResource(CoreR.string.storage_unavailable)

    override fun notEnoughStorage(needed: String, available: String): UiText =
        UiText.StringResource(CoreR.string.not_enough_storage, needed, available)

    override fun downloadingError(): UiText = UiText.StringResource(CoreR.string.downloading_error)

    override val failedNotificationTitle: Int = CoreR.string.download_failed

    override val failedNotificationIcon: Int = CoreR.drawable.ic_x

    override val progressNotificationIcon: Int = CoreR.drawable.ic_download

    override val waiting: Int = CoreR.string.download_queued

    override val waitingForNetwork: Int = CoreR.string.download_waiting_for_network
}
