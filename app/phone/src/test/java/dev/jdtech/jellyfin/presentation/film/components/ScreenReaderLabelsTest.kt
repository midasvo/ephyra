package dev.jdtech.jellyfin.presentation.film.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.core.presentation.dummy.dummyShow
import dev.jdtech.jellyfin.core.presentation.dummy.dummyUser
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.HomeItem
import dev.jdtech.jellyfin.models.View
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.utils.LocalOfflineMode
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #441: what a screen reader says for the Home buttons and the badges on a card. Every button has
 * a label, and the watched tick no longer says "Episode watched indicator" on a movie.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "en-rUS-w411dp-h914dp")
class ScreenReaderLabelsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int) = rule.activity.getString(id)

    @Test
    fun `the Home header buttons say what they do`() {
        rule.setContent {
            FindroidTheme {
                CompositionLocalProvider(LocalOfflineMode provides false) {
                    HomeHeader(
                        serverName = "Ephyra Test",
                        onServerClick = {},
                        onSearchClick = {},
                        onUserClick = {},
                        user = dummyUser,
                    )
                }
            }
        }

        rule.onNodeWithContentDescription("Search").assertHasClickAction()
        rule.onNodeWithContentDescription("Profile").assertHasClickAction()
        // The avatar's initial is not read as the button's name.
        rule.onAllNodesWithText(dummyUser.name.take(1).uppercase()).assertCountEquals(0)
    }

    @Test
    fun `the arrow of a Latest row says it opens the library`() {
        val view = View(UUID.randomUUID(), "Movies", listOf(dummyMovie), CollectionType.Movies)
        rule.setContent {
            FindroidTheme {
                HomeView(
                    view = HomeItem.ViewItem(view),
                    itemsPadding = PaddingValues(),
                    onAction = {},
                )
            }
        }

        rule.onNodeWithContentDescription(string(CoreR.string.view_all)).assertHasClickAction()
    }

    @Test
    fun `a watched movie says Watched, not Episode watched indicator`() {
        rule.setContent {
            FindroidTheme {
                ItemCard(
                    item = dummyMovie.copy(played = true),
                    direction = Direction.VERTICAL,
                    onClick = {},
                )
            }
        }

        rule.onAllNodesWithContentDescription("Watched").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Episode watched indicator").assertCountEquals(0)
    }

    @Test
    fun `the unwatched count says what it counts`() {
        rule.setContent {
            FindroidTheme {
                ItemCard(
                    item = dummyShow.copy(unplayedItemCount = 5),
                    direction = Direction.VERTICAL,
                    onClick = {},
                )
            }
        }

        rule.onAllNodesWithContentDescription("5 unwatched").assertCountEquals(1)
    }
}
