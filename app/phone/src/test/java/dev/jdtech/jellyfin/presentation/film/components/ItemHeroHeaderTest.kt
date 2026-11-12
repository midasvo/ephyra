package dev.jdtech.jellyfin.presentation.film.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderState
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.film.presentation.episode.EpisodeState
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.presentation.film.EpisodeWideContent
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.utils.LocalOfflineMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #429: the episode's header in a pane narrower than the window (the Season list-detail below
 * about 1230 dp, a 600 dp split-screen) stacks the still above the text, and no button of the
 * episode is squeezed or pushed out of the pane.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w1024dp-h640dp-land")
class ItemHeroHeaderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    // 30 minutes, 7 watched ("23 mins left" and the restart button), downloaded (delete button).
    private val episode =
        dummyEpisode.copy(
            runtimeTicks = 18_000_000_000L,
            playbackPositionTicks = 4_200_000_000L,
            sources =
                dummyEpisode.sources +
                    dummyEpisode.sources
                        .first()
                        .copy(type = FindroidSourceType.LOCAL, path = "e.mkv"),
        )

    @Test
    fun `the 1280 dp list-detail pane keeps the still beside the text`() {
        // Pane 799 dp: 751 inside the margins, 320 dp still, 24 dp gap: a 407 dp column.
        assertFalse(heroStacked(innerWidth = 751.dp, posterWidth = 320.dp, gap = 24.dp))
    }

    @Test
    fun `narrower list-detail panes and book posture stack`() {
        // 1024, 900 and 841 dp windows (panes 614, 540, 481) and a ~420 dp book-posture pane.
        listOf(566.dp, 492.dp, 433.dp, 372.dp).forEach { inner ->
            assertTrue(inner.toString(), heroStacked(inner, posterWidth = 320.dp, gap = 24.dp))
        }
    }

    @Test
    fun `medium windows stack below a 360 dp column`() {
        // Episode page: 600 dp split and 640 dp portrait stack, 701 dp (the fold) does not.
        assertTrue(heroStacked(552.dp, posterWidth = 256.dp, gap = 24.dp))
        assertTrue(heroStacked(592.dp, posterWidth = 256.dp, gap = 24.dp))
        assertFalse(heroStacked(653.dp, posterWidth = 256.dp, gap = 24.dp))
        // Movie page in a 600 dp split: its 160 dp poster leaves 368 dp, so it stays beside.
        assertFalse(heroStacked(552.dp, posterWidth = 160.dp, gap = 24.dp))
    }

    @Test
    fun `every button is whole and inside a 480 dp pane`() {
        show(paneWidth = 480.dp)

        listOf(
                CoreR.string.play_button_description,
                CoreR.string.play_from_beginning,
                CoreR.string.check_button_description,
                CoreR.string.favorite_button_description,
                CoreR.string.delete_download,
            )
            .forEach { id ->
                val icon = bounds(rule.activity.getString(id))
                assertTrue("$id squeezed: $icon", icon.right - icon.left >= 24.dp)
                assertTrue("$id outside the pane: $icon", icon.right <= 480.dp)
            }
        val playText =
            rule.onNodeWithText("23 mins left", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("Play text wrapped: $playText", playText.bottom - playText.top < 30.dp)
    }

    @Test
    fun `a narrow pane puts the still above the title`() {
        show(paneWidth = 480.dp)

        assertTrue(title().top >= still().bottom)
    }

    @Test
    fun `a wide pane keeps the title beside the still`() {
        show(paneWidth = 799.dp)

        assertTrue(title().left >= still().right)
        assertTrue(title().top < still().bottom)
    }

    private fun show(paneWidth: Dp) {
        rule.setContent {
            CompositionLocalProvider(LocalOfflineMode provides false) {
                FindroidTheme {
                    Box(modifier = Modifier.width(paneWidth)) {
                        EpisodeWideContent(
                            state = EpisodeState(episode = episode),
                            downloaderState = { DownloaderState() },
                            onAction = {},
                            onDownloaderAction = {},
                            onMediaInfoClick = {},
                            scrollState = rememberScrollState(),
                        )
                    }
                }
            }
        }
    }

    private fun bounds(description: String): DpRect =
        rule
            .onNodeWithContentDescription(description, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

    private fun still(): DpRect =
        bounds(rule.activity.getString(CoreR.string.image_description_poster, episode.name))

    private fun title(): DpRect =
        rule.onNodeWithText(episode.name, useUnmergedTree = true).getUnclippedBoundsInRoot()
}
