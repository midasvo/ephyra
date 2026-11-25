package dev.jdtech.jellyfin.repository

import android.content.Context
import dev.jdtech.jellyfin.data.files.DownloadedFilesOnDisk
import dev.jdtech.jellyfin.database.RoomDownloadStore
import dev.jdtech.jellyfin.database.RoomServerStore
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.domain.ports.MediaServer

/**
 * #966: [MediaRepositoryImpl] (in `application`) over the Room stores of [database] and the files
 * of [context], as Hilt builds it: for the tests here that check the repository and Room together.
 * The repository's own tests are in `application`, over fakes.
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
