package dev.jdtech.jellyfin

import android.app.Application
import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import dev.jdtech.jellyfin.film.presentation.season.SeasonState
import dev.jdtech.jellyfin.presentation.player.PlayerEpisodeSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #456: the episode sheet is 92 % opaque, so the player's top-row controls under it showed
 * through. The sheet reports where it opens, and the player hides the controls under it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
class EpisodeSheetCoverTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    // The sheet at 1280 x 800 dp, mdpi: 30 % of the width, at the right edge, full height.
    private val sheet = Rect(896, 0, 1280, 800)

    @Test
    fun `the sheet reports where it opens, before it has slid in`() {
        var reported: Rect? = null
        rule.mainClock.autoAdvance = false
        rule.setContent {
            PlayerEpisodeSheet(
                visibleState = MutableTransitionState(false).apply { targetState = true },
                state = SeasonState(),
                seasonNumber = 1,
                currentEpisodeId = null,
                onEpisodeClick = {},
                onDismiss = {},
                onClosed = {},
                onBoundsChanged = { reported = it },
            )
        }
        rule.mainClock.advanceTimeByFrame()
        val bounds = reported!!
        assertEquals(sheet.left, bounds.left)
        assertEquals(sheet.right, bounds.right)
        assertTrue(bounds.height() > 0)
    }

    @Test
    fun `top-row buttons under the sheet are covered, the others are not`() {
        // The tablet's top row at 1280 dp: speed, sleep timer, audio, subtitles and Episodes are
        // under the sheet, quality reaches into it with its padding, PiP ends before it.
        assertTrue(isUnderEpisodeSheet(Rect(928, 8, 984, 64), sheet)) // speed
        assertTrue(isUnderEpisodeSheet(Rect(1216, 8, 1272, 64), sheet)) // Episodes
        assertTrue(isUnderEpisodeSheet(Rect(856, 8, 912, 64), sheet)) // quality
        assertFalse(isUnderEpisodeSheet(Rect(784, 8, 840, 64), sheet)) // PiP
        // A button that only touches the sheet's edge is not under it.
        assertFalse(isUnderEpisodeSheet(Rect(840, 8, 896, 64), sheet))
    }

    // #523: the transport row (five 56 dp buttons, 48 dp apart, centred), the seek row along the
    // bottom and the skip button in the bottom right corner, in a window of the given size in dp.
    private fun lowerControls(width: Int, height: Int): Map<String, Rect> {
        val left = (width - 472) / 2
        val top = height / 2 - 28
        val names = listOf("previous", "rewind", "play/pause", "forward", "next")
        val transport =
            names.withIndex().associate { (i, name) ->
                name to Rect(left + i * 104, top, left + i * 104 + 56, top + 56)
            }
        return transport +
            mapOf(
                "seek row" to Rect(0, height - 102, width, height),
                "skip" to Rect(width - 164, height - 112, width - 24, height - 64),
            )
    }

    private fun sheetIn(width: Int, height: Int): Rect {
        val sheetWidth = (width * 0.3f).toInt().coerceIn(320, 420)
        return Rect(width - sheetWidth, 0, width, height)
    }

    @Test
    fun `the open sheet hides the seek row and the skip button, not the transport row at 1280 dp`() {
        val hidden = controlsUnderEpisodeSheet(lowerControls(1280, 800), sheetIn(1280, 800))
        assertEquals(setOf("seek row", "skip"), hidden)
    }

    @Test
    fun `the open sheet also hides the transport buttons it covers or cuts`() {
        // 8 inch tablet: Next is under the sheet.
        assertEquals(
            setOf("next", "seek row", "skip"),
            controlsUnderEpisodeSheet(lowerControls(1024, 640), sheetIn(1024, 640)),
        )
        // Unfolded foldable: the sheet's edge cuts the forward button, which counts as covered.
        assertEquals(
            setOf("forward", "next", "seek row", "skip"),
            controlsUnderEpisodeSheet(lowerControls(841, 701), sheetIn(841, 701)),
        )
    }

    @Test
    fun `play and pause is never hidden where the sheet is offered`() {
        // The sheet is offered from 840 dp wide, where it takes the largest share of the window.
        for (width in listOf(840, 841, 1024, 1067, 1280, 1400, 1600)) {
            val hidden = controlsUnderEpisodeSheet(lowerControls(width, 600), sheetIn(width, 600))
            assertFalse("at $width dp", "play/pause" in hidden)
            assertFalse("at $width dp", "rewind" in hidden)
        }
    }

    @Test
    fun `a closed sheet hides nothing`() {
        assertEquals(emptySet<String>(), controlsUnderEpisodeSheet(lowerControls(1280, 800), null))
    }
}
