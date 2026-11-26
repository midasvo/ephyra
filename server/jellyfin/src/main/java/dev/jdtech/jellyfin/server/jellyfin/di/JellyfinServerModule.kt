package dev.jdtech.jellyfin.server.jellyfin.di

import android.app.Application
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.domain.ports.ActiveServerHolder
import dev.jdtech.jellyfin.domain.ports.DeviceReportSource
import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.domain.ports.ServerConnector
import dev.jdtech.jellyfin.domain.ports.ServerSession
import dev.jdtech.jellyfin.domain.ports.UserDataSyncPorts
import dev.jdtech.jellyfin.http.MediaHttpClient
import dev.jdtech.jellyfin.models.ServerType
import dev.jdtech.jellyfin.repository.NetworkTimeouts
import dev.jdtech.jellyfin.server.jellyfin.JellyfinDeviceProfiles
import dev.jdtech.jellyfin.server.jellyfin.JellyfinDeviceReportSource
import dev.jdtech.jellyfin.server.jellyfin.JellyfinMediaServer
import dev.jdtech.jellyfin.server.jellyfin.JellyfinServerConnector
import dev.jdtech.jellyfin.server.jellyfin.JellyfinServerSession
import dev.jdtech.jellyfin.server.jellyfin.JellyfinUserDataSyncPorts
import javax.inject.Singleton

/**
 * #758: what the Jellyfin adapter provides, all over the app's one client. The apps, the only
 * modules that depend on the adapter, are the composition root; `core` provides what this takes
 * from outside: the [ActiveServerHolder] (with the saved session on it; `core` knows Room) and the
 * user's [NetworkTimeouts] (#918: read once, when the client is made).
 */
@Module
@InstallIn(SingletonComponent::class)
object JellyfinServerModule {
    /**
     * CE (#250, #763): signed in before anything uses the client. #899: the client reads the
     * server, the user and the token from [active], which `core` hands out with the saved session
     * already on it, so no provider here, [ServerSession] and [MediaServer] included, sees the
     * client before that.
     */
    @Singleton
    @Provides
    fun provideJellyfinApi(
        @ApplicationContext application: Context,
        timeouts: NetworkTimeouts,
        active: ActiveServerHolder,
    ): JellyfinApi =
        JellyfinApi.getInstance(
            context = application,
            requestTimeout = timeouts.requestTimeout(),
            connectTimeout = timeouts.connectTimeout(),
            socketTimeout = timeouts.socketTimeout(),
            activeServer = active,
        )

    /** #748: the base URL and the user id for code outside the adapter. */
    @Singleton
    @Provides
    fun provideServerSession(jellyfinApi: JellyfinApi): ServerSession =
        JellyfinServerSession(jellyfinApi)

    /**
     * #761: finding a server and logging in, for the onboarding screens. #770: one of the app's
     * connectors (`ServerConnectors`).
     */
    @Singleton
    @Provides
    @IntoMap
    @ServerTypeKey(ServerType.JELLYFIN)
    fun provideServerConnector(jellyfinApi: JellyfinApi): ServerConnector =
        JellyfinServerConnector(jellyfinApi)

    /** #762: the server's user data for any saved user, for the sync. #770: of Jellyfin servers. */
    @Singleton
    @Provides
    @IntoMap
    @ServerTypeKey(ServerType.JELLYFIN)
    fun provideUserDataSyncPorts(jellyfinApi: JellyfinApi): UserDataSyncPorts =
        JellyfinUserDataSyncPorts(jellyfinApi)

    /** The API's own client, so the player shares its connection pool (#296, #750). */
    @Provides
    fun provideMediaHttpClient(jellyfinApi: JellyfinApi): MediaHttpClient = MediaHttpClient {
        jellyfinApi.httpClient
    }

    /** CE (#752): the Jellyfin device profile for each playback target. */
    @Singleton
    @Provides
    fun provideJellyfinDeviceProfiles(): JellyfinDeviceProfiles = JellyfinDeviceProfiles()

    @Provides
    fun provideDeviceReportSource(
        application: Application,
        profiles: JellyfinDeviceProfiles,
    ): DeviceReportSource = JellyfinDeviceReportSource(application, profiles)

    /**
     * CE (#756, #757): the Jellyfin server the repository talks to, with its ports. #770: the
     * repository gets it through `CurrentMediaServer`, while the server in use is a Jellyfin
     * one. #899: `CurrentMediaServer` takes its ports for one value at a time ([MediaServer.at]).
     */
    @Singleton
    @Provides
    @IntoMap
    @ServerTypeKey(ServerType.JELLYFIN)
    fun provideMediaServer(
        jellyfinApi: JellyfinApi,
        session: ServerSession,
        deviceProfiles: JellyfinDeviceProfiles,
    ): MediaServer = JellyfinMediaServer(jellyfinApi, deviceProfiles, session)
}
