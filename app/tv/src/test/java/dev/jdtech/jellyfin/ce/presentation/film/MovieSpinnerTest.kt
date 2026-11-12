package dev.jdtech.jellyfin.ce.presentation.film

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.film.presentation.movie.MovieState
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The TV movie page drops its spinner once the movie has loaded (#544). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp-television")
class MovieSpinnerTest {
    @get:Rule val rule = createComposeRule()

    private fun spinners(state: MovieState): Int {
        rule.setContent { FindroidTheme { MovieScreenLayout(state = state, onAction = {}) } }
        rule.waitForIdle()
        return rule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .fetchSemanticsNodes()
            .size
    }

    @Test
    fun `a loaded movie shows no spinner`() {
        assertEquals(0, spinners(MovieState(movie = dummyMovie)))
    }

    @Test
    fun `a movie still loading shows the spinner`() {
        assertEquals(1, spinners(MovieState()))
    }
}
