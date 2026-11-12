package dev.jdtech.jellyfin.ce.presentation.film

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import dev.jdtech.jellyfin.ce.ui.TvSafeArea
import dev.jdtech.jellyfin.ce.ui.components.TV_TEXT_DIALOG_TAG
import dev.jdtech.jellyfin.ce.ui.components.TV_TEXT_DIALOG_TEXT_TAG
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.core.presentation.dummy.dummyPersonDetail
import dev.jdtech.jellyfin.film.presentation.person.PersonState
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import java.util.UUID
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
 * The TV person page on a 1080p TV (540 dp): the whole first row fits under the header, and a cut
 * biography opens in full (#508).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp-television")
class PersonHeaderTest {
    @get:Rule val rule = createComposeRule()

    private val movies: List<FindroidMovie> =
        (0 until 6).map { dummyMovie.copy(id = UUID(0, it.toLong()), name = "Movie $it") }

    private val longBiography = "A biography that goes on for many lines. ".repeat(60)

    private fun setPerson(biography: String = longBiography, fontScale: Float = 1f) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = fontScale)
            ) {
                FindroidTheme {
                    PersonScreenLayout(
                        state =
                            PersonState(
                                person = dummyPersonDetail.copy(overview = biography),
                                starredInMovies = movies,
                                isLoading = false,
                            ),
                        onRetryClick = {},
                        onItemClick = {},
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun card(index: Int): SemanticsMatcher =
        hasAnyAncestor(hasTestTag(personMediaCardTag(movies[index].id))) and isFocusable()

    private val biography = hasTestTag(PERSON_BIOGRAPHY_TAG)

    private fun press(key: Key) {
        rule.onNode(isFocused()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun assertFirstRowFits() {
        rule.onNode(card(0)).assertIsFocused()
        // The card's node holds its poster, title and year.
        val bottom = rule.onNodeWithTag(personMediaCardTag(movies[0].id)).getUnclippedBoundsInRoot()
        assertTrue(
            "first row ends at ${bottom.bottom}, below the safe area",
            bottom.bottom <= 540.dp - TvSafeArea.vertical,
        )
        val name = rule.onNodeWithTag(PERSON_BIOGRAPHY_TAG).getUnclippedBoundsInRoot()
        assertTrue("header scrolled off: $name", name.top >= 0.dp)
    }

    @Test
    fun `the first row fits under the header`() {
        setPerson()
        assertFirstRowFits()
    }

    @Test
    fun `the first row fits under the header at font scale 1_3`() {
        setPerson(fontScale = 1.3f)
        assertFirstRowFits()
    }

    @Test
    fun `focus starts on the first card`() {
        setPerson()
        rule.onNode(card(0)).assertIsFocused()
    }

    @Test
    fun `a cut biography takes focus and opens in full, and Back returns to it`() {
        setPerson()

        press(Key.DirectionUp)
        rule.onNode(biography).assertIsFocused()

        press(Key.DirectionCenter)
        rule.onNodeWithTag(TV_TEXT_DIALOG_TEXT_TAG).assertIsFocused()
        val scrollBefore = scrollPosition()
        pressInDialog(Key.DirectionDown)
        assertTrue("Down didn't scroll the text", scrollPosition() > scrollBefore)
        pressInDialog(Key.DirectionUp)
        assertEquals(scrollBefore, scrollPosition(), 0.5f)

        // Back on the remote, as the dialog's window gets it.
        rule.runOnUiThread { ShadowDialog.getLatestDialog().onBackPressed() }
        rule.waitForIdle()

        rule.onNodeWithTag(TV_TEXT_DIALOG_TEXT_TAG).assertDoesNotExist()
        rule.onNode(biography).assertIsFocused()
    }

    /**
     * The dialog is centred, so a height within the screen less the safe area above and below
     * keeps all of it inside the safe area.
     */
    private fun assertDialogFitsSafeArea(fontScale: Float) {
        setPerson(biography = longBiography.repeat(3), fontScale = fontScale)
        press(Key.DirectionUp)
        press(Key.DirectionCenter)

        val dialog = rule.onNodeWithTag(TV_TEXT_DIALOG_TAG).getUnclippedBoundsInRoot()
        assertTrue(
            "dialog is ${dialog.height} tall at font scale $fontScale",
            dialog.height <= 540.dp - TvSafeArea.vertical * 2,
        )
        // The text still scrolls to the end inside it.
        rule.onNodeWithTag(TV_TEXT_DIALOG_TEXT_TAG).assertIsFocused()
        val scrollBefore = scrollPosition()
        pressInDialog(Key.DirectionDown)
        assertTrue("Down didn't scroll the text", scrollPosition() > scrollBefore)
    }

    @Test
    fun `the dialog fits inside the safe area`() = assertDialogFitsSafeArea(fontScale = 1f)

    @Test
    fun `the dialog fits inside the safe area at font scale 1_3`() =
        assertDialogFitsSafeArea(fontScale = 1.3f)

    @Test
    fun `a biography that fits doesn't take focus`() {
        setPerson(biography = "Short.")

        rule.onNode(biography and isFocusable()).assertDoesNotExist()
        press(Key.DirectionUp)
        rule.onNode(card(0)).assertIsFocused()
    }

    // The page keeps its own focused node under the dialog, so press on the dialog's text.
    private fun pressInDialog(key: Key) {
        rule.onNodeWithTag(TV_TEXT_DIALOG_TEXT_TAG).performKeyInput { pressKey(key) }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    private fun scrollPosition(): Float =
        rule
            .onNodeWithTag(TV_TEXT_DIALOG_TEXT_TAG)
            .fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
            .value()
}
