package dev.jdtech.jellyfin.data.di

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.jdtech.jellyfin.data.files.DownloadedFilesOnDisk
import dev.jdtech.jellyfin.database.MIGRATION_6_7
import dev.jdtech.jellyfin.database.RoomDownloadStore
import dev.jdtech.jellyfin.database.RoomPendingUserDataStore
import dev.jdtech.jellyfin.database.RoomServerStore
import dev.jdtech.jellyfin.database.ServerDatabase
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.profilelock.ProfileLock
import dev.jdtech.jellyfin.repository.DownloadList
import dev.jdtech.jellyfin.repository.DownloadMediaStreams
import dev.jdtech.jellyfin.repository.DownloadSegmentsAndTrickplay
import dev.jdtech.jellyfin.repository.DownloadSources
import dev.jdtech.jellyfin.repository.DownloadUserData
import dev.jdtech.jellyfin.repository.DownloadedFiles
import dev.jdtech.jellyfin.repository.DownloadedItemRows
import dev.jdtech.jellyfin.repository.PendingUserDataStore
import dev.jdtech.jellyfin.repository.QueuedDownloads
import dev.jdtech.jellyfin.repository.ServerAddresses
import dev.jdtech.jellyfin.repository.StoredItemLists
import dev.jdtech.jellyfin.repository.StoredItemSources
import dev.jdtech.jellyfin.repository.StoredItems
import dev.jdtech.jellyfin.repository.StoredServers
import dev.jdtech.jellyfin.repository.StoredTrackPreferences
import dev.jdtech.jellyfin.repository.StoredUsers
import dev.jdtech.jellyfin.repository.UserLocks
import javax.inject.Singleton

/**
 * The Room database and the store ports over it (#899). In `data` since part C1, like
 * `JellyfinServerModule` in `server/jellyfin`: only the apps depend on this module, so only they
 * see the DAO.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Singleton
    @Provides
    fun provideServerDatabaseDao(@ApplicationContext app: Context): ServerDatabaseDao {
        return Room.databaseBuilder(app.applicationContext, ServerDatabase::class.java, "servers")
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(MIGRATION_6_7)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
            .getServerDatabaseDao()
    }

    /** #899: what the user data sync reads and writes in Room. */
    @Singleton
    @Provides
    fun providePendingUserDataStore(database: ServerDatabaseDao): PendingUserDataStore =
        RoomPendingUserDataStore(database)

    /**
     * #899: the saved servers, addresses and users. #967: one store, bound as each of its roles.
     */
    @Singleton
    @Provides
    fun provideRoomServerStore(database: ServerDatabaseDao): RoomServerStore =
        RoomServerStore(database)

    @Provides fun provideStoredServers(store: RoomServerStore): StoredServers = store

    @Provides fun provideServerAddresses(store: RoomServerStore): ServerAddresses = store

    @Provides fun provideStoredUsers(store: RoomServerStore): StoredUsers = store

    @Provides fun provideUserLocks(store: RoomServerStore): UserLocks = store

    @Provides
    fun provideStoredTrackPreferences(store: RoomServerStore): StoredTrackPreferences = store

    // CE (#299): one per process, so an unlocked profile stays unlocked across screens.
    @Singleton
    @Provides
    fun provideProfileLock(storedUsers: StoredUsers, userLocks: UserLocks): ProfileLock =
        ProfileLock(storedUsers, userLocks)

    // #899: the download rows, for the downloader, the queue, Smart Downloads and the screens.
    // #967:
    // one store, bound as each of its roles.
    @Singleton
    @Provides
    fun provideRoomDownloadStore(database: ServerDatabaseDao): RoomDownloadStore =
        RoomDownloadStore(database)

    @Provides fun provideDownloadList(store: RoomDownloadStore): DownloadList = store

    @Provides fun provideDownloadedItemRows(store: RoomDownloadStore): DownloadedItemRows = store

    @Provides fun provideDownloadSources(store: RoomDownloadStore): DownloadSources = store

    @Provides
    fun provideDownloadMediaStreams(store: RoomDownloadStore): DownloadMediaStreams = store

    @Provides
    fun provideDownloadSegmentsAndTrickplay(
        store: RoomDownloadStore
    ): DownloadSegmentsAndTrickplay = store

    @Provides fun provideDownloadUserData(store: RoomDownloadStore): DownloadUserData = store

    @Provides fun provideQueuedDownloads(store: RoomDownloadStore): QueuedDownloads = store

    // #966: what the repository's stored fallback reads.
    @Provides fun provideStoredItems(store: RoomDownloadStore): StoredItems = store

    @Provides fun provideStoredItemSources(store: RoomDownloadStore): StoredItemSources = store

    @Provides fun provideStoredItemLists(store: RoomDownloadStore): StoredItemLists = store

    /** #966: the images and trickplay sheets a download keeps in the app's files directory. */
    @Provides
    fun provideDownloadedFiles(@ApplicationContext context: Context): DownloadedFiles =
        DownloadedFilesOnDisk(context)
}
