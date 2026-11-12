package dev.jdtech.jellyfin.presentation.setup.users

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import dev.jdtech.jellyfin.core.presentation.dummy.dummyUser
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.setup.presentation.users.UsersAction
import dev.jdtech.jellyfin.setup.presentation.users.UsersState
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #541 (ADR 0003): Back to the Users screen puts focus on what was opened from it, not on the
 * signed-in user. A SaveableStateHolder takes the screen out of the composition and back, the way
 * Navigation 3 does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp")
class UsersFocusRestoreTest {
    @get:Rule val rule = createComposeRule()

    private val current =
        dummyUser.copy(id = UUID.fromString("00000000-0000-0000-0000-000000000001"), name = "Midas")
    private val guest =
        dummyUser.copy(id = UUID.fromString("00000000-0000-0000-0000-000000000003"), name = "Guest")

    private var away by mutableStateOf(false)

    private fun setUp(state: UsersState) {
        rule.setContent {
            val stateHolder = rememberSaveableStateHolder()
            FindroidTheme {
                if (away) {
                    stateHolder.SaveableStateProvider("login") { Box {} }
                } else {
                    stateHolder.SaveableStateProvider("users") {
                        UsersScreenLayout(
                            state = state,
                            onAction = { action ->
                                when (action) {
                                    is UsersAction.OnAddClick,
                                    is UsersAction.OnChangeServerClick,
                                    is UsersAction.OnPublicUserClick -> away = true
                                    else -> Unit
                                }
                            },
                            baseUrl = "",
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private val withUsers =
        UsersState(
            users = listOf(current),
            publicUsers = listOf(guest),
            serverName = "Demo",
            currentUserId = current.id,
        )

    /** A user's face, found by the initial it shows. */
    private fun card(initial: String): SemanticsMatcher =
        isFocusable() and (hasText(initial) or hasAnyDescendant(hasText(initial)))

    private fun openAndComeBack(matcher: SemanticsMatcher) {
        rule.onNode(matcher).requestFocus()
        rule.onNode(matcher).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        away = false
        rule.waitForIdle()
    }

    @Test
    fun firstVisitFocusesTheSignedInUser() {
        setUp(withUsers)
        rule.onNode(card("M")).assertIsFocused()
    }

    @Test
    fun backFromAddUserFocusesAddUser() {
        setUp(withUsers)
        val add = hasTestTag(USERS_ADD_TAG)

        openAndComeBack(add)

        rule.onNodeWithTag(USERS_ADD_TAG).assertIsFocused()
    }

    @Test
    fun backFromSwitchServerFocusesSwitchServer() {
        setUp(withUsers)
        val server = hasTestTag(USERS_SERVER_TAG)

        openAndComeBack(server)

        rule.onNodeWithTag(USERS_SERVER_TAG).assertIsFocused()
    }

    @Test
    fun backFromAPublicUserFocusesThatUser() {
        setUp(withUsers)

        openAndComeBack(card("G"))

        rule.onNode(card("G")).assertIsFocused()
    }

    @Test
    fun noUsersFocusesAddUser() {
        setUp(UsersState(serverName = "Demo"))
        rule.onNodeWithTag(USERS_ADD_TAG).assertIsFocused()
    }
}
