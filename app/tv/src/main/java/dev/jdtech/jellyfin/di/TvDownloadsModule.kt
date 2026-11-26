package dev.jdtech.jellyfin.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.repository.DownloadDeleter
import dev.jdtech.jellyfin.repository.DownloadQueuePort
import dev.jdtech.jellyfin.repository.SmartDownloadChecker
import javax.inject.Singleton

/**
 * CE (#1048): the TV app has no downloads (TV ADR 0007), so shared ViewModels and workers receive
 * no-op bindings without linking the `downloads` module.
 */
@Module
@InstallIn(SingletonComponent::class)
object TvDownloadsModule {
    @Provides @Singleton fun provideDownloadDeleter(): DownloadDeleter = DownloadDeleter.None

    @Provides @Singleton fun provideDownloadQueuePort(): DownloadQueuePort = DownloadQueuePort.None

    @Provides
    @Singleton
    fun provideSmartDownloadChecker(): SmartDownloadChecker = SmartDownloadChecker.None
}
