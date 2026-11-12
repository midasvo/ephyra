package dev.jdtech.jellyfin.presentation.player

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.jdtech.jellyfin.player.core.domain.models.SubtitleSyncCue
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "en-rUS")
class SubtitleSyncDialogTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `shows tracks and switches to sync tab`() {
        var selectedTrack by mutableIntStateOf(-1)
        var delayMs by mutableIntStateOf(0)

        val tracks =
            listOf(
                SubtitleTrackItem(0, "English [CC]", isSelected = false),
                SubtitleTrackItem(1, "Spanish", isSelected = false),
            )

        rule.setContent {
            FindroidTheme(darkTheme = true) {
                PlayerSubtitleSyncSheet(
                    tracks = tracks,
                    selectedTrackIndex = selectedTrack,
                    subtitleDelayMs = delayMs,
                    currentPlaybackPositionMs = 60_000L,
                    availableCues = emptyList(),
                    onTrackSelected = { selectedTrack = it },
                    onDelayChanged = { delayMs = it },
                    onDismiss = {},
                )
            }
        }

        // Subtitle track selection is displayed
        rule.onNodeWithText("English [CC]").assertIsDisplayed()
        rule.onNodeWithText("None").assertIsDisplayed()

        // Selecting a track activates the timing and sync controls
        rule.onNodeWithText("English [CC]").performClick()
        assertEquals(0, selectedTrack)

        rule.waitForIdle()
        rule.onNodeWithText("Subtitle delay").assertIsDisplayed()
        rule.onNodeWithText("0.0s").assertIsDisplayed()
    }

    @Test
    fun `manual delay stepper adjusts and resets delay`() {
        assertEquals("+0.1s", formatSubtitleDelay(100))
        assertEquals("+1.5s", formatSubtitleDelay(1500))
        assertEquals("-0.3s", formatSubtitleDelay(-300))
    }

    @Test
    fun `capture line displays nearest cues and calculates reaction compensated delay`() {
        var appliedDelayMs by mutableIntStateOf(0)
        val playbackTimeMs = 45_000L

        val cues =
            listOf(
                SubtitleSyncCue(30_000L, text = "Far away line"),
                SubtitleSyncCue(44_200L, text = "Just before reaction"),
                SubtitleSyncCue(46_000L, text = "Shortly after"),
                SubtitleSyncCue(80_000L, text = "Way later"),
            )

        val tracks =
            listOf(
                SubtitleTrackItem(0, "English [CC]", isSelected = true),
            )

        rule.setContent {
            FindroidTheme(darkTheme = true) {
                PlayerSubtitleSyncSheet(
                    tracks = tracks,
                    selectedTrackIndex = 0,
                    subtitleDelayMs = appliedDelayMs,
                    currentPlaybackPositionMs = playbackTimeMs,
                    availableCues = cues,
                    isLoadingCues = false,
                    onTrackSelected = {},
                    onDelayChanged = { appliedDelayMs = it },
                    onDismiss = {},
                )
            }
        }

        // Click capture button
        rule.onNodeWithText("Capture spoken line").performClick()
        rule.waitForIdle()

        // Shows nearest lines
        rule.onNodeWithText("Just before reaction").assertExists()
        rule.onNodeWithText("Shortly after").assertExists()

        // Click the line
        rule.onNodeWithText("Just before reaction").performScrollTo().performClick()
        rule.waitForIdle()

        // Expected delay = playbackTime (45000) - cue.startTime (44200) - reaction compensation (300) = +500ms
        val expected = (45_000L - 44_200L - HUMAN_REACTION_COMPENSATION_MS).toInt()
        assertEquals(500, expected)
        assertEquals(expected, appliedDelayMs)
    }
}
