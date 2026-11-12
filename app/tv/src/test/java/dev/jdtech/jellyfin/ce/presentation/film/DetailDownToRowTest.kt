package dev.jdtech.jellyfin.ce.presentation.film

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.ce.ui.personCardTag
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.core.presentation.dummy.dummyPerson
import dev.jdtech.jellyfin.core.presentation.dummy.dummySeason
import dev.jdtech.jellyfin.core.presentation.dummy.dummyShow
import dev.jdtech.jellyfin.film.presentation.movie.MovieState
import dev.jdtech.jellyfin.film.presentation.show.ShowState
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Down from the buttons of the TV movie and show pages reaches the next row, also while that row is
 * below the screen, and moving along the buttons keeps the title on screen (#514).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp-television")
class DetailDownToRowTest {
    @get:Rule val rule = createComposeRule()

    private val actors =
        (0 until 6).map { dummyPerson.copy(id = UUID(2, it.toLong()), name = "Actor $it") }

    private val longOverview = "A long overview that wraps over several lines. ".repeat(20)
    private val studios = (0 until 30).map { "Studio with a long name $it" }

    private val tallMovie =
        dummyMovie.copy(
            trailer = null,
            playbackPositionTicks = 0,
            played = false,
            favorite = false,
            tagline = "A tagline",
            overview = longOverview,
            genres = listOf("Comedy", "Romance", "Crime"),
            studios = studios,
        )

    private val shortMovie =
        tallMovie.copy(
            tagline = null,
            overview = "Short.",
            genres = emptyList(),
            studios = emptyList(),
        )

    /**
     * Like the Android TV default, it scrolls a focused child to a third of the list. Robolectric
     * isn't a TV, so the test provides it.
     */
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

    private fun assertCastCardFocused(index: Int) {
        rule.onNode(isFocused() and hasParent(hasTestTag(personCardTag(actors[index].id))))
            .assertExists()
    }

    private fun assertOnScreen(text: String) {
        val bounds = rule.onNodeWithText(text).getUnclippedBoundsInRoot()
        assertTrue("$text is above the screen: $bounds", bounds.top >= 0.dp)
    }

    @Test
    fun `Down from Play focuses the first cast card while the row is below the screen`() {
        setMovie(tallMovie)
        rule.onNodeWithText("Play").assertIsFocused()

        press(Key.DirectionDown)

        assertCastCardFocused(0)
    }

    @Test
    fun `Down from Play focuses the first cast card while the row is on screen`() {
        setMovie(shortMovie)

        press(Key.DirectionDown)

        assertCastCardFocused(0)
    }

    @Test
    fun `moving along the buttons keeps the title on screen`() {
        setMovie(tallMovie)

        press(Key.DirectionRight)
        rule.onNodeWithText("Mark as played").assertIsFocused()
        press(Key.DirectionRight)
        rule.onNodeWithText("Add to favorites").assertIsFocused()

        assertOnScreen(tallMovie.name)
    }

    @Test
    fun `Up from the cast returns to the button that was left and scrolls back to the top`() {
        setMovie(tallMovie)

        press(Key.DirectionRight)
        press(Key.DirectionDown)
        assertCastCardFocused(0)
        press(Key.DirectionUp)

        rule.onNodeWithText("Mark as played").assertIsFocused()
        assertOnScreen(tallMovie.name)
    }

    @Test
    fun `Down from Play on the show page focuses the first season while it is below the screen`() {
        val seasons =
            (0 until 3).map {
                dummySeason.copy(id = UUID(3, it.toLong()), name = "Season card $it")
            }
        setTvContent {
            ShowScreenLayout(
                state =
                    ShowState(
                        show =
                            dummyShow.copy(
                                overview = longOverview,
                                genres = listOf("Comedy", "Romance", "Crime"),
                                studios = studios,
                            ),
                        seasons = seasons,
                        actors = actors,
                        director = actors[0],
                        writers = actors,
                    ),
                onAction = {},
            )
        }
        rule.onNodeWithText("Play").assertIsFocused()

        press(Key.DirectionDown)

        rule.onNode(isFocused() and hasParent(hasAnyDescendant(hasText("Season card 0"))))
            .assertExists()
    }
}
