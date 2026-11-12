package dev.jdtech.jellyfin

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #388: a mode switch resets the incoming mode's back stack while its display is not shown. The
 * entries it drops must lose their saved state and ViewModels, as a pop would, or a later entry
 * with the same key (Login) shows the password and error typed before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ModeSwitchEntryStateTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    class ScreenViewModel : ViewModel() {
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }

    /** Sets the text of each screen. */
    private val typeIn = mutableMapOf<NavKey, (String) -> Unit>()
    private val viewModels = mutableMapOf<NavKey, MutableList<ScreenViewModel>>()

    private var setupComplete by mutableStateOf(true)
    private lateinit var entryDecorators: ModeEntryDecorators
    private lateinit var navigationState: NavigationState
    private lateinit var navigator: Navigator
    private lateinit var setupBackStack: NavBackStack<NavKey>

    @Before
    fun setUp() {
        rule.setContent {
            entryDecorators = rememberModeEntryDecorators()
            navigationState =
                rememberNavigationState(
                    startRoute = HomeRoute,
                    topLevelRoutes = setOf(HomeRoute, MediaRoute),
                )
            navigator = Navigator(navigationState)
            setupBackStack = rememberNavBackStack(UsersRoute)

            val provider = entryProvider {
                entry<HomeRoute> { Screen(HomeRoute, "home") }
                entry<MediaRoute> { Screen(MediaRoute, "media") }
                entry<UsersRoute> { Screen(UsersRoute, "users") }
                entry<LoginRoute> { key -> Screen(key, "login") }
            }

            if (setupComplete) {
                NavDisplay(
                    entries =
                        navigationState.toDecoratedEntries(entryDecorators.main, provider::invoke),
                    onBack = { navigator.goBack() },
                )
            } else {
                NavDisplay(
                    backStack = setupBackStack,
                    entryDecorators = entryDecorators.setup,
                    entryProvider = provider,
                )
            }
        }
        rule.waitForIdle()
    }

    @Composable
    private fun Screen(key: NavKey, name: String) {
        var text by rememberSaveable { mutableStateOf("") }
        val viewModel = viewModel<ScreenViewModel>()
        val instances = viewModels.getOrPut(key) { mutableListOf() }
        if (viewModel !in instances) instances += viewModel
        typeIn[key] = { text = it }
        Text("$name:$text")
    }

    private fun type(key: NavKey, text: String) {
        rule.runOnIdle { typeIn.getValue(key)(text) }
        rule.waitForIdle()
    }

    private fun toSetup() {
        rule.runOnIdle {
            setupComplete = false
            restartSetup(setupBackStack, UsersRoute, entryDecorators)
        }
        rule.waitForIdle()
    }

    private fun toMain() {
        rule.runOnIdle {
            setupComplete = true
            resetMainToHome(navigator, navigationState, entryDecorators)
        }
        rule.waitForIdle()
    }

    @Test
    fun theContentKeyIsTheOneTheEntriesUse() {
        val provider = entryProvider<NavKey> {
            entry<LoginRoute> {}
            entry<UsersRoute> {}
        }
        for (key in listOf(LoginRoute(), LoginRoute("demo"), UsersRoute)) {
            assertEquals(provider(key).contentKey, entryContentKey(key))
        }
    }

    @Test
    fun aSetupLoginLeftForMainComesBackEmpty() {
        toSetup()
        rule.runOnIdle { setupBackStack.add(LoginRoute()) }
        rule.waitForIdle()
        type(LoginRoute(), "wrongpw1")
        rule.onNodeWithText("login:wrongpw1").assertExists()

        // Signed in from setup Login: setup leaves with [Users, Login] on its stack.
        toMain()
        rule.onNodeWithText("home:").assertExists()
        assertTrue(!viewModels.getValue(LoginRoute()).single().cleared)

        // Signed out, then Add user again.
        toSetup()
        rule.runOnIdle { setupBackStack.add(LoginRoute()) }
        rule.waitForIdle()
        rule.onNodeWithText("login:").assertExists()
        val logins = viewModels.getValue(LoginRoute())
        assertEquals(2, logins.size)
        assertTrue(logins.first().cleared)
        assertTrue(!logins.last().cleared)
    }

    @Test
    fun aMainLoginLeftForSetupComesBackEmptyAndHomeKeepsItsState() {
        type(HomeRoute, "scrolled")
        rule.runOnIdle { navigator.navigate(LoginRoute()) }
        rule.waitForIdle()
        type(LoginRoute(), "wrongpw1")
        val home = viewModels.getValue(HomeRoute).single()

        // A 401 on main Login signs the app out, then the user signs in again from setup.
        toSetup()
        toMain()
        rule.onNodeWithText("home:scrolled").assertExists()
        assertEquals(listOf(home), viewModels.getValue(HomeRoute))
        assertTrue(!home.cleared)

        rule.runOnIdle { navigator.navigate(LoginRoute()) }
        rule.waitForIdle()
        rule.onNodeWithText("login:").assertExists()
        val logins = viewModels.getValue(LoginRoute())
        assertEquals(2, logins.size)
        assertTrue(logins.first().cleared)
    }

    @Test
    fun theOtherTabsKeepTheirStateAcrossASwitch() {
        rule.runOnIdle { navigator.navigate(MediaRoute) }
        rule.waitForIdle()
        type(MediaRoute, "loaded")

        toSetup()
        toMain()
        rule.runOnIdle { navigator.navigate(MediaRoute) }
        rule.waitForIdle()
        rule.onNodeWithText("media:loaded").assertExists()
        assertEquals(1, viewModels.getValue(MediaRoute).size)
    }

    @Test
    fun backFromLoginStillClearsIt() {
        rule.runOnIdle { navigator.navigate(LoginRoute()) }
        rule.waitForIdle()
        type(LoginRoute(), "wrongpw1")
        rule.runOnIdle { navigator.goBack() }
        rule.waitForIdle()
        assertTrue(viewModels.getValue(LoginRoute()).single().cleared)

        rule.runOnIdle { navigator.navigate(LoginRoute()) }
        rule.waitForIdle()
        rule.onNodeWithText("login:").assertExists()
    }

    @Test
    fun discardingTwiceOrAKeyWithoutStateIsHarmless() {
        rule.runOnIdle {
            entryDecorators.discardSetup(listOf(LoginRoute("nobody"), LoginRoute("nobody")))
            entryDecorators.discardMain(listOf(LoginRoute("nobody")))
        }
        // A key dropped by two resets in a row, with no state created in between.
        rule.runOnIdle { navigator.navigate(LoginRoute()) }
        rule.waitForIdle()
        toSetup()
        toMain()
        toSetup()
        toMain()
        rule.onNodeWithText("home:").assertExists()
        rule.runOnIdle { entryDecorators.discardMain(listOf(LoginRoute(), LoginRoute())) }
        rule.waitForIdle()
        rule.onNodeWithText("home:").assertExists()
    }
}
