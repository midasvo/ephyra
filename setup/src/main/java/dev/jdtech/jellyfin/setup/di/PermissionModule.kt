package dev.jdtech.jellyfin.setup.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.models.PermissionDefinitions
import dev.jdtech.jellyfin.setup.domain.LocalNetworkAccess
import dev.jdtech.jellyfin.setup.domain.LocalNetworkAccessImpl
import dev.jdtech.jellyfin.setup.domain.ServerLocalNetworkBlock
import dev.jdtech.jellyfin.setup.presentation.permission.PermissionRequest
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PermissionModule {
    /** The permissions that gate app entry, in the order they are asked. */
    @Provides
    @Singleton
    fun providePermissionRequests(appPreferences: AppPreferences): List<PermissionRequest> =
        listOf(
            PermissionRequest(
                permission = PermissionDefinitions.localNetwork,
                decidedPreference = appPreferences.localNetworkPermissionDecided,
            )
        )

    /** CE (#376): the state of the local network permission, for the setup screens. */
    @Provides
    @Singleton
    fun provideLocalNetworkAccess(@ApplicationContext context: Context): LocalNetworkAccess =
        LocalNetworkAccessImpl(context)

    /** CE (#571): whether the denied permission is what keeps the server in use out of reach. */
    @Provides
    @Singleton
    fun provideServerLocalNetworkBlock(
        access: LocalNetworkAccess,
        jellyfinApi: JellyfinApi,
    ): ServerLocalNetworkBlock =
        ServerLocalNetworkBlock(access = access, serverAddress = { jellyfinApi.api.baseUrl })
}
