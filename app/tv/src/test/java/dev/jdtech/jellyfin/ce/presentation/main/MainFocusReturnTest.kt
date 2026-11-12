package dev.jdtech.jellyfin.ce.presentation.main

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.ce.ui.focus.RestoreFocus
import dev.jdtech.jellyfin.ce.ui.focus.focusPosition
import dev.jdtech.jellyfin.ce.ui.focus.rememberFocusPosition
import dev.jdtech.jellyfin.core.presentation.dummy.dummyCollections
import dev.jdtech.jellyfin.core.presentation.dummy.dummyServer
import dev.jdtech.jellyfin.core.presentation.dummy.dummyUser
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #533: Back from Settings (or the profile) puts focus on the drawer item that opened it, also when
 * the content restores its own focus later, as Home does once its refresh is done. A
 * SaveableStateHolder takes Main out of the composition and back, the way Navigation 3 does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp")
class MainFocusReturnTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var mainShown by mutableStateOf(true)
    private var homeReady by mutableStateOf(true)
    private var homeError by mutableStateOf(false)
    private var libraryReady by mutableStateOf(true)
    private var settingsOpened = false
    private var usersOpened = false

    /** Two cards that restore their focus with the real helper once [ready]. */
    @Composable
    private fun Cards(prefix: String, ready: Boolean) {
        val position = rememberFocusPosition()
        Row(Modifier.fillMaxSize()) {
            if (ready) {
                repeat(2) { column ->
                    Box(
                        Modifier.size(100.dp)
                            .focusPosition(position, 0, column)
                            .testTag("${prefix}_$column")
                            .focusable()
                    )
                }
            }
        }
        RestoreFocus(position = position, ready = ready)
    }

    private fun setUp() {
        rule.setContent {
            val stateHolder = rememberSaveableStateHolder()
            FindroidTheme {
                if (mainShown) {
                    stateHolder.SaveableStateProvider("main") {
                        MainScreenLayout(
                            user = dummyUser,
                            server = dummyServer,
                            libraries = dummyCollections,
                            navigateToSettings = { settingsOpened = true },
                            navigateToUsers = { usersOpened = true },
                            homeContent = {
                                if (homeError) {
                                    Box(Modifier.size(100.dp).testTag("home_retry").focusable())
                                } else {
                                    Cards("home", homeReady)
                                }
                            },
                            searchContent = { Cards("search", true) },
                            favoritesContent = { Cards("favorites", true) },
                            libraryContent = { Cards("library", libraryReady) },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun press(tag: String, key: Key) {
        rule.onNodeWithTag(tag).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    /** Opens a route over Main from [drawerItem] and comes back while Home is still refreshing. */
    private fun openAndComeBack(drawerItem: String) {
        rule.onNodeWithTag(drawerItem).requestFocus()
        press(drawerItem, Key.DirectionCenter)
        mainShown = false
        homeReady = false
        rule.waitForIdle()
        mainShown = true
        rule.waitForIdle()
        rule.onNodeWithTag(drawerItem).assertIsFocused()
        // Home's refresh is done: its restore must leave the drawer item alone.
        homeReady = true
        rule.waitForIdle()
    }

    @Test
    fun backFromSettingsKeepsFocusOnSettings() {
        setUp()
        rule.onNodeWithTag("home_1").requestFocus()

        openAndComeBack("drawer_item_settings")

        assertTrue(settingsOpened)
        rule.onNodeWithTag("drawer_item_settings").assertIsFocused()
    }

    @Test
    fun backFromTheProfileKeepsFocusOnTheProfile() {
        setUp()
        rule.onNodeWithTag("home_1").requestFocus()

        openAndComeBack("drawer_item_profile")

        assertTrue(usersOpened)
        rule.onNodeWithTag("drawer_item_profile").assertIsFocused()
    }

    // A skipped restore is once per visit: the next return to Main, from a card, restores again.
    @Test
    fun aLaterReturnFromACardStillRestoresTheCard() {
        setUp()
        openAndComeBack("drawer_item_settings")
        rule.onNodeWithTag("drawer_item_settings").assertIsFocused()

        // Into the content and onto the second card, which "opens a movie".
        rule.onNodeWithTag("home_1").requestFocus()
        mainShown = false
        rule.waitForIdle()
        mainShown = true
        rule.waitForIdle()

        rule.onNodeWithTag("home_1").assertIsFocused()
    }

    @Test
    fun aReturnFromACardRestoresTheCard() {
        setUp()
        rule.onNodeWithTag("home_1").requestFocus()

        mainShown = false
        homeReady = false
        rule.waitForIdle()
        mainShown = true
        rule.waitForIdle()
        homeReady = true
        rule.waitForIdle()

        rule.onNodeWithTag("home_1").assertIsFocused()
    }

    @Test
    fun movingInTheDrawerWhileHomeLoadsKeepsFocusThere() {
        homeReady = false
        setUp()
        rule.onNodeWithTag("drawer_item_search").requestFocus()
        press("drawer_item_search", Key.DirectionDown)
        rule.onNodeWithTag("drawer_item_home").assertIsFocused()

        homeReady = true
        rule.waitForIdle()

        rule.onNodeWithTag("drawer_item_home").assertIsFocused()
    }

    // Retry leaves the composition while Home reloads, and focus lands in the drawer without the
    // user moving there: Home takes it back once its rows are in.
    @Test
    fun focusThatOnlyFellIntoTheDrawerGoesBackToHome() {
        homeError = true
        setUp()
        rule.onNodeWithTag("home_retry").requestFocus()
        rule.onNodeWithTag("home_retry").assertIsFocused()

        homeError = false
        homeReady = false
        rule.waitForIdle()
        // Where Android puts focus when the focused node goes away.
        rule.onNodeWithTag("drawer_item_profile").requestFocus()
        homeReady = true
        rule.waitForIdle()

        rule.onNodeWithTag("home_0").assertIsFocused()
    }

    @Test
    fun firstVisitFocusesHomesFirstCard() {
        homeReady = false
        setUp()
        homeReady = true
        rule.waitForIdle()

        rule.onNodeWithTag("home_0").assertIsFocused()
    }

    // OK on a library that is still loading leaves focus on its drawer item until it is there.
    @Test
    fun aDestinationThatLoadsTakesFocusFromTheDrawer() {
        libraryReady = false
        setUp()
        val item = "drawer_item_library_${dummyCollections.first().id}"
        rule.onNodeWithTag(item).requestFocus()
        press(item, Key.DirectionCenter)
        rule.onNodeWithTag(item).assertIsFocused()

        libraryReady = true
        rule.waitForIdle()

        rule.onNodeWithTag("library_0").assertIsFocused()
    }
}
