package dev.jdtech.jellyfin.repository

import android.content.Context
import dev.jdtech.jellyfin.data.files.DownloadedFilesOnDisk
import dev.jdtech.jellyfin.database.RoomDownloadStore
import dev.jdtech.jellyfin.database.RoomServerStore
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.domain.ports.MediaServer

/**
 * #966: [MediaRepositoryImpl] over the Room stores of [database] and the downloaded files of
 * [context], the same parts Hilt hands it (`MediaRepositoryModule`, `DatabaseModule`). For the
 * tests that build the repository with a real database; the app gets it from Hilt.
 */
fun roomMediaRepository(
    context: Context,
    server: MediaServer,
    database: ServerDatabaseDao,
    currentServer: CurrentServerId,
    nextUpMaxDays: NextUpMaxDays,
    resumeThresholds: ResumeThresholdsStore,
    syncRequester: UserDataSyncRequester = UserDataSyncRequester.NONE,
    knownServerState: KnownServerState = KnownServerState.NONE,
    storedLibraryNames: StoredLibraryNames = StoredLibraryNames.ENGLISH,
): MediaRepositoryImpl {
    val downloads = RoomDownloadStore(database)
    val servers = RoomServerStore(database)
    return MediaRepositoryImpl(
        server = server,
        storedItems = downloads,
        storedLists = downloads,
        storedSources = downloads,
        downloadSources = downloads,
        storedSegments = downloads,
        storedUserData = downloads,
        trackPreferences = servers,
        storedUsers = servers,
        downloadedFiles = DownloadedFilesOnDisk(context),
        currentServer = currentServer,
        nextUpMaxDays = nextUpMaxDays,
        resumeThresholds = resumeThresholds,
        syncRequester = syncRequester,
        knownServerState = knownServerState,
        storedLibraryNames = storedLibraryNames,
    )
}
