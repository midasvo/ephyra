package dev.jdtech.jellyfin.database

import android.app.Application
import androidx.room3.Room
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CE (#380): every released schema migrates to the current one, and the rows a user cannot get back
 * survive it: the server and its address, the user with the access token and profile lock, the
 * download metadata (items, sources, streams, pending downloads), the offline resume position and
 * the series track preferences.
 *
 * The target is the highest schema in `data/schemas/`, not a fixed number. A new version that has
 * no migration fails every test here with "no migration path"; in the app it would hit
 * `fallbackToDestructiveMigration` and silently wipe the database instead.
 *
 * Released schemas: 12 (v1.2.0-ce.0 to ce.3), 14 (v1.2.0-ce.4 to 2.1.0), 16 (2.2.0), 18 (2.3.0,
 * 2.4.0).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ServerDatabaseMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dbFile = context.getDatabasePath("migration-test")

    @get:Rule val helper = helper(dbFile)

    private val latest: Int =
        context.assets
            .list(SCHEMA_DIR)!!
            .map { it.removeSuffix(".json").toInt() }
            .max()

    @Test fun `schema 12 migrates with its data`() = migratesWithData(12)

    @Test fun `schema 14 migrates with its data`() = migratesWithData(14)

    @Test fun `schema 16 migrates with its data`() = migratesWithData(16)

    @Test fun `schema 18 migrates with its data`() = migratesWithData(18)

    /** Every exported schema, released or not, has a migration path to the current one. */
    @Test
    fun `every exported schema migrates to the current one`() = runBlocking {
        for (version in 2 until latest) {
            val file = context.getDatabasePath("sweep-$version")
            val sweep = helper(file)
            sweep.createDatabase(version).close()
            sweep.runMigrationsAndValidate(latest, MIGRATIONS).close()
        }
    }

    /**
     * KSP rewrites the current version's JSON in place when an entity changes without a version
     * bump, and the build passes. The migration tests then compare against the rewritten file and
     * stay green, while an installed database of that version no longer matches the code.
     *
     * When this fails: undo the change to the schema file, bump `version` in [ServerDatabase] and
     * add a migration (an `AutoMigration` usually does). Never change a hash here: each one is a
     * schema that is on users' devices.
     */
    @Test
    fun `released schemas are unchanged`() {
        val pinned =
            mapOf(
                12 to "9a46630a7d4c4ba4dfd46b2d85afc52f",
                14 to "e90f3d1b1d47c59c2e974df07965d328",
                16 to "ca0feb86ab6251711afc43b6a5f55957",
                18 to "8f1beae8cd83dda5184f02488860efa0",
            )
        for ((version, hash) in pinned) {
            val json =
                context.assets.open("$SCHEMA_DIR/$version.json").bufferedReader().use {
                    it.readText()
                }
            val actual = Regex("\"identityHash\": \"([0-9a-f]+)\"").find(json)!!.groupValues[1]
            assertEquals("identity hash of schema $version", hash, actual)
        }
    }

    private fun migratesWithData(from: Int) = runBlocking {
        helper.createDatabase(from).apply {
            insertRows(from)
            close()
        }

        // Room's own check: the migrated tables match the exported schema exactly.
        helper.runMigrationsAndValidate(latest, MIGRATIONS).apply {
            assertRowsSurvived(from)
            close()
        }

        // The app's view: Room opens the file against the compiled entities (identity hash), with
        // the app's migrations and without the destructive fallback, and the DAO reads the rows.
        val database =
            Room.databaseBuilder(context, ServerDatabase::class.java, dbFile.path)
                .setDriver(AndroidSQLiteDriver())
                .addMigrations(*MIGRATIONS.toTypedArray())
                .build()
        try {
            val dao = database.getServerDatabaseDao()
            assertEquals(TOKEN, dao.getServerCurrentUser(SERVER)!!.accessToken)
            assertEquals(ADDRESS, dao.getServerCurrentAddress(SERVER)!!.address)
            assertEquals("Movie", dao.getMovie(uuid(MOVIE)).name)
            assertEquals(PATH, dao.getSources(uuid(MOVIE)).single().path)
            assertEquals(POSITION, dao.getUserData(uuid(MOVIE), uuid(USER))!!.playbackPositionTicks)
            assertEquals(uuid(EPISODE), dao.getPendingDownloads().single().itemId)
            if (from >= 14) {
                assertEquals("eng", dao.getSeriesTrackPreferences(uuid(USER), uuid(SHOW)).single().language)
            }
        } finally {
            database.close()
        }
    }

    /** Rows in the columns schema 12 has; later schemas only add columns with defaults. */
    private fun SQLiteConnection.insertRows(version: Int) {
        execSQL("INSERT INTO servers (id, name, currentServerAddressId, currentUserId) VALUES ('$SERVER', 'Home', '$ADDRESS_ID', '$USER')")
        execSQL("INSERT INTO serverAddresses (id, serverId, address) VALUES ('$ADDRESS_ID', '$SERVER', '$ADDRESS')")
        execSQL(
            "INSERT INTO users (id, name, serverId, accessToken) VALUES ('$USER', 'Alice', '$SERVER', '$TOKEN')"
        )
        execSQL(
            "INSERT INTO movies (id, serverId, name, overview, runtimeTicks, status) VALUES ('$MOVIE', '$SERVER', 'Movie', '', 72000000000, 'Released')"
        )
        execSQL(
            "INSERT INTO shows (id, serverId, name, overview, runtimeTicks, status) VALUES ('$SHOW', '$SERVER', 'Show', '', 0, 'Continuing')"
        )
        execSQL(
            "INSERT INTO seasons (id, seriesId, name, seriesName, overview, indexNumber) VALUES ('$SEASON', '$SHOW', 'Season 1', 'Show', '', 1)"
        )
        execSQL(
            "INSERT INTO episodes (id, serverId, seasonId, seriesId, name, seriesName, overview, indexNumber, parentIndexNumber, runtimeTicks) VALUES ('$EPISODE', '$SERVER', '$SEASON', '$SHOW', 'Episode 1', 'Show', '', 1, 1, 13000000000)"
        )
        execSQL(
            "INSERT INTO sources (id, itemId, name, type, path, downloadId, downloadMaxBitrate) VALUES ('source', '$MOVIE', 'Movie', 'LOCAL', '$PATH', 42, 8000000)"
        )
        execSQL(
            "INSERT INTO mediastreams (id, sourceId, title, language, type, codec, isExternal, path) VALUES ('stream', 'source', 'English', 'eng', 'AUDIO', 'aac', 0, '$PATH')"
        )
        execSQL("INSERT INTO userdata (userId, itemId, played, favorite, playbackPositionTicks, toBeSynced) VALUES ('$USER', '$MOVIE', 0, 1, $POSITION, 1)")
        execSQL(
            "INSERT INTO pending_downloads (itemId, itemKind, addedAt) VALUES ('$EPISODE', 'Episode', 1700000000)"
        )
        if (version >= 14) {
            execSQL(
                "INSERT INTO seriesTrackPreferences (userId, seriesId, trackType, disabled, language, title, positionInLanguage) VALUES ('$USER', '$SHOW', 1, 0, 'eng', NULL, 0)"
            )
        }
        if (version >= 17) {
            execSQL(
                "UPDATE users SET pinHash = 'hash', pinSalt = 'salt', pinOnStart = 0 WHERE id = '$USER'"
            )
        }
        if (version >= 18) {
            execSQL("UPDATE users SET lockType = 'pattern' WHERE id = '$USER'")
        }
    }

    private fun SQLiteConnection.assertRowsSurvived(from: Int) {
        assertEquals(USER, text("SELECT currentUserId FROM servers WHERE id = '$SERVER'"))
        assertEquals(ADDRESS, text("SELECT address FROM serverAddresses WHERE id = '$ADDRESS_ID'"))
        assertEquals(TOKEN, text("SELECT accessToken FROM users WHERE id = '$USER'"))
        assertEquals(1L, long("SELECT COUNT(*) FROM episodes WHERE seasonId = '$SEASON'"))
        assertEquals(PATH, text("SELECT path FROM sources WHERE itemId = '$MOVIE'"))
        assertEquals(42L, long("SELECT downloadId FROM sources WHERE itemId = '$MOVIE'"))
        assertEquals(1L, long("SELECT COUNT(*) FROM mediastreams WHERE sourceId = 'source'"))
        assertEquals(POSITION, long("SELECT playbackPositionTicks FROM userdata"))
        assertEquals(1L, long("SELECT toBeSynced FROM userdata"))
        assertEquals(EPISODE, text("SELECT itemId FROM pending_downloads"))
        assertEquals(0L, long("SELECT smartQueued FROM sources"))
        if (from >= 14) {
            assertEquals("eng", text("SELECT language FROM seriesTrackPreferences"))
        }
        if (from >= 17) {
            assertEquals("hash", text("SELECT pinHash FROM users"))
            assertEquals(0L, long("SELECT pinOnStart FROM users"))
        } else {
            // A user from before profile locks has none, and asks for one at start once set.
            assertNull(text("SELECT pinHash FROM users"))
            assertEquals(1L, long("SELECT pinOnStart FROM users"))
        }
        assertEquals(
            if (from >= 18) "pattern" else "pin",
            text("SELECT lockType FROM users"),
        )
    }

    private fun SQLiteConnection.text(sql: String): String? =
        prepare(sql).use {
            it.step()
            if (it.isNull(0)) null else it.getText(0)
        }

    private fun SQLiteConnection.long(sql: String): Long =
        prepare(sql).use {
            it.step()
            it.getLong(0)
        }

    private fun uuid(id: String): UUID = UUID.fromString(id)

    private fun helper(file: java.io.File) =
        MigrationTestHelper(
            instrumentation = instrumentation,
            file = file,
            driver = AndroidSQLiteDriver(),
            databaseClass = ServerDatabase::class,
        )

    private companion object {
        const val SCHEMA_DIR = "dev.jdtech.jellyfin.database.ServerDatabase"

        /** The hand-written migrations `DatabaseModule` adds; the rest are auto-migrations. */
        val MIGRATIONS = listOf(MIGRATION_6_7)

        const val SERVER = "server"
        const val ADDRESS = "https://jellyfin.example"
        const val TOKEN = "access-token"
        const val PATH = "/storage/emulated/0/Android/data/downloads/movie.mkv"
        const val POSITION = 36_000_000_000L
        const val ADDRESS_ID = "00000000-0000-0000-0000-00000000000a"
        const val USER = "00000000-0000-0000-0000-000000000001"
        const val MOVIE = "00000000-0000-0000-0000-000000000002"
        const val SHOW = "00000000-0000-0000-0000-000000000003"
        const val SEASON = "00000000-0000-0000-0000-000000000004"
        const val EPISODE = "00000000-0000-0000-0000-000000000005"
    }
}
