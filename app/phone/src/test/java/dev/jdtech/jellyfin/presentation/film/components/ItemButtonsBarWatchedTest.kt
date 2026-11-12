package dev.jdtech.jellyfin.presentation.film.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.utils.LocalOfflineMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #397: the watched button is a toggle, on when the item is watched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ItemButtonsBarWatchedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var clicks = 0

    @Test
    fun `a watched item has the button on`() {
        show(played = true)

        watchedButton().assertIsOn()
    }

    @Test
    fun `an unwatched item has the button off`() {
        show(played = false)

        watchedButton().assertIsOff()
    }

    @Test
    fun `tapping the button asks to change the watched state`() {
        show(played = false)

        watchedButton().performClick()

        assertEquals(1, clicks)
    }

    private fun watchedButton() =
        rule.onNodeWithContentDescription(
            rule.activity.getString(CoreR.string.check_button_description)
        )

    private fun show(played: Boolean) {
        rule.setContent {
            FindroidTheme {
                CompositionLocalProvider(LocalOfflineMode provides false) {
                    ItemButtonsBar(
                        item = dummyEpisode.copy(played = played),
                        onPlayClick = {},
                        onMarkAsPlayedClick = { clicks++ },
                        onMarkAsFavoriteClick = {},
                        onDownloadClick = {},
                        onDownloadCancelClick = {},
                        onDownloadDeleteClick = {},
                        onTrailerClick = {},
                    )
                }
            }
        }
    }
}
