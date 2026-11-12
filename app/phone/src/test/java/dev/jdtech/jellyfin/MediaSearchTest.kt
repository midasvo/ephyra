package dev.jdtech.jellyfin

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #394: Home's search button opens the search on the My media tab. Leaving that search returned
 * to My media instead of Home. The rules are the same with or without the rail beside a docked
 * search (#439); the rail only makes tab picks possible while the search is open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MediaSearchTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val expanded = mutableStateOf(false)
    private val search = MediaSearch(expanded, mutableStateOf(false))

    private fun homeSearchButton() = search.openFromHome()

    @Test
    fun closingASearchOpenedOnHomeGoesBackToHome() {
        homeSearchButton()

        assertEquals(HomeRoute, search.setExpanded(false))
        assertFalse(expanded.value)
    }

    @Test
    fun closingASearchOpenedOnMyMediaStaysThere() {
        // Bottom bar or rail: a second press on My media opens its search.
        search.onTabClick(MediaRoute, current = HomeRoute)
        search.onTabClick(MediaRoute, current = MediaRoute)
        assertTrue(expanded.value)

        assertNull(search.setExpanded(false))
    }

    @Test
    fun openingTheBarOnMyMediaStaysThere() {
        search.onTabClick(MediaRoute, current = HomeRoute)

        assertNull(search.setExpanded(true))
        assertNull(search.setExpanded(false))
    }

    @Test
    fun goingBackToHomeOnceForgetsWhereTheSearchCameFrom() {
        homeSearchButton()
        assertEquals(HomeRoute, search.setExpanded(false))

        // Later, on the My media tab itself.
        search.onTabClick(MediaRoute, current = HomeRoute)
        search.setExpanded(true)
        assertNull(search.setExpanded(false))
    }

    @Test
    fun withTheRailPickingMyMediaMakesItAMyMediaSearch() {
        homeSearchButton()

        // The rail stays beside a docked search, so My media can be picked while it is open.
        search.onTabClick(MediaRoute, current = MediaRoute)

        assertTrue(expanded.value)
        assertNull(search.setExpanded(false))
    }

    @Test
    fun withTheRailPickingAnotherTabClosesAndForgets() {
        homeSearchButton()

        search.onTabClick(DownloadsRoute, current = MediaRoute)
        search.onTopLevelRoute(DownloadsRoute)
        assertFalse(expanded.value)

        search.onTabClick(MediaRoute, current = DownloadsRoute)
        search.setExpanded(true)
        assertNull(search.setExpanded(false))
    }

    @Test
    fun theOriginSurvivesRecreation() {
        val restorationTester = StateRestorationTester(rule)
        lateinit var fromHome: MutableState<Boolean>
        lateinit var open: MutableState<Boolean>
        restorationTester.setContent {
            open = rememberSearchExpanded()
            fromHome = rememberSearchFromHome()
        }

        rule.runOnIdle { MediaSearch(open, fromHome).openFromHome() }
        restorationTester.emulateSavedInstanceStateRestore()

        rule.runOnIdle { assertEquals(HomeRoute, MediaSearch(open, fromHome).setExpanded(false)) }
    }
}
