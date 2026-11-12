package dev.jdtech.jellyfin

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #387: rotating or switching the theme recreates the activity. An open search must stay open, not
 * fall back to the library grid with the query left in the collapsed bar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SearchExpandedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun openSearchSurvivesRecreation() {
        val restorationTester = StateRestorationTester(rule)
        lateinit var searchExpanded: MutableState<Boolean>
        restorationTester.setContent { searchExpanded = rememberSearchExpanded() }

        rule.runOnIdle { searchExpanded.value = true }
        restorationTester.emulateSavedInstanceStateRestore()

        rule.runOnIdle { assertTrue(searchExpanded.value) }
    }
}
