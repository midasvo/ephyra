package dev.jdtech.jellyfin.presentation.film.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyUser
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.presentation.users.UsersState
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #440: the profile sheet opens fully, so with a second user in a short landscape window the other
 * users show at once, and the sheet scrolls to Add user and Settings when they do not fit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w891dp-h411dp-land")
class ProfileBottomSheetTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int) = rule.activity.getString(id)

    private fun showSheet() {
        val other = User(id = UUID.randomUUID(), name = "Alice", serverId = "")
        rule.setContent {
            FindroidTheme {
                ProfileBottomSheetLayout(
                    state =
                        UsersState(
                            users = listOf(dummyUser, other),
                            serverName = "Jellyfin",
                            currentUserId = dummyUser.id,
                        ),
                    baseUrl = null,
                    onSwitchServer = {},
                    onPublicUserClick = {},
                    onAddUser = {},
                    onSettings = {},
                    onUserClick = {},
                    onDismissRequest = {},
                )
            }
        }
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
    }

    @Test
    fun `in landscape the sheet opens fully and shows the other user`() {
        showSheet()

        rule.onNodeWithText(string(SetupR.string.ce_users_switch_user)).assertIsDisplayed()
        rule.onNodeWithText("Alice").assertIsDisplayed()
    }

    @Test
    fun `in landscape Add user and Settings can be scrolled to in the sheet`() {
        showSheet()

        rule
            .onNodeWithText(string(SetupR.string.users_btn_add_user))
            .performScrollTo()
            .assertIsDisplayed()
        rule
            .onNodeWithText(string(CoreR.string.title_settings))
            .performScrollTo()
            .assertIsDisplayed()
    }
}
