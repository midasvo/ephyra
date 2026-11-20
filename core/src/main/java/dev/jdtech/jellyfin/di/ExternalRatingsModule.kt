package dev.jdtech.jellyfin.di

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.ratings.ExternalRatingsCache
import dev.jdtech.jellyfin.ratings.ExternalRatingsSource
import dev.jdtech.jellyfin.ratings.MdbListClient
import dev.jdtech.jellyfin.ratings.MdbListRatings
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.ExternalRatingsKeyCheck
import java.io.File
import javax.inject.Singleton

/**
 * CE (#331): MDBList ratings. The client has an OkHttp of its own, with nothing from the Jellyfin
 * client's set-up, so the key never passes another interceptor or a logger.
 */
@Module
@InstallIn(SingletonComponent::class)
object ExternalRatingsModule {
    @Singleton
    @Provides
    fun provideMdbListRatings(
        application: Application,
        appPreferences: AppPreferences,
    ): MdbListRatings =
        MdbListRatings(
            appPreferences = appPreferences,
            client = MdbListClient(),
            cache = ExternalRatingsCache(File(application.cacheDir, "external_ratings")),
        )

    @Provides
    fun provideExternalRatingsSource(ratings: MdbListRatings): ExternalRatingsSource = ratings

    @Provides
    fun provideExternalRatingsKeyCheck(ratings: MdbListRatings): ExternalRatingsKeyCheck = ratings
}
