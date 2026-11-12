package dev.jdtech.jellyfin.presentation.film

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #435: the Season page's episode list keeps its position over a rotation, which recreates the
 * activity and may switch between the single-column page and the list-detail.
 *
 * The list here is 100 dp items in a 400 dp box, so it can scroll 100 dp per item past the
 * first four.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SeasonListStateTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var bottomPadding by mutableStateOf(0.dp)
    private var itemHeight by mutableStateOf(100.dp)
    private var itemCount by mutableStateOf(30)

    private fun LazyListState.position() = firstVisibleItemIndex to firstVisibleItemScrollOffset

    @Test
    fun `a position clamped by late insets is applied again once they arrive`() {
        // Scrolled to the very end with a 60 dp navigation bar: item 26 at 60 dp. Measured first
        // without the bar, the list can only reach item 26 at 0.
        val state = LazyListState(26, px(60))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            EpisodeList(state)
            LaunchedEffect(state) { restorePosition(state, target = 26 to px(60)) }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        assertEquals(26 to 0, state.position())

        bottomPadding = 60.dp
        repeat(3) { rule.mainClock.advanceTimeByFrame() }
        assertEquals(26 to px(60), state.position())
    }

    @Test
    fun `a scroll during the restore is never undone`() {
        val state = LazyListState(26, px(60))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            EpisodeList(state)
            LaunchedEffect(state) { restorePosition(state, target = 26 to px(60)) }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()

        // The user, or the list-detail revealing the selected episode, moves the list.
        rule.runOnIdle { runBlocking { state.scrollToItem(3) } }
        bottomPadding = 60.dp
        repeat(5) { rule.mainClock.advanceTimeByFrame() }
        assertEquals(3 to 0, state.position())
    }

    @Test
    fun `a first open is left at the top`() {
        val state = LazyListState()
        rule.setContent {
            EpisodeList(state)
            LaunchedEffect(state) { restorePosition(state, target = 0 to 0) }
        }
        rule.waitForIdle()
        assertEquals(0 to 0, state.position())
    }

    @Test
    fun `rotating through a shorter list and back keeps the position`() {
        val tester = StateRestorationTester(rule)
        lateinit var state: LazyListState
        tester.setContent {
            state = rememberSeasonListState()
            EpisodeList(state)
        }
        rule.runOnIdle { runBlocking { state.scrollToItem(20, px(30)) } }
        rule.waitForIdle()

        // The other orientation's list is shorter (its rows are 20 dp: 600 dp in a 400 dp box),
        // so it can only show its end.
        itemHeight = 20.dp
        restore(tester)
        assertEquals(10 to 0, state.position())

        // Back: the position the user left, not the clamped one.
        itemHeight = 100.dp
        restore(tester)
        assertEquals(20 to px(30), state.position())
    }

    @Test
    fun `a scroll after the restore is what the next rotation keeps`() {
        val tester = StateRestorationTester(rule)
        lateinit var state: LazyListState
        tester.setContent {
            state = rememberSeasonListState()
            EpisodeList(state)
        }
        rule.runOnIdle { runBlocking { state.scrollToItem(20) } }
        rule.waitForIdle()
        restore(tester)

        rule.runOnIdle { runBlocking { state.scrollToItem(7) } }
        rule.waitForIdle()
        restore(tester)
        assertEquals(7 to 0, state.position())
    }

    @Test
    fun `a position the season no longer has is given up and saving goes on`() {
        val tester = StateRestorationTester(rule)
        lateinit var state: LazyListState
        tester.setContent {
            state = rememberSeasonListState()
            EpisodeList(state)
        }
        rule.runOnIdle { runBlocking { state.scrollToItem(20) } }
        rule.waitForIdle()

        // The season comes back with 5 episodes (offline, only the downloaded ones): item 20 is
        // gone. The list stays usable where it is.
        itemCount = 5
        restore(tester)
        rule.runOnIdle { runBlocking { state.scrollToItem(0, px(50)) } }
        rule.waitForIdle()
        assertEquals(0 to px(50), state.position())

        // That scroll was saved: with all 30 back, the list opens there, not at item 20.
        itemCount = 30
        restore(tester)
        assertEquals(0 to px(50), state.position())
    }

    @Composable
    private fun EpisodeList(state: LazyListState) {
        // Read once: like an activity, a composition never sees the next rotation's size.
        val rowHeight = remember { itemHeight }
        val rows = remember { itemCount }
        Box(Modifier.height(400.dp)) {
            LazyColumn(state = state, contentPadding = PaddingValues(bottom = bottomPadding)) {
                items(rows) { Spacer(Modifier.fillMaxWidth().height(rowHeight)) }
            }
        }
    }

    /** A rotation: the activity's saved state restored, then three seconds of frames. */
    private fun restore(tester: StateRestorationTester) {
        tester.emulateSavedInstanceStateRestore()
        rule.mainClock.advanceTimeBy(3_000)
        rule.waitForIdle()
    }

    private fun px(dp: Int): Int = (dp * rule.activity.resources.displayMetrics.density).toInt()
}
