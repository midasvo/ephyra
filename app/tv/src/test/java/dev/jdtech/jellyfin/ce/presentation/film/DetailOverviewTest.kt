package dev.jdtech.jellyfin.ce.presentation.film

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isNotFocusable
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import dev.jdtech.jellyfin.ce.ui.components.TV_OVERVIEW_TAG
import dev.jdtech.jellyfin.ce.ui.components.TV_TEXT_DIALOG_TAG
import dev.jdtech.jellyfin.ce.ui.components.TV_TEXT_DIALOG_TEXT_TAG
import dev.jdtech.jellyfin.ce.ui.personCardTag
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.core.presentation.dummy.dummyPerson
import dev.jdtech.jellyfin.core.presentation.dummy.dummySeason
import dev.jdtech.jellyfin.core.presentation.dummy.dummyShow
import dev.jdtech.jellyfin.film.presentation.episode.EpisodeState
import dev.jdtech.jellyfin.film.presentation.movie.MovieState
import dev.jdtech.jellyfin.film.presentation.season.SeasonState
import dev.jdtech.jellyfin.film.presentation.show.ShowState
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * A long overview on the TV movie, show and season pages is cut, takes focus between the page's
 * top and its buttons, and opens in full on OK (#545). A short one takes no focus.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp-television")
class DetailOverviewTest {
    @get:Rule val rule = createComposeRule()

    private val actors =
        (0 until 6).map { dummyPerson.copy(id = UUID(2, it.toLong()), name = "Actor $it") }

    private val longOverview = "A long overview that wraps over several lines. ".repeat(20)

    private val longMovie =
        dummyMovie.copy(
            name = "Long Movie",
            trailer = null,
            playbackPositionTicks = 0,
            played = false,
            favorite = false,
            overview = longOverview,
        )

    private val overview = hasTestTag(TV_OVERVIEW_TAG)

    /** Like the Android TV default; Robolectric isn't a TV (see [DetailDownToRowTest]). */
    @OptIn(ExperimentalFoundationApi::class)
    private val tvPivot =
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float,
            ): Float = offset - containerSize * 0.3f
        }

    @OptIn(ExperimentalFoundationApi::class)
    private fun setTvContent(content: @Composable () -> Unit) {
        rule.setContent {
            CompositionLocalProvider(LocalBringIntoViewSpec provides tvPivot) {
                FindroidTheme { content() }
            }
        }
        rule.waitForIdle()
    }

    private fun setMovie(movie: FindroidMovie) = setTvContent {
        MovieScreenLayout(state = MovieState(movie = movie, actors = actors), onAction = {})
    }

    private fun press(key: Key) {
        rule.onNode(isFocused()).performKeyInput { pressKey(key) }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    /** OK opens the full text titled [title]; Back closes it and focus returns to the overview. */
    private fun assertOpensInFull(title: String, text: String) {
        press(Key.DirectionCenter)
        rule.onNodeWithTag(TV_TEXT_DIALOG_TEXT_TAG).assertIsFocused().assert(hasText(text))
        rule.onNodeWithTag(TV_TEXT_DIALOG_TAG).assert(hasAnyDescendant(hasText(title)))

        // Back on the remote, as the dialog's window gets it.
        rule.runOnUiThread { ShadowDialog.getLatestDialog().onBackPressed() }
        rule.waitForIdle()

        rule.onNodeWithTag(TV_TEXT_DIALOG_TEXT_TAG).assertDoesNotExist()
        rule.onNode(overview).assertIsFocused()
    }

    private fun assertOnScreen(text: String) {
        val bounds = rule.onNodeWithText(text).getUnclippedBoundsInRoot()
        assertTrue("$text is above the screen: $bounds", bounds.top >= 0.dp)
    }

    /** Focus is on one of the cast cards. */
    private val castFocused: SemanticsMatcher =
        actors
            .map { isFocused() and hasParent(hasTestTag(personCardTag(it.id))) }
            .reduce { a, b -> a or b }

    @Test
    fun `Up from Play focuses a cut movie overview, which opens in full`() {
        setMovie(longMovie)
        rule.onNodeWithText("Play").assertIsFocused()

        press(Key.DirectionUp)

        rule.onNode(overview).assertIsFocused()
        assertOnScreen(longMovie.name)
        assertOpensInFull(longMovie.name, longOverview)
    }

    @Test
    fun `Down from the overview returns to the button that was left, then reaches the cast`() {
        setMovie(longMovie)

        press(Key.DirectionRight)
        rule.onNodeWithText("Mark as played").assertIsFocused()
        press(Key.DirectionUp)
        rule.onNode(overview).assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithText("Mark as played").assertIsFocused()

        press(Key.DirectionDown)
        rule.onNode(castFocused).assertExists()
        press(Key.DirectionUp)
        rule.onNodeWithText("Mark as played").assertIsFocused()
        assertOnScreen(longMovie.name)
    }

    /** Pixels in the ring's colour around the overview, on the page's window (not the dialog's). */
    private fun ringPixelsAroundOverview(primary: Color): Int {
        val box = rule.onNode(overview).getBoundsInRoot()
        val area = Rect(box.left.value, box.top.value, box.right.value, box.bottom.value).inflate(8f)
        val pixels = rule.onAllNodes(isRoot()).onFirst().captureToImage().toPixelMap()
        var found = 0
        for (x in area.left.roundToInt().coerceAtLeast(0) until
            area.right.roundToInt().coerceAtMost(pixels.width)) {
            for (y in area.top.roundToInt().coerceAtLeast(0) until
                area.bottom.roundToInt().coerceAtMost(pixels.height)) {
                val c = pixels[x, y]
                if (
                    abs(c.red - primary.red) < 0.08f &&
                        abs(c.green - primary.green) < 0.08f &&
                        abs(c.blue - primary.blue) < 0.08f
                ) {
                    found++
                }
            }
        }
        return found
    }

    @Test
    fun `the page's overview shows no ring while its dialog is open`() {
        var primary = Color.Unspecified
        setTvContent {
            primary = MaterialTheme.colorScheme.primary
            MovieScreenLayout(state = MovieState(movie = longMovie, actors = actors), onAction = {})
        }
        press(Key.DirectionUp)
        rule.onNode(overview).assertIsFocused()
        val focused = ringPixelsAroundOverview(primary)
        assertTrue("$focused ring pixels around the focused overview", focused > 100)

        press(Key.DirectionCenter)
        rule.onNodeWithTag(TV_TEXT_DIALOG_TEXT_TAG).assertIsFocused()
        assertEquals(0, ringPixelsAroundOverview(primary))

        rule.runOnUiThread { ShadowDialog.getLatestDialog().onBackPressed() }
        rule.waitForIdle()
        rule.onNode(overview).assertIsFocused()
        assertTrue(ringPixelsAroundOverview(primary) > 100)
    }

    @Test
    fun `a movie overview that fits takes no focus`() {
        setMovie(longMovie.copy(overview = "Short."))
        rule.onNode(overview).assert(isNotFocusable())

        press(Key.DirectionUp)

        rule.onNodeWithText("Play").assertIsFocused()
    }

    @Test
    fun `Up from Play focuses a cut show overview, which opens in full`() {
        val show = dummyShow.copy(name = "Long Show", overview = longOverview)
        setTvContent {
            ShowScreenLayout(
                state =
                    ShowState(
                        show = show,
                        seasons = listOf(dummySeason),
                        actors = actors,
                    ),
                onAction = {},
            )
        }
        rule.onNodeWithText("Play").assertIsFocused()

        press(Key.DirectionUp)

        rule.onNode(overview).assertIsFocused()
        assertOnScreen(show.name)
        assertOpensInFull(show.name, longOverview)
    }

    private val seasons =
        (1..2).map { dummySeason.copy(id = UUID(4, it.toLong()), name = "Season $it") }

    private val longEpisode =
        dummyEpisode.copy(
            id = UUID(5, 1),
            name = "Long Episode",
            indexNumber = 1,
            overview = longOverview,
            played = false,
            playbackPositionTicks = 0,
        )
    private val shortEpisode =
        longEpisode.copy(
            id = UUID(5, 2),
            name = "Short Episode",
            indexNumber = 2,
            overview = "Short.",
        )

    private fun setSeason() = setTvContent {
        val state =
            SeasonState(
                season = seasons[0],
                seriesSeasons = seasons,
                episodes = listOf(longEpisode, shortEpisode),
                isLoading = false,
            )
        SeasonScreenLayout(
            state = state,
            loadError = null,
            onRetry = {},
            episodes = state.episodes,
            selection = EpisodeSelection(longEpisode.id.toString()),
            episodeState = { EpisodeState() },
            onSelectSeason = {},
            onPlay = { _, _, _ -> },
            onAction = {},
        )
    }

    private fun episodeCard(name: String): SemanticsMatcher =
        isFocused() and hasParent(hasAnyDescendant(hasText(name, substring = true)))

    @Test
    fun `on the season page a cut episode overview sits between the buttons and the seasons`() {
        setSeason()
        rule.onNode(episodeCard(longEpisode.name)).assertExists()

        press(Key.DirectionUp)
        rule.onNodeWithText("Play").assertIsFocused()
        press(Key.DirectionUp)
        rule.onNode(overview).assertIsFocused()
        press(Key.DirectionUp)
        rule.onNodeWithText("Season 1").assertIsFocused()

        press(Key.DirectionDown)
        rule.onNode(overview).assertIsFocused()
        assertOpensInFull(longEpisode.name, longOverview)
        press(Key.DirectionDown)
        rule.onNodeWithText("Play").assertIsFocused()
    }

    @Test
    fun `moving between episodes changes whether the overview takes focus, not the focus`() {
        setSeason()
        rule.onNode(overview).assert(isFocusable())

        press(Key.DirectionRight)
        rule.onNode(episodeCard(shortEpisode.name)).assertExists()
        rule.onNode(overview).assert(isNotFocusable()).assert(hasAnyDescendant(hasText("Short.")))

        press(Key.DirectionLeft)
        rule.onNode(episodeCard(longEpisode.name)).assertExists()
        rule.onNode(overview).assert(isFocusable())
    }
}
