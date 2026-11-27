package dev.jdtech.jellyfin.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.settings.presentation.settings.CastBuild
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CastBuildModule {
    @Provides @Singleton fun provideCastBuild(): CastBuild = CastBuild.None
}
