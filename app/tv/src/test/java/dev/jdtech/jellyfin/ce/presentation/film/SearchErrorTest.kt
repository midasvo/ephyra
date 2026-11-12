package dev.jdtech.jellyfin.ce.presentation.film

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import dev.jdtech.jellyfin.ce.ui.components.TV_LOAD_ERROR_RETRY_TAG
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.film.presentation.search.SearchAction
import dev.jdtech.jellyfin.film.presentation.search.SearchState
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import java.net.SocketTimeoutException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A failed TV search (#417): the reason in plain words and Retry, without taking the field's focus. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp")
class SearchErrorTest {
    @get:Rule val rule = createComposeRule()

    private val retry: SemanticsMatcher =
        (hasTestTag(TV_LOAD_ERROR_RETRY_TAG) or
            hasAnyAncestor(hasTestTag(TV_LOAD_ERROR_RETRY_TAG))) and isFocusable()

    private val unreachable =
        SocketTimeoutException("failed to connect to /10.0.2.2 (port 9) after 6000ms")

    private val movies = (0 until 3).map { dummyMovie.copy(id = UUID(0, it.toLong())) }

    private var state by mutableStateOf(SearchState())
    private val searches = mutableListOf<String>()

    private fun setUp() {
        rule.setContent {
            FindroidTheme {
                SearchScreenLayout(
                    state = state,
                    onAction = { if (it is SearchAction.Search) searches += it.query },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput("jungle")
        rule.waitForIdle()
        state = SearchState(query = "jungle", error = unreachable)
        rule.waitForIdle()
    }

    @Test
    fun showsThePlainReasonAndRetryAndKeepsTheField() {
        setUp()

        rule.onNode(hasText("Can't reach the Jellyfin server", substring = true)).assertIsDisplayed()
        rule.onNode(hasText("10.0.2.2", substring = true)).assertDoesNotExist()
        rule.onNode(hasText("Connection timed out", substring = true)).assertDoesNotExist()
        rule.onNode(retry).assertIsDisplayed()
        // The keyboard may still be open: the error doesn't take focus from the field.
        rule.onNodeWithTag(SEARCH_FIELD_TAG).assertIsFocused()
    }

    @Test
    fun downReachesRetryAndRetrySearchesAgain() {
        setUp()

        rule.onNodeWithTag(SEARCH_FIELD_TAG).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNode(retry).assertIsFocused()

        rule.onNode(retry).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(listOf("jungle", "jungle"), searches)

        // While the retry loads, Retry stays and keeps focus.
        state = state.copy(loading = true)
        rule.waitForIdle()
        rule.onNode(retry).assertIsFocused()

        // The retry finds something: focus goes to the first result.
        state = SearchState(query = "jungle", items = movies)
        rule.waitForIdle()
        rule.onNode(hasAnyAncestor(hasTestTag(searchCardTag(movies[0].id))) and isFocusable())
            .assertIsFocused()
    }

    @Test
    fun aFailedSearchDoesNotShowThePreviousResults() {
        setUp()
        rule.onNodeWithTag(searchCardTag(movies[0].id)).assertDoesNotExist()

        // The view model keeps the previous query's items with the error.
        state = SearchState(query = "jungle", items = movies, error = unreachable)
        rule.waitForIdle()
        rule.onNodeWithTag(searchCardTag(movies[0].id)).assertDoesNotExist()
        rule.onNode(retry).assertIsDisplayed()
    }
}
