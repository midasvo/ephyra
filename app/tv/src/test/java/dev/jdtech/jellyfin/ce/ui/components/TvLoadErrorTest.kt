package dev.jdtech.jellyfin.ce.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.test.pressKey
import dev.jdtech.jellyfin.ce.presentation.film.FavoritesScreenLayout
import dev.jdtech.jellyfin.ce.presentation.film.favoritesCardTag
import dev.jdtech.jellyfin.core.Constants
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.film.presentation.collection.CollectionState
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import java.net.SocketTimeoutException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The TV load error (#405): plain-words reason, Retry focused, Retry calls back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1920dp-h1080dp")
class TvLoadErrorTest {
    @get:Rule val rule = createComposeRule()

    private val retry: SemanticsMatcher =
        (hasTestTag(TV_LOAD_ERROR_RETRY_TAG) or
            hasAnyAncestor(hasTestTag(TV_LOAD_ERROR_RETRY_TAG))) and isFocusable()

    private val unreachable =
        SocketTimeoutException("failed to connect to /10.0.2.2 (port 9) after 6000ms")

    @Test
    fun showsThePlainReasonAndFocusesRetry() {
        var retries = 0
        rule.setContent {
            FindroidTheme { TvLoadError(error = unreachable, onRetry = { retries++ }) }
        }
        rule.waitForIdle()

        rule.onNode(hasText("Can't reach the Jellyfin server", substring = true)).assertIsDisplayed()
        rule.onNode(hasText("Check your connection and try again.", substring = true))
            .assertIsDisplayed()
        // Never the exception's own text.
        rule.onNode(hasText("10.0.2.2", substring = true)).assertDoesNotExist()
        rule.onNode(retry).assertIsFocused()

        // OK on the remote.
        rule.onNode(retry).performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, retries)
    }

    @Test
    fun focusesRetryAgainWhenTheErrorComesBack() {
        var showError by mutableStateOf(true)
        rule.setContent {
            FindroidTheme {
                if (showError) TvLoadError(error = unreachable, onRetry = {}) else Box {}
            }
        }
        rule.waitForIdle()
        rule.onNode(retry).assertIsFocused()

        // Retry: the page loads again (spinner), and that load fails too.
        showError = false
        rule.waitForIdle()
        showError = true
        rule.waitForIdle()

        rule.onNode(retry).assertIsFocused()
    }

    @Test
    fun favoritesFocusesTheFirstCardAfterASuccessfulRetry() {
        val movies = (0 until 3).map { dummyMovie.copy(id = UUID(0, it.toLong())) }
        var state by
            mutableStateOf(CollectionState(isLoading = false, error = unreachable))
        rule.setContent {
            FindroidTheme {
                FavoritesScreenLayout(state = state, onItemClick = {}, onRetry = {})
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(TV_LOAD_ERROR_TAG).assertIsDisplayed()
        rule.onNode(retry).assertIsFocused()

        state = CollectionState(isLoading = true)
        rule.waitForIdle()
        state =
            CollectionState(
                isLoading = false,
                sections =
                    listOf(
                        CollectionSection(
                            id = Constants.FAVORITE_TYPE_MOVIES,
                            name = UiText.StringResource(CoreR.string.movies_label),
                            items = movies,
                        )
                    ),
            )
        rule.waitForIdle()

        rule.onNode(hasAnyAncestor(hasTestTag(favoritesCardTag(movies[0].id))) and isFocusable())
            .assertIsFocused()
    }
}
