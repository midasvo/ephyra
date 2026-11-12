package dev.jdtech.jellyfin.ce.presentation.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.settings.R as SettingsR
import dev.jdtech.jellyfin.settings.domain.models.Preference
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceGroup
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceSelect
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The details pane of a TV settings page gives focus back to the row that opened it (#419). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1920dp-h1080dp")
class TvSettingsLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var pagesLeft = 0

    private fun select(name: Int) =
        PreferenceSelect(
            nameStringResource = name,
            backendPreference = Preference("", null),
            options = SettingsR.array.languages,
            optionValues = SettingsR.array.languages_values,
            optionsIncludeNull = true,
        )

    private fun setUp() {
        rule.setContent {
            // Stands in for the navigation's Back, which leaves the page.
            BackHandler { pagesLeft++ }
            FindroidTheme {
                TvSettingsLayout(
                    title = "Language",
                    groups =
                        listOf(
                            PreferenceGroup(
                                preferences =
                                    listOf(
                                        select(SettingsR.string.settings_preferred_audio_language),
                                        select(
                                            SettingsR.string.settings_preferred_subtitle_language
                                        ),
                                    )
                            )
                        ),
                    onAction = {},
                )
            }
        }
        rule.waitForIdle()
    }

    private fun press(keys: List<Key>) {
        keys.forEach { key ->
            rule.onNode(isFocused()).performKeyInput { pressKey(key) }
            rule.waitForIdle()
        }
    }

    private fun pressBack() {
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun assertFocused(text: String) {
        rule.onNode(isFocused()).assert(hasText(text, substring = true))
    }

    /** OK on the audio row, then two options down the pane, level with the subtitle row. */
    private fun openPaneAndMoveDown() {
        assertFocused("Preferred audio language")
        press(listOf(Key.DirectionCenter))
        assertFocused("Not set")
        press(listOf(Key.DirectionDown, Key.DirectionDown))
        assertFocused("Afar")
    }

    @Test
    fun leftFromThePaneReturnsToTheRowThatOpenedIt() {
        setUp()
        openPaneAndMoveDown()

        press(listOf(Key.DirectionLeft))

        assertFocused("Preferred audio language")
    }

    @Test
    fun backFromThePaneReturnsToTheRowAndASecondBackLeavesThePage() {
        setUp()
        openPaneAndMoveDown()

        pressBack()
        assertFocused("Preferred audio language")
        assertEquals(0, pagesLeft)

        pressBack()
        assertEquals(1, pagesLeft)
    }

    @Test
    fun backFromARowLeavesThePageInOnePress() {
        setUp()
        assertFocused("Preferred audio language")

        pressBack()

        assertEquals(1, pagesLeft)
    }
}
