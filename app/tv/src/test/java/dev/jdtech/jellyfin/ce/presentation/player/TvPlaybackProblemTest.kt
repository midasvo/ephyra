package dev.jdtech.jellyfin.ce.presentation.player

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #463: when the connection dropped the TV player said "Can't play this video / Source error" with
 * only "Exit player", while the player was in fact waiting for the network. It now says the
 * connection was lost, and every problem with something to load again has "Try again".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp-television")
class TvPlaybackProblemTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun `no wait and no error is no problem`() {
        assertNull(playbackProblem(waitingForNetwork = false, hasError = false, nothingLoaded = false))
    }

    @Test
    fun `waiting for the network is not an error, whatever came before`() {
        assertEquals(
            PlaybackProblem.WAITING_FOR_NETWORK,
            playbackProblem(waitingForNetwork = true, hasError = false, nothingLoaded = false),
        )
        assertEquals(
            PlaybackProblem.WAITING_FOR_NETWORK,
            playbackProblem(waitingForNetwork = true, hasError = true, nothingLoaded = false),
        )
    }

    @Test
    fun `an error on a loaded item can be tried again`() {
        assertEquals(
            PlaybackProblem.ERROR,
            playbackProblem(waitingForNetwork = false, hasError = true, nothingLoaded = false),
        )
    }

    @Test
    fun `an error with nothing loaded starts over`() {
        assertEquals(
            PlaybackProblem.START_FAILED,
            playbackProblem(waitingForNetwork = false, hasError = true, nothingLoaded = true),
        )
    }

    /** A [Player] that only knows its state and error, and records what it is asked to do. */
    private fun player(state: Int, error: PlaybackException?, calls: MutableList<String>): Player =
        Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) {
            _,
            method,
            _ ->
            when (method.name) {
                "getPlaybackState" -> state
                "getPlayerError" -> error
                else -> {
                    calls += method.name
                    null
                }
            }
        } as Player

    private val error = PlaybackException("test", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)

    @Test
    fun `a player that stopped on an error loads again before it plays`() {
        val calls = mutableListOf<String>()
        retryPlayback(player(Player.STATE_IDLE, error, calls))
        assertEquals(listOf("prepare", "play"), calls)
    }

    @Test
    fun `a paused player only plays`() {
        val calls = mutableListOf<String>()
        retryPlayback(player(Player.STATE_READY, null, calls))
        assertEquals(listOf("play"), calls)
    }

    @Test
    fun `a player that is still starting is not prepared a second time`() {
        val calls = mutableListOf<String>()
        retryPlayback(player(Player.STATE_IDLE, null, calls))
        assertEquals(listOf("play"), calls)
    }

    private fun show(problem: PlaybackProblem, onRetry: () -> Unit = {}, onExit: () -> Unit = {}) {
        rule.setContent {
            FindroidTheme {
                TvPlaybackProblemDialog(
                    problem = problem,
                    retrying = false,
                    onRetry = onRetry,
                    onExit = onExit,
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `a lost connection says so, with focus on Try again`() {
        var retries = 0
        show(PlaybackProblem.WAITING_FOR_NETWORK, onRetry = { retries++ })

        rule
            .onNodeWithText("Connection lost. Playback continues when the network is back.")
            .assertExists()
        rule.onNodeWithText("Can't play this video").assertDoesNotExist()
        rule.onNodeWithText("Exit player").assertExists()
        rule.onNodeWithText("Try again").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        rule.waitForIdle()
        assertEquals(1, retries)
    }

    @Test
    fun `an error has Try again with focus, and Exit player next to it`() {
        var retries = 0
        var exits = 0
        show(PlaybackProblem.ERROR, onRetry = { retries++ }, onExit = { exits++ })

        rule.onNodeWithText("Can't play this video").assertExists()
        rule.onNodeWithText("Try again").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionRight)
        }
        rule.waitForIdle()
        rule.onNodeWithText("Exit player").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        rule.waitForIdle()
        assertEquals(0, retries)
        assertEquals(1, exits)
    }

    @Test
    fun `a failed start has Try again too`() {
        show(PlaybackProblem.START_FAILED)

        rule.onNodeWithText("Can't play this video").assertExists()
        rule.onNodeWithText("Try again").assertIsFocused()
    }
}
