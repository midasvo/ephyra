package dev.jdtech.jellyfin.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.repository.CurrentAccount
import dev.jdtech.jellyfin.repository.CurrentServerId
import dev.jdtech.jellyfin.repository.DownloadSegmentsAndTrickplay
import dev.jdtech.jellyfin.repository.DownloadSources
import dev.jdtech.jellyfin.repository.DownloadUserData
import dev.jdtech.jellyfin.repository.DownloadedFiles
import dev.jdtech.jellyfin.repository.ItemCatalog
import dev.jdtech.jellyfin.repository.ItemExtras
import dev.jdtech.jellyfin.repository.ItemLists
import dev.jdtech.jellyfin.repository.KnownServerState
import dev.jdtech.jellyfin.repository.MediaRepositoryImpl
import dev.jdtech.jellyfin.repository.NextUpMaxDays
import dev.jdtech.jellyfin.repository.PlaybackMedia
import dev.jdtech.jellyfin.repository.PlaybackReports
import dev.jdtech.jellyfin.repository.ResumeThresholdsStore
import dev.jdtech.jellyfin.repository.SeriesTrackPreferences
import dev.jdtech.jellyfin.repository.StoredItemLists
import dev.jdtech.jellyfin.repository.StoredItemSources
import dev.jdtech.jellyfin.repository.StoredItems
import dev.jdtech.jellyfin.repository.StoredLibraryNames
import dev.jdtech.jellyfin.repository.StoredTrackPreferences
import dev.jdtech.jellyfin.repository.StoredUsers
import dev.jdtech.jellyfin.repository.UserDataSyncRequester
import dev.jdtech.jellyfin.repository.UserDataUpdates
import javax.inject.Singleton

/**
 * The repository over the server in use and Room (#899: moved from `core`'s `RepositoryModule`).
 * What it takes from `core` comes through the ports `core` binds: [UserDataSyncRequester] (the user
 * data sync), [KnownServerState] (the server reachability), [StoredLibraryNames] (the app's
 * strings) and, #918, what it reads of the settings ([CurrentServerId], [NextUpMaxDays],
 * [ResumeThresholdsStore]). The codec probe that was bound here is in `device` (#965). #966: the
 * database through the store roles `DatabaseModule` binds, the downloaded files through
 * [DownloadedFiles]; no DAO and no `Context`.
 */
@Module
@InstallIn(SingletonComponent::class)
object MediaRepositoryModule {

    /**
     * The repository over the server in use: the adapter of its type (#770: `ServerModule`, over
     * what `JellyfinServerModule` in `server/jellyfin` provides, #758).
     */
    @Singleton
    @Provides
    fun provideMediaRepositoryImpl(
        server: MediaServer,
        storedItems: StoredItems,
        storedLists: StoredItemLists,
        storedSources: StoredItemSources,
        downloadSources: DownloadSources,
        storedSegments: DownloadSegmentsAndTrickplay,
        storedUserData: DownloadUserData,
        trackPreferences: StoredTrackPreferences,
        storedUsers: StoredUsers,
        downloadedFiles: DownloadedFiles,
        currentServerId: CurrentServerId,
        nextUpMaxDays: NextUpMaxDays,
        resumeThresholds: ResumeThresholdsStore,
        syncRequester: UserDataSyncRequester,
        knownServerState: KnownServerState,
        storedLibraryNames: StoredLibraryNames,
    ): MediaRepositoryImpl {
        return MediaRepositoryImpl(
            server = server,
            storedItems = storedItems,
            storedLists = storedLists,
            storedSources = storedSources,
            downloadSources = downloadSources,
            storedSegments = storedSegments,
            storedUserData = storedUserData,
            trackPreferences = trackPreferences,
            storedUsers = storedUsers,
            downloadedFiles = downloadedFiles,
            currentServer = currentServerId,
            nextUpMaxDays = nextUpMaxDays,
            resumeThresholds = resumeThresholds,
            syncRequester = syncRequester,
            knownServerState = knownServerState,
            storedLibraryNames = storedLibraryNames,
        )
    }

    // #967: one repository, bound as each of its roles. Each binding hands out the one instance
    // above, which holds the play sessions and the resume settings. CE (#281): downloaded items
    // fall
    // back to Room when the server is away.

    @Provides fun provideCurrentAccount(impl: MediaRepositoryImpl): CurrentAccount = impl

    @Provides fun provideItemCatalog(impl: MediaRepositoryImpl): ItemCatalog = impl

    @Provides fun provideItemLists(impl: MediaRepositoryImpl): ItemLists = impl

    @Provides fun provideItemExtras(impl: MediaRepositoryImpl): ItemExtras = impl

    @Provides fun provideUserDataUpdates(impl: MediaRepositoryImpl): UserDataUpdates = impl

    @Provides
    fun provideSeriesTrackPreferences(impl: MediaRepositoryImpl): SeriesTrackPreferences = impl

    @Provides fun providePlaybackMedia(impl: MediaRepositoryImpl): PlaybackMedia = impl

    @Provides fun providePlaybackReports(impl: MediaRepositoryImpl): PlaybackReports = impl
}
