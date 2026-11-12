package dev.jdtech.jellyfin.presentation.player

import android.app.Dialog
import android.os.Bundle
import android.os.SystemClock
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.jdtech.jellyfin.player.local.R
import dev.jdtech.jellyfin.BasePlayerActivity
import dev.jdtech.jellyfin.player.local.domain.StillWatchingTracker
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import java.lang.IllegalStateException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Are you still watching?" dialog. Uses [MaterialAlertDialogBuilder] for visual consistency
 * with the existing player dialogs (track/speed selection).
 *
 * The dialog renders a ticking countdown to
 * [PlayerViewModel.UiState.stillWatchingEndsAtMs]. The actual pause-and-stop logic
 * is owned by the ViewModel — once its timer elapses it clears `showStillWatching` and
 * the activity dismisses us. The dialog is non-cancelable for the back button /
 * outside-touch so the only paths out are the two explicit buttons or that timeout.
 */
class StillWatchingDialogFragment : DialogFragment() {
    private val viewModel: PlayerViewModel
        get() = (requireActivity() as BasePlayerActivity).viewModel

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        val activity = activity ?: throw IllegalStateException("Activity cannot be null")
        val endsAtMs = viewModel.uiState.value.stillWatchingEndsAtMs
        return MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.still_watching_title)
            .setMessage(message(endsAtMs, SystemClock.elapsedRealtime()))
            .setPositiveButton(R.string.still_watching_continue) { dialog, _ ->
                viewModel.acknowledgeStillWatching()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.still_watching_pause) { dialog, _ ->
                viewModel.dismissStillWatching()
                dialog.dismiss()
            }
            .create()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Tick the countdown on the dialog's message, towards the ViewModel's deadline: its own
        // timer is what actually fires the pause. Started once here, not in onStart, so a return
        // to the app restarts this one loop (#367), which then shows the time really left.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val alertDialog = dialog as? AlertDialog ?: return@repeatOnLifecycle
                while (true) {
                    // Read on every tick: a new prompt while this one is up moves the deadline.
                    val endsAtMs = viewModel.uiState.value.stillWatchingEndsAtMs
                    val nowMs = SystemClock.elapsedRealtime()
                    alertDialog.setMessage(message(endsAtMs, nowMs))
                    // Wake up when the shown number changes.
                    val leftMs = endsAtMs - nowMs
                    delay(if (leftMs % 1_000L > 0L) leftMs % 1_000L else 1_000L)
                }
            }
        }
    }

    private fun message(endsAtMs: Long, nowMs: Long): String =
        getString(
            R.string.still_watching_message,
            // At the deadline the ViewModel closes the dialog; never show "0s" until then.
            StillWatchingTracker.secondsLeft(endsAtMs, nowMs).coerceAtLeast(1),
        )

    override fun onDestroy() {
        super.onDestroy()
        // Fix for hiding the system bars on API < 30
        activity?.window?.let {
            WindowCompat.getInsetsController(it, it.decorView).apply {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}
