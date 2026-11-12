package dev.jdtech.jellyfin.ce.presentation.film

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.film.presentation.movie.MovieAction
import dev.jdtech.jellyfin.film.presentation.movie.MovieState
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Resume, Restart and the time left on the TV movie page (#412). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp")
class MovieResumeTest {
    @get:Rule val rule = createComposeRule()

    private val actions = mutableListOf<MovieAction>()

    // 12 minutes, 4 watched.
    private val partlyWatched =
        dummyMovie.copy(
            trailer = null,
            runtimeTicks = 12 * TICKS_PER_MINUTE,
            playbackPositionTicks = 4 * TICKS_PER_MINUTE,
            played = false,
        )

    private fun setMovie(movie: FindroidMovie) {
        rule.setContent {
            FindroidTheme {
                MovieScreenLayout(state = MovieState(movie = movie), onAction = { actions += it })
            }
        }
        rule.waitForIdle()
    }

    /** Focuses the button or list item with [text] and presses OK on the D-pad. */
    private fun select(text: String) {
        rule.onNodeWithText(text).requestFocus()
        // The dialog has a focused element of its own, so press on this node.
        rule.onNodeWithText(text).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onNode(isFocused()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun assertAbsent(text: String) =
        assertEquals(0, rule.onAllNodesWithText(text).fetchSemanticsNodes().size)

    @Test
    fun `a partly watched movie offers Resume and Restart and says how much is left`() {
        setMovie(partlyWatched)

        rule.onNodeWithText("Resume").assertIsFocused()
        rule.onNodeWithText("Restart")
        rule.onNodeWithText("8 mins left")
        assertAbsent("Play")
    }

    @Test
    fun `Resume plays from the position and Restart from the beginning`() {
        setMovie(partlyWatched)

        press(Key.DirectionCenter)
        press(Key.DirectionRight)
        rule.onNodeWithText("Restart").assertIsFocused()
        press(Key.DirectionCenter)

        assertEquals(
            listOf(
                MovieAction.Play(startFromBeginning = false),
                MovieAction.Play(startFromBeginning = true),
            ),
            actions,
        )
    }

    @Test
    fun `a movie not started has Play only`() {
        setMovie(partlyWatched.copy(playbackPositionTicks = 0))

        rule.onNodeWithText("Play").assertIsFocused()
        assertAbsent("Resume")
        assertAbsent("Restart")
        assertAbsent("8 mins left")
    }

    @Test
    fun `a movie watched to the end has Play only`() {
        setMovie(partlyWatched.copy(played = true))

        rule.onNodeWithText("Play")
        assertAbsent("Resume")
        assertAbsent("Restart")
    }

    @Test
    fun `the version dialog remembers that Restart opened it`() {
        val first = partlyWatched.sources.first()
        setMovie(
            partlyWatched.copy(
                sources =
                    listOf(first.copy(id = "a", name = "720p"), first.copy(id = "b", name = "4K"))
            )
        )

        select("Restart")
        select("4K")
        select("Resume")
        select("720p")

        assertEquals(
            listOf(
                MovieAction.Play(startFromBeginning = true, mediaSourceId = "b"),
                MovieAction.Play(startFromBeginning = false, mediaSourceId = "a"),
            ),
            actions,
        )
    }

    private companion object {
        const val TICKS_PER_MINUTE = 600_000_000L
    }
}
