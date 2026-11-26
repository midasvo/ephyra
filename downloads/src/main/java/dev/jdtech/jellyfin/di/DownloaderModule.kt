package dev.jdtech.jellyfin.di

import android.app.Application
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadQueue
import dev.jdtech.jellyfin.core.presentation.downloader.SmartDownloads
import dev.jdtech.jellyfin.repository.CurrentAccount
import dev.jdtech.jellyfin.repository.CurrentServerId
import dev.jdtech.jellyfin.repository.DataChangeEvents
import dev.jdtech.jellyfin.repository.DownloadDeleter
import dev.jdtech.jellyfin.repository.DownloadList
import dev.jdtech.jellyfin.repository.DownloadMediaStreams
import dev.jdtech.jellyfin.repository.DownloadQueuePort
import dev.jdtech.jellyfin.repository.DownloadSegmentsAndTrickplay
import dev.jdtech.jellyfin.repository.DownloadSettings
import dev.jdtech.jellyfin.repository.DownloadSources
import dev.jdtech.jellyfin.repository.DownloadUserData
import dev.jdtech.jellyfin.repository.DownloadedItemRows
import dev.jdtech.jellyfin.repository.ItemCatalog
import dev.jdtech.jellyfin.repository.PlaybackMedia
import dev.jdtech.jellyfin.repository.QueuedDownloads
import dev.jdtech.jellyfin.repository.SmartDownloadChecker
import dev.jdtech.jellyfin.utils.Downloader
import dev.jdtech.jellyfin.utils.DownloaderImpl
import dev.jdtech.jellyfin.utils.download.DownloadTexts
import dev.jdtech.jellyfin.utils.download.MediaDownloadEngine
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DownloaderModule {
    @Singleton
    @Provides
    fun provideDownloader(
        application: Application,
        downloadList: DownloadList,
        itemRows: DownloadedItemRows,
        downloadSources: DownloadSources,
        downloadStreams: DownloadMediaStreams,
        segmentsAndTrickplay: DownloadSegmentsAndTrickplay,
        downloadUserData: DownloadUserData,
        queuedDownloads: QueuedDownloads,
        catalog: Provider<ItemCatalog>,
        media: Provider<PlaybackMedia>,
        account: Provider<CurrentAccount>,
        settings: DownloadSettings,
        currentServerId: CurrentServerId,
        workManagerProvider: Provider<WorkManager>,
        engine: MediaDownloadEngine,
        texts: DownloadTexts,
        dataChanges: DataChangeEvents,
    ): Downloader {
        return DownloaderImpl(
            application,
            downloadList,
            itemRows,
            downloadSources,
            downloadStreams,
            segmentsAndTrickplay,
            downloadUserData,
            queuedDownloads,
            catalog,
            media,
            account,
            settings,
            currentServerId,
            workManagerProvider,
            engine,
            texts,
            dataChanges,
        )
    }

    @Provides fun provideDownloadDeleter(downloader: Downloader): DownloadDeleter = downloader

    @Provides fun provideDownloadQueuePort(queue: DownloadQueue): DownloadQueuePort = queue

    @Provides
    fun provideSmartDownloadChecker(smartDownloads: SmartDownloads): SmartDownloadChecker =
        smartDownloads
}
