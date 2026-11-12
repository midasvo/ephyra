package dev.jdtech.jellyfin

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import dev.jdtech.jellyfin.player.local.PlaybackSessionStore
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel

abstract class BasePlayerActivity : AppCompatActivity() {

    abstract val viewModel: PlayerViewModel

    /** Owns [viewModel], and with it the player, so a session can outlive this activity. */
    abstract val sessionStore: PlaybackSessionStore

    private var wasPip: Boolean = false

    /** The user closed the player, as opposed to the activity being removed from under it. */
    private var closedByUser: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        sessionStore.onUiAttached()
    }

    override fun finish() {
        // Every close from the app (back, the close button, the end of the queue) comes through
        // here. The system removing the task while audio plays in the background does not.
        closedByUser = true
        super.finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isChangingConfigurations || !sessionStore.hasSession) return

        // With background audio on and playing, the session carries on in PlaybackService
        // without this activity. Anything else ends it, as closing the player always has.
        if (!closedByUser && viewModel.isBackgroundAudioEnabled && viewModel.player.isPlaying) {
            sessionStore.onUiDetached(isPlaying = true)
        } else {
            sessionStore.clear()
        }
    }

    override fun onResume() {
        super.onResume()

        if (wasPip) {
            wasPip = false
        } else {
            // CE (#477): only undo the pause onPause made; a player that played on in the
            // background may have been paused since (still-watching prompt, sleep timer).
            playWhenReadyToRestore(pausesOnLeave, viewModel.playWhenReady)?.let {
                viewModel.player.playWhenReady = it
            }
        }
        hideSystemUI()
    }

    /**
     * Leaving the player screen pauses the player, unless it plays on in the background. Fixed for
     * the session ([PlayerViewModel.isBackgroundAudioEnabled] is read once), so onPause and
     * onResume always agree, also across a recreated activity.
     */
    private val pausesOnLeave: Boolean
        get() = !viewModel.isBackgroundAudioEnabled

    override fun onPause() {
        super.onPause()

        if (isInPictureInPictureMode) {
            wasPip = true
        } else {
            viewModel.playWhenReady = viewModel.player.playWhenReady
            if (pausesOnLeave) {
                viewModel.player.playWhenReady = false
            }
            viewModel.updatePlaybackProgress()
        }
    }

    override fun onStop() {
        super.onStop()

        if (wasPip) {
            if (viewModel.isBackgroundAudioEnabled && viewModel.player.isPlaying) {
                // PiP was closed while playing: carry on as background audio. The media
                // notification brings the player back.
                wasPip = false
            } else {
                finish()
            }
        }
    }

    protected fun hideSystemUI() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    protected fun configureInsets(playerControls: View) {
        playerControls.setOnApplyWindowInsetsListener { _, windowInsets ->
            // CE (#455): the visible system bars too, not only the cutout.
            val insets =
                playerControlsInsets(
                    WindowInsetsCompat.toWindowInsetsCompat(windowInsets, playerControls)
                )
            playerControls.updatePadding(
                left = insets.left,
                top = insets.top,
                right = insets.right,
                bottom = insets.bottom,
            )
            return@setOnApplyWindowInsetsListener windowInsets
        }
    }
}
