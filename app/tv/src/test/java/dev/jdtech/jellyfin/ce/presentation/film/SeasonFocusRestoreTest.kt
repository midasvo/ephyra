package dev.jdtech.jellyfin.ce.presentation.film

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.jdtech.jellyfin.MainActivity
import dev.jdtech.jellyfin.ce.ui.focus.RestoreFocus
import dev.jdtech.jellyfin.ce.ui.focus.focusPosition
import dev.jdtech.jellyfin.ce.ui.focus.rememberFocusPosition
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.core.presentation.dummy.dummySeason
import dev.jdtech.jellyfin.film.presentation.episode.EpisodeState
import dev.jdtech.jellyfin.film.presentation.season.SeasonState
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.Ticks
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Focus restoration on the TV season screen and [RestoreFocus] protection against stray initial
 * focus before restoration completes (#1008).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp-television")
class SeasonFocusRestoreTest {
    @get:Rule val rule = createComposeRule()

    private val season1 =
        dummySeason.copy(id = UUID(7, 1).toString().toItemId(), name = "Season 1", indexNumber = 1)
    private val season2 =
        dummySeason.copy(id = UUID(7, 2).toString().toItemId(), name = "Season 2", indexNumber = 2)

    private val episodes: List<Episode> =
        (1..5).map {
            dummyEpisode.copy(
                id = UUID(7, 10L + it).toString().toItemId(),
                name = "Episode $it",
                indexNumber = it,
                seasonId = season1.id,
                seriesId = season1.seriesId,
                runtimeTicks = 12 * Ticks.PER_MINUTE,
                playbackPositionTicks = 0,
                played = false,
            )
        }

    private var playerOpen by mutableStateOf(false)
    private var lastSelection: EpisodeSelection? = null

    private fun setUpSeason() {
        rule.setContent {
            val stateHolder = rememberSaveableStateHolder()
            FindroidTheme {
                if (playerOpen) {
                    stateHolder.SaveableStateProvider("player") { Box {} }
                } else {
                    stateHolder.SaveableStateProvider("season") {
                        val selection =
                            rememberSaveable(saver = EpisodeSelection.Saver) {
                                EpisodeSelection(null)
                            }
                        lastSelection = selection
                        SeasonScreenLayout(
                            state =
                                SeasonState(
                                    season = season1,
                                    episodes = episodes,
                                    seriesSeasons = listOf(season1, season2),
                                    isLoading = false,
                                ),
                            loadError = null,
                            onRetry = {},
                            episodes = episodes,
                            selection = selection,
                            episodeState = { EpisodeState() },
                            onSelectSeason = {},
                            onPlay = { _, _, _ -> playerOpen = true },
                            onAction = {},
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onNode(isFocused()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test
    fun `returning to season restores focus to the previously focused episode card`() {
        setUpSeason()
        repeat(3) { press(Key.DirectionRight) }
        assertEquals(episodes[3].id.asString(), lastSelection?.id)

        playerOpen = true
        rule.waitForIdle()
        playerOpen = false
        rule.waitForIdle()

        rule.onNodeWithText("Season 1").assertIsNotFocused()
        assertEquals(episodes[3].id.asString(), lastSelection?.id)
        // Moving right once from Episode 4 focuses Episode 5.
        press(Key.DirectionRight)
        assertEquals(episodes[4].id.asString(), lastSelection?.id)
        // Moving back left focuses Episode 4 again.
        press(Key.DirectionLeft)
        assertEquals(episodes[3].id.asString(), lastSelection?.id)
    }

    @Test
    fun `initial focus on Season 1 chip before RestoreFocus completes does not clobber saved episode`() {
        setUpSeason()
        repeat(3) { press(Key.DirectionRight) }
        assertEquals(episodes[3].id.asString(), lastSelection?.id)

        playerOpen = true
        rule.waitForIdle()

        // Pause the clock as the season re-enters composition so we can simulate Android's initial
        // window focus landing on the first focusable node ("Season 1" chip) before RestoreFocus
        // finishes waiting its frame.
        rule.mainClock.autoAdvance = false
        try {
            playerOpen = false
            Snapshot.sendApplyNotifications()
            rule.mainClock.advanceTimeByFrame()
            rule.onNodeWithText("Season 1").requestFocus()
            rule.onNodeWithText("Season 1").assertIsFocused()
        } finally {
            rule.mainClock.autoAdvance = true
        }
        rule.waitForIdle()

        rule.onNodeWithText("Season 1").assertIsNotFocused()
        assertEquals(episodes[3].id.asString(), lastSelection?.id)
        press(Key.DirectionRight)
        assertEquals(episodes[4].id.asString(), lastSelection?.id)
    }

    @Test
    fun `focus gained while RestoreFocus is waiting does not overwrite saved position and later moves still save`() {
        var ready by mutableStateOf(true)
        var screenOpen by mutableStateOf(true)
        rule.setContent {
            val stateHolder = rememberSaveableStateHolder()
            FindroidTheme {
                if (!screenOpen) {
                    stateHolder.SaveableStateProvider("other") { Box {} }
                } else {
                    stateHolder.SaveableStateProvider("target") {
                        val position = rememberFocusPosition()
                        Row {
                            repeat(3) { col ->
                                Box(
                                    Modifier.size(64.dp)
                                        .focusPosition(position, row = 0, column = col)
                                        .testTag("item_$col")
                                        .focusable()
                                )
                            }
                        }
                        RestoreFocus(position = position, ready = ready)
                    }
                }
            }
        }
        rule.waitForIdle()

        // Move to item_2, then leave.
        rule.onNodeWithTag("item_2").requestFocus()
        rule.onNodeWithTag("item_2").assertIsFocused()

        ready = false
        screenOpen = false
        rule.waitForIdle()

        // Re-enter while still waiting for ready = true; stray focus on item_0 must be ignored.
        screenOpen = true
        rule.waitForIdle()
        rule.onNodeWithTag("item_0").requestFocus()
        rule.onNodeWithTag("item_0").assertIsFocused()

        // Once ready, RestoreFocus puts focus back on item_2.
        ready = true
        rule.waitForIdle()
        rule.onNodeWithTag("item_2").assertIsFocused()

        // Subsequent user moves after restoration completes still save normally.
        rule.onNodeWithTag("item_1").requestFocus()
        screenOpen = false
        rule.waitForIdle()
        screenOpen = true
        rule.waitForIdle()
        rule.onNodeWithTag("item_1").assertIsFocused()
    }

    @Test
    fun `MainActivity handles keyboard and navigation config changes without relaunching`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info =
            context.packageManager.getActivityInfo(
                ComponentName(context, MainActivity::class.java),
                0,
            )
        val required =
            ActivityInfo.CONFIG_KEYBOARD or
                ActivityInfo.CONFIG_KEYBOARD_HIDDEN or
                ActivityInfo.CONFIG_NAVIGATION
        assertEquals(required, info.configChanges and required)
    }
}
