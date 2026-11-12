package dev.jdtech.jellyfin.ce.presentation.film

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.core.presentation.dummy.dummyPersonDetail
import dev.jdtech.jellyfin.core.presentation.dummy.dummyShow
import dev.jdtech.jellyfin.film.presentation.person.PersonState
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Open → Back → same card focused" for the CE person screen (#136, ADR 0003).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1920dp-h1080dp")
class PersonFocusRestoreTest {
    @get:Rule val rule = createComposeRule()

    private val movies: List<dev.jdtech.jellyfin.models.FindroidMovie> =
        (0 until 10).map { dummyMovie.copy(id = UUID(0, it.toLong()), name = "Movie $it") }

    private val shows: List<dev.jdtech.jellyfin.models.FindroidShow> =
        (0 until 10).map { dummyShow.copy(id = UUID(1, it.toLong()), name = "Show $it") }

    private var detailOpen by mutableStateOf(false)
    private var opened: FindroidItem? = null

    private fun setUpPerson(
        starredInShows: List<dev.jdtech.jellyfin.models.FindroidShow> = emptyList()
    ) {
        rule.setContent {
            val stateHolder = rememberSaveableStateHolder()
            FindroidTheme {
                if (detailOpen) {
                    stateHolder.SaveableStateProvider("detail") { Box {} }
                } else {
                    stateHolder.SaveableStateProvider("person") {
                        PersonScreenLayout(
                            state =
                                PersonState(
                                    person = dummyPersonDetail,
                                    starredInMovies = movies,
                                    starredInShows = starredInShows,
                                    isLoading = false,
                                ),
                            onRetryClick = {},
                            onItemClick = { item ->
                                opened = item
                                detailOpen = true
                            },
                        )
                    }
                }
            }
        }
    }

    private fun card(item: FindroidItem): SemanticsMatcher =
        hasAnyAncestor(hasTestTag(personMediaCardTag(item.id))) and isFocusable()

    @Test
    fun firstVisitFocusesTheFirstCard() {
        setUpPerson()
        rule.waitForIdle()
        rule.onNode(card(movies[0])).assertIsFocused()
    }

    @Test
    fun backFocusesTheCardThatWasOpened() {
        setUpPerson()
        val target = movies[3]

        rule.onNode(card(target)).requestFocus()
        rule.onNode(card(target)).assertIsFocused()

        detailOpen = true
        rule.waitForIdle()
        detailOpen = false
        rule.waitForIdle()

        rule.onNode(card(target)).assertIsFocused()
    }

    /**
     * Top of the person's name in the window; below 0 means it is scrolled off (or so far off that
     * the header is no longer composed).
     */
    private fun nameTop(): Float =
        rule
            .onAllNodesWithText(dummyPersonDetail.name)
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.positionInRoot
            ?.y ?: Float.NEGATIVE_INFINITY

    // #407: on a 1080p TV (540 dp) the header and the first row don't both fit.
    @Test
    @Config(qualifiers = "w960dp-h540dp")
    fun firstVisitKeepsTheNameOnScreen() {
        setUpPerson()
        rule.waitForIdle()
        rule.onNode(card(movies[0])).assertIsFocused()
        assertTrue("name scrolled off: ${nameTop()}", nameTop() >= 0f)
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp")
    fun movingAlongTheFirstRowKeepsTheNameOnScreen() {
        setUpPerson()
        rule.waitForIdle()

        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()

        rule.onNode(card(movies[1])).assertIsFocused()
        assertTrue("name scrolled off: ${nameTop()}", nameTop() >= 0f)
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp")
    fun upFromTheSecondSectionBringsTheNameBack() {
        setUpPerson(starredInShows = shows)
        rule.waitForIdle()

        rule.onNode(card(movies[0])).assertIsFocused()

        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNode(card(shows[0])).assertIsFocused()
        assertTrue("second section didn't scroll", nameTop() < 0f)

        rule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        rule.waitForIdle()
        rule.onNode(card(movies[0])).assertIsFocused()
        assertTrue("name scrolled off: ${nameTop()}", nameTop() >= 0f)
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp")
    fun backToTheFirstSectionKeepsTheNameOnScreen() {
        setUpPerson()
        val target = movies[3]
        rule.onNode(card(target)).requestFocus()

        detailOpen = true
        rule.waitForIdle()
        detailOpen = false
        rule.waitForIdle()

        rule.onNode(card(target)).assertIsFocused()
        assertTrue("name scrolled off: ${nameTop()}", nameTop() >= 0f)
    }
}
