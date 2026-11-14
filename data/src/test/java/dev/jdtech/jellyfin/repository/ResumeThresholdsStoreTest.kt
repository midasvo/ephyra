package dev.jdtech.jellyfin.repository

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#637): each server's resume settings are kept for offline stops, and unknown ones are the
 * defaults.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ResumeThresholdsStoreTest {
    private val preferences =
        RuntimeEnvironment.getApplication().getSharedPreferences("thresholds", Context.MODE_PRIVATE)
    private val store = ResumeThresholdsStore(preferences)

    @Test
    fun `an unknown server has the defaults`() {
        assertEquals(ResumeThresholds.DEFAULT, store.get("never-read"))
        assertEquals(ResumeThresholds.DEFAULT, store.get(null))
    }

    @Test
    fun `each server keeps its own`() {
        store.put("a", ResumeThresholds(2, 93, 2))
        store.put("b", ResumeThresholds(10, 80, 60))

        assertEquals(ResumeThresholds(2, 93, 2), ResumeThresholdsStore(preferences).get("a"))
        assertEquals(ResumeThresholds(10, 80, 60), ResumeThresholdsStore(preferences).get("b"))
    }

    @Test
    fun `a server read in this run is fresh`() {
        assertFalse(store.isFresh("c"))
        store.put("c", ResumeThresholds.DEFAULT)
        assertTrue(store.isFresh("c"))
    }

    @Test
    fun `a stored value that is not three numbers is ignored`() {
        assertNull(ResumeThresholdsStore.parse("2,93"))
        assertNull(ResumeThresholdsStore.parse("2,x,3"))
        assertEquals(ResumeThresholds(2, 93, 2), ResumeThresholdsStore.parse("2,93,2"))
    }
}
