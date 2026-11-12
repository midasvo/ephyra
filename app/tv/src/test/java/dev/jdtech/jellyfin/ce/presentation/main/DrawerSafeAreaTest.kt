package dev.jdtech.jellyfin.ce.presentation.main

import android.app.Application
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import dev.jdtech.jellyfin.ce.ui.TvSafeArea
import dev.jdtech.jellyfin.core.presentation.dummy.dummyCollections
import dev.jdtech.jellyfin.core.presentation.dummy.dummyServer
import dev.jdtech.jellyfin.core.presentation.dummy.dummyUser
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #539: the drawer's icons, avatar and labels start inside the TV safe area (48 dp from the left
 * edge) with the drawer collapsed and expanded, and the pages beside it still start at 80 dp. #567:
 * the profile item shows the user name over the server name, both inside the item.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp")
class DrawerSafeAreaTest {
    @get:Rule val rule = createComposeRule()

    private val iconTags =
        listOf(
            "drawer_icon_profile",
            "drawer_icon_search",
            "drawer_icon_home",
            "drawer_icon_favorites",
            "drawer_icon_settings",
        ) + dummyCollections.map { "drawer_icon_library_${it.id}" }

    private fun setUp(
        user: User = dummyUser,
        server: Server = dummyServer,
        homeContent: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize().testTag("home_screen_content").focusable())
        },
    ) {
        rule.setContent {
            FindroidTheme {
                MainScreenLayout(
                    user = user,
                    server = server,
                    libraries = dummyCollections,
                    navigateToSettings = {},
                    navigateToUsers = {},
                    homeContent = homeContent,
                    searchContent = {},
                    favoritesContent = {},
                    libraryContent = {},
                )
            }
        }
        rule.waitForIdle()
    }

    private fun assertIconsInSafeArea(state: String) {
        for (tag in iconTags) {
            val left = rule.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot().left
            assertTrue(
                "$state: $tag starts at $left, in the margin (${TvSafeArea.horizontal})",
                left >= TvSafeArea.horizontal,
            )
        }
    }

    @Test
    fun collapsedIconsAreInsideTheSafeArea() {
        setUp()
        rule.onNodeWithTag("home_screen_content").requestFocus()
        rule.waitForIdle()
        rule.onNodeWithTag("home_screen_content").assertIsFocused()

        assertIconsInSafeArea("collapsed")
        // The avatar is the widest leading content: it sits on the edge.
        assertEquals(
            TvSafeArea.horizontal,
            rule.onNodeWithTag("drawer_icon_profile", useUnmergedTree = true)
                .getBoundsInRoot()
                .left,
        )
    }

    @Test
    fun expandedIconsAndLabelsAreInsideTheSafeArea() {
        setUp()
        rule.onNodeWithTag("drawer_item_home").requestFocus()
        rule.waitForIdle()
        rule.onNodeWithTag("drawer_item_home").assertIsFocused()

        assertIconsInSafeArea("expanded")
        for (label in listOf(dummyUser.name, "Search", "Home", "Settings")) {
            val left = rule.onNodeWithText(label, useUnmergedTree = true).getBoundsInRoot().left
            assertTrue("The label $label starts at $left", left >= TvSafeArea.horizontal)
        }
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()

    /** #567: the user name and the server name, each on one line, inside the profile item. */
    private fun assertProfileLinesInsideTheItem() {
        // Past the labels' enter animation.
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        val item = bounds("drawer_item_profile")
        val name = bounds("drawer_profile_name")
        val server = bounds("drawer_profile_server")
        for ((label, line) in listOf("name" to name, "server" to server)) {
            assertTrue(
                "the $label line $line is outside the profile item $item",
                line.left >= item.left &&
                    line.right <= item.right &&
                    line.top >= item.top &&
                    line.bottom <= item.bottom,
            )
        }
        assertTrue("the server name $server is not below the name $name", server.top >= name.bottom)
        // One line each: titleMedium is 22.5 sp high, bodySmall 21 sp.
        assertTrue("the name takes ${name.height}", name.height <= 23.dp)
        assertTrue("the server name takes ${server.height}", server.height <= 21.dp)
    }

    @Test
    fun expandedProfileShowsBothLinesInsideTheItem() {
        setUp()
        rule.onNodeWithTag("drawer_item_profile").requestFocus()
        rule.waitForIdle()
        rule.onNodeWithTag("drawer_item_profile").assertIsFocused()

        assertProfileLinesInsideTheItem()
        rule.onNodeWithText(dummyServer.name, useUnmergedTree = true).assertExists()
        // The labels line up with the other items' labels.
        assertEquals(
            rule.onNodeWithText("Search", useUnmergedTree = true).getBoundsInRoot().left,
            bounds("drawer_profile_name").left,
        )
        assertEquals(DrawerProfileItemHeight, bounds("drawer_item_profile").height)
        // The avatar where it was: inside the safe area, 4 dp further in than collapsed.
        assertEquals(TvSafeArea.horizontal + 4.dp, bounds("drawer_icon_profile").left)
    }

    @Test
    fun longProfileNamesStayOnOneLineInsideTheItem() {
        setUp(
            user = dummyUser.copy(name = "A user name far too long for the drawer to show in full"),
            server = dummyServer.copy(name = "A server name far too long for the drawer as well"),
        )
        rule.onNodeWithTag("drawer_item_profile").requestFocus()
        rule.waitForIdle()

        assertProfileLinesInsideTheItem()
    }

    @Test
    fun collapsedProfileShowsNoLabels() {
        setUp()
        rule.onNodeWithTag("home_screen_content").requestFocus()
        rule.waitForIdle()

        rule.onNodeWithTag("drawer_profile_name", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(TvSafeArea.horizontal, bounds("drawer_icon_profile").left)
    }

    /**
     * The expanded drawer's items now end at 292 dp, right of a poster that starts at the page's
     * 128 dp and is 150 dp wide (Favorites). A plain Right finds nothing right of the item there and
     * left focus in the drawer.
     */
    @Test
    fun rightFromTheDrawerReachesAPosterThatEndsBeforeTheDrawerItem() {
        setUp(
            homeContent = {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.padding(start = TvSafeArea.horizontal, top = 120.dp)
                            .size(150.dp, 225.dp)
                            .focusable()
                            .testTag("poster")
                    )
                }
            }
        )
        rule.onNodeWithTag("poster").requestFocus()
        rule.waitForIdle()

        rule.onNodeWithTag("poster").performKeyInput { pressKey(Key.DirectionLeft) }
        rule.waitForIdle()
        rule.onNodeWithTag("drawer_item_home").assertIsFocused()

        rule.onNodeWithTag("drawer_item_home").performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        rule.onNodeWithTag("poster").assertIsFocused()
    }

    @Test
    fun pagesBesideTheDrawerStillStartAt80dp() {
        setUp()
        assertEquals(
            80.dp,
            rule.onNodeWithTag("home_screen_content").getBoundsInRoot().left,
        )
    }
}
