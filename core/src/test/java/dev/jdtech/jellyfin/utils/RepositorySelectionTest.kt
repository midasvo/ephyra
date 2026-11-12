package dev.jdtech.jellyfin.utils

import android.content.SharedPreferences
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [useOfflineRepository] (#427): a UI that shows online mode keeps the online repository through a
 * network drop; every other case follows the live [isOfflineModeActive] as before.
 */
class RepositorySelectionTest {

    private val connectivity = FakeConnectivity(online = true)

    @Test
    fun `online decided by the UI survives a network drop`() {
        val decision = UiOfflineDecision().apply { set(owner = "main", offline = false) }

        connectivity.online = false

        assertFalse(useOfflineRepository(decision, appPreferences(offlineMode = false), connectivity))
    }

    @Test
    fun `the offline setting wins over an online decision`() {
        val decision = UiOfflineDecision().apply { set(owner = "main", offline = false) }

        assertTrue(useOfflineRepository(decision, appPreferences(offlineMode = true), connectivity))
    }

    @Test
    fun `offline decided by the UI follows live connectivity`() {
        val decision = UiOfflineDecision().apply { set(owner = "main", offline = true) }
        val preferences = appPreferences(offlineMode = false)

        connectivity.online = false
        assertTrue(useOfflineRepository(decision, preferences, connectivity))

        // Started without network, network back: the download queue and workers get the online
        // repository, as before.
        connectivity.online = true
        assertFalse(useOfflineRepository(decision, preferences, connectivity))
    }

    @Test
    fun `nothing decided follows live connectivity and the setting`() {
        val decision = UiOfflineDecision()

        assertFalse(useOfflineRepository(decision, appPreferences(offlineMode = false), connectivity))
        assertTrue(useOfflineRepository(decision, appPreferences(offlineMode = true), connectivity))
        connectivity.online = false
        assertTrue(useOfflineRepository(decision, appPreferences(offlineMode = false), connectivity))
    }

    @Test
    fun `a cleared ViewModel leaves a newer decision alone`() {
        val decision = UiOfflineDecision()
        val old = Any()
        val new = Any()

        decision.set(old, offline = true)
        decision.set(new, offline = false)
        decision.clear(old)
        assertEquals(false, decision.offline)

        decision.clear(new)
        assertNull(decision.offline)
    }

    private class FakeConnectivity(var online: Boolean) : NetworkConnectivity {
        override fun isOnline() = online

        override fun isMetered() = false

        override fun isRoaming() = false
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
