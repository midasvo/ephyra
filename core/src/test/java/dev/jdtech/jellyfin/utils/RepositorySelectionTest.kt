package dev.jdtech.jellyfin.utils

import android.content.SharedPreferences
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [useOfflineRepository]: since #281 only the Offline Mode setting picks the offline repository.
 *
 * That keeps what #427 was for: the repository never changes under a running UI because the
 * network came or went, so a screen made after a network drop shows an error instead of an empty
 * list. Changing the setting restarts the activity, so no screen outlives that either.
 */
class RepositorySelectionTest {

    @Test
    fun `the setting off means the online repository`() {
        assertFalse(useOfflineRepository(appPreferences(offlineMode = false)))
    }

    @Test
    fun `the setting on means the offline repository`() {
        assertTrue(useOfflineRepository(appPreferences(offlineMode = true)))
    }

    @Test
    fun `the network coming or going swaps nothing`() {
        // The choice takes no connectivity at all: there is nothing for a network drop, or a start
        // without network, to change. Asked again and again, as every new screen does, it holds.
        val online = appPreferences(offlineMode = false)
        val offline = appPreferences(offlineMode = true)

        repeat(3) {
            assertFalse(useOfflineRepository(online))
            assertTrue(useOfflineRepository(offline))
        }
    }

    /** Real AppPreferences backed by a fake SharedPreferences with a fixed offlineMode value. */
    private fun appPreferences(offlineMode: Boolean): AppPreferences =
        AppPreferences(fakeSharedPreferences(mapOf("pref_offline_mode" to offlineMode)))

    private fun fakeSharedPreferences(booleans: Map<String, Boolean>): SharedPreferences =
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getBoolean" -> booleans[args!![0] as String] ?: args[1]
                "getString",
                "getInt",
                "getLong",
                "getFloat",
                "getStringSet" -> args!![1]
                "contains" -> false
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "fakeSharedPreferences"
                "equals" -> proxy === args?.getOrNull(0)
                else -> throw UnsupportedOperationException("SharedPreferences.${method.name}")
            }
        } as SharedPreferences
}
