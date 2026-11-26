package dev.jdtech.jellyfin.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.utils.CoreDownloadTexts
import dev.jdtech.jellyfin.utils.download.DownloadTexts

/** CE (#901, #1048): the download module shows these texts of core's resources on phone. */
@Module
@InstallIn(SingletonComponent::class)
object DownloadTextsModule {
    @Provides fun provideDownloadTexts(): DownloadTexts = CoreDownloadTexts
}
