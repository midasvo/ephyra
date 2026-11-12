package dev.jdtech.jellyfin.presentation.player

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import dev.jdtech.jellyfin.BasePlayerActivity
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme

class SubtitleSyncDialogFragment : DialogFragment() {
    private val viewModel by lazy { (requireActivity() as BasePlayerActivity).viewModel }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                FindroidTheme(darkTheme = true) {
                    val currentItemId = viewModel.player.currentMediaItem?.mediaId
                    key(currentItemId) {
                        val choices = remember { viewModel.trackChoices(C.TRACK_TYPE_TEXT, context) }
                        var selectedTrackIndex by remember { mutableIntStateOf(choices.selected) }
                        var delayMs by remember { mutableIntStateOf(viewModel.subtitleDelayMs) }

                        val cues by viewModel.activeCues.collectAsStateWithLifecycle()
                        val isLoadingCues by viewModel.isLoadingCues.collectAsStateWithLifecycle()

                        LaunchedEffect(Unit) {
                            if (selectedTrackIndex >= 0 && cues.isEmpty()) {
                                viewModel.loadCuesForActiveTrack(selectedTrackIndex)
                            }
                        }

                        val tracks =
                            remember(choices, selectedTrackIndex) {
                                choices.names.mapIndexed { index, name ->
                                    SubtitleTrackItem(
                                        index = index,
                                        name = name,
                                        isSelected = index == selectedTrackIndex,
                                    )
                                }
                            }

                        PlayerSubtitleSyncSheet(
                            tracks = tracks,
                            selectedTrackIndex = selectedTrackIndex,
                            subtitleDelayMs = delayMs,
                            getCurrentPositionMs = { viewModel.player.currentPosition.coerceAtLeast(0L) },
                            availableCues = cues,
                            isLoadingCues = isLoadingCues,
                            onTrackSelected = { newIndex ->
                                selectedTrackIndex = newIndex
                                viewModel.switchToTrack(C.TRACK_TYPE_TEXT, newIndex)
                            },
                            onDelayChanged = { newDelay ->
                                delayMs = newDelay
                                viewModel.setSubtitleDelay(newDelay)
                            },
                            onDismiss = { dismiss() },
                        )
                    }
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.let { window ->
            window.requestFeature(Window.FEATURE_NO_TITLE)
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        activity?.window?.let {
            WindowCompat.getInsetsController(it, it.decorView).apply {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    companion object {
        fun newInstance(): SubtitleSyncDialogFragment = SubtitleSyncDialogFragment()
    }
}
