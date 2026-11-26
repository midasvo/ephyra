package dev.jdtech.jellyfin.repository

/**
 * Checks whether smart downloads should queue or prune episodes after user-data sync. Implemented
 * by `SmartDownloads` in `downloads`; bound to [None] on TV where downloads do not exist (TV ADR
 * 0007).
 */
interface SmartDownloadChecker {
    suspend fun checkAll()

    object None : SmartDownloadChecker {
        override suspend fun checkAll() = Unit
    }
}
