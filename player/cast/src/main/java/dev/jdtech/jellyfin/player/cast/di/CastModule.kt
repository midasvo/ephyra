package dev.jdtech.jellyfin.player.cast.di

import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.player.cast.CastPlayerController
import dev.jdtech.jellyfin.player.cast.CastPlayerControllerImpl
import dev.jdtech.jellyfin.player.cast.CastSessionManager
import dev.jdtech.jellyfin.player.cast.CastSessionManagerImpl
import dev.jdtech.jellyfin.player.cast.models.CastAvailability
import java.util.Optional
import javax.inject.Provider
import javax.inject.Singleton

/**
 * A stand-in for the Cast SDK, which a debug build can turn on to show the Cast screens where
 * there is no receiver (an emulator). Release builds have none.
 */
interface CastStandIn {
    /** `null` unless the stand-in is turned on. */
    val sessionManager: CastSessionManager?
    val playerController: CastPlayerController?
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CastStandInModule {
    @BindsOptionalOf abstract fun standIn(): CastStandIn
}

@Module
@InstallIn(SingletonComponent::class)
object CastModule {
    @Provides
    @Singleton
    fun provideCastSessionManager(
        standIn: Optional<CastStandIn>,
        impl: CastSessionManagerImpl,
    ): CastSessionManager = standIn.takeIf { impl.hasCast() }?.orElse(null)?.sessionManager ?: impl

    @Provides
    @Singleton
    fun provideCastPlayerController(
        standIn: Optional<CastStandIn>,
        session: CastSessionManagerImpl,
        impl: Provider<CastPlayerControllerImpl>,
    ): CastPlayerController =
        standIn.takeIf { session.hasCast() }?.orElse(null)?.playerController ?: impl.get()

    /** A build without Cast stays without it: the stand-in is for builds that have the SDK. */
    private fun CastSessionManager.hasCast() = availability != CastAvailability.NOT_IN_BUILD
}
