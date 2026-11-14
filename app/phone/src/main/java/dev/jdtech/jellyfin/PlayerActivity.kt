package dev.jdtech.jellyfin

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.text.format.DateUtils
import android.util.Rational
import android.util.TypedValue
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowMetricsCalculator
import com.google.android.material.color.MaterialColors
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.ephyra.EphyraMotion
import dev.jdtech.jellyfin.databinding.ActivityPlayerBinding
import dev.jdtech.jellyfin.film.presentation.season.SeasonState
import dev.jdtech.jellyfin.film.presentation.season.SeasonViewModel
import dev.jdtech.jellyfin.player.local.PlaybackSessionStore
import dev.jdtech.jellyfin.player.local.R as PlayerR
import dev.jdtech.jellyfin.player.local.domain.SleepTimerOption
import dev.jdtech.jellyfin.player.local.presentation.PlayerEvents
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import dev.jdtech.jellyfin.presentation.player.ChapterListDialogFragment
import dev.jdtech.jellyfin.presentation.player.PlayerEpisodeSheet
import dev.jdtech.jellyfin.presentation.player.PlayerUpNextCard
import dev.jdtech.jellyfin.presentation.player.QualitySelectionDialogFragment
import dev.jdtech.jellyfin.presentation.player.SleepTimerDialogFragment
import dev.jdtech.jellyfin.presentation.player.SpeedSelectionDialogFragment
import dev.jdtech.jellyfin.presentation.player.StillWatchingDialogFragment
import dev.jdtech.jellyfin.presentation.player.SubtitleSyncDialogFragment
import dev.jdtech.jellyfin.presentation.player.TrackSelectionDialogFragment
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.PlayerGestureHelper
import dev.jdtech.jellyfin.utils.PreviewScrubListener
import dev.jdtech.jellyfin.utils.applySubtitleStyle
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

var isControlsLocked: Boolean = false

@AndroidEntryPoint
class PlayerActivity : BasePlayerActivity() {

    @Inject lateinit var appPreferences: AppPreferences

    lateinit var binding: ActivityPlayerBinding
    private var playerGestureHelper: PlayerGestureHelper? = null
    @Inject override lateinit var sessionStore: PlaybackSessionStore
    override val viewModel: PlayerViewModel by lazy {
        ViewModelProvider(
            sessionStore.viewModelStore,
            defaultViewModelProviderFactory,
            defaultViewModelCreationExtras,
        )[PlayerViewModel::class.java]
    }
    private var previewScrubListener: PreviewScrubListener? = null
    private var wasZoom: Boolean = false

    private lateinit var skipSegmentButton: Button

    private var stillWatchingDialog: StillWatchingDialogFragment? = null

    /** CE (R8): locks or unlocks the controls as their buttons do. Set in [onCreate]. */
    private var toggleLock: () -> Unit = {}

    /**
     * CE (#448): the focused control is one the user moved to with Tab or the arrows, not one
     * Android focused by itself. Only then do Space and the arrows go to it.
     */
    private var keyboardFocusChosen = false

    /** CE (R8): wheel movement not yet turned into a seek; touchpads scroll in fractions. */
    private var pendingScroll = 0f

    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    /** CE (R8): the TV remote's seek steps (10 s back, 30 s forward unless changed on a TV). */
    private val keySeekBackMs by lazy {
        appPreferences.getValue(appPreferences.playerTvSeekBackInc).toLongOrNull() ?: 10_000L
    }
    private val keySeekForwardMs by lazy {
        appPreferences.getValue(appPreferences.playerTvSeekForwardInc).toLongOrNull() ?: 30_000L
    }

    private val hideVolumeIndicator = Runnable {
        binding.gestureVolumeLayout.visibility = View.GONE
    }

    /**
     * CE (#363): a fold is half-opened (tabletop or book posture), in either orientation. Set from
     * [WindowInfoTracker]; [playerOrientation] then leaves the window free to turn.
     */
    private var foldHalfOpened = false

    /**
     * CE (#363): the display answered "Keep the player in landscape" with a letterboxed box
     * ([refusesLandscape]). Latched for the life of this player, so it follows the device from then
     * on instead of flipping between the box and full screen.
     */
    private var landscapeRefused = false

    /**
     * CE (R6, T7, #363): asks for the orientation [playerOrientation] picks, from the window the
     * player gets when it fills the screen, the "Keep the player in landscape" setting, the window
     * mode, the fold and the lock.
     *
     * The shorter side and not the current width, because a phone held in landscape is wider than
     * 600 dp and must still lock. The maximum window and not the current one, because a requested
     * orientation only applies to a full-screen window: a tablet that starts the player in
     * split-screen, or returns from picture-in-picture, must not lock itself to landscape. Folding
     * or unfolding changes the maximum window, hence the call from [onConfigurationChanged].
     */
    private fun updatePlayerOrientation() {
        val calculator = WindowMetricsCalculator.getOrCreate()
        val bounds = calculator.computeMaximumWindowMetrics(this).bounds
        val shorterSideDp =
            minOf(bounds.width(), bounds.height()) / resources.displayMetrics.density
        val keepLandscape = appPreferences.getValue(appPreferences.playerKeepLandscape)
        if (keepLandscape && !landscapeRefused) {
            val window = calculator.computeCurrentWindowMetrics(this).bounds
            val letterboxed =
                isLetterboxed(
                    windowWidth = window.width(),
                    windowHeight = window.height(),
                    maxWidth = bounds.width(),
                    maxHeight = bounds.height(),
                    multiWindow = isInMultiWindowMode,
                    pictureInPicture = isInPictureInPictureMode,
                )
            if (refusesLandscape(requestedOrientation, keepLandscape, shorterSideDp, letterboxed)) {
                Timber.d("Keep landscape: letterboxed in $window of $bounds, following the device")
                landscapeRefused = true
            }
        }
        val orientation =
            playerOrientation(
                shorterSideDp = shorterSideDp,
                keepLandscape = keepLandscape,
                locked = isControlsLocked,
                multiWindow = isInMultiWindowMode,
                pictureInPicture = isInPictureInPictureMode,
                foldHalfOpened = foldHalfOpened,
                sdkInt = Build.VERSION.SDK_INT,
                landscapeRefused = landscapeRefused,
            )
        if (requestedOrientation != orientation) {
            requestedOrientation = orientation
        }
    }

    /**
     * CE (R9, #51): the hinge, in window coordinates, while the window is in tabletop posture
     * (half-opened, hinge horizontal); null otherwise. Set from [WindowInfoTracker].
     */
    private var tabletopFold: Rect? = null

    /**
     * CE (R9, #51): in tabletop posture the video (with its subtitles and the title bar) sits above
     * the hinge, and the transport row, the time bar, the volume and brightness panels, the unlock
     * button and the episode sheet (R10) sit below it. Done with margins on views that normally
     * fill the player, so leaving the posture puts every margin back to 0 and the layout is the
     * usual one. Only views move: the player and its surface stay attached, so playback carries on.
     *
     * Ignored in picture-in-picture, and when the hinge doesn't cross the player (a window on one
     * half of the screen).
     */
    private fun applyTabletopLayout() {
        if (!::binding.isInitialized) return
        val playerView = binding.playerView
        val fold = tabletopFold
        var videoBottom = 0
        var controlsTop = 0
        if (fold != null && !isInPictureInPictureMode && playerView.isLaidOut) {
            val location = IntArray(2)
            playerView.getLocationInWindow(location)
            val foldTop = fold.top - location[1]
            val foldBottom = fold.bottom - location[1]
            if (foldTop > 0 && foldBottom < playerView.height) {
                videoBottom = playerView.height - foldTop
                controlsTop = foldBottom
            }
        }
        val above = listOf<View?>(playerView.findViewById(R.id.video_area), playerView.subtitleView)
        for (view in above) view?.setVerticalMargins(top = 0, bottom = videoBottom)
        val below =
            listOf<View?>(
                playerView.findViewById(R.id.lower_controls),
                playerView.findViewById(R.id.locked_player_view),
                binding.gesturePanels,
                binding.episodeSheet,
            )
        for (view in below) view?.setVerticalMargins(top = controlsTop, bottom = 0)
    }

    private fun View.setVerticalMargins(top: Int, bottom: Int) {
        val params = layoutParams as ViewGroup.MarginLayoutParams
        if (params.topMargin == top && params.bottomMargin == bottom) return
        params.topMargin = top
        params.bottomMargin = bottom
        layoutParams = params
    }

    /** CE (R9): whether the window is in R7's `w840dp-h480dp` bucket, the expanded player. */
    private fun isExpandedWindow(): Boolean {
        val config = resources.configuration
        return config.screenWidthDp >= 840 && config.screenHeightDp >= 480
    }

    /** CE (R9): whether the window was in R7's `w840dp-h480dp` bucket at the last resize. */
    private var expandedPlayerDimens: Boolean? = null

    /**
     * CE (R9): applies the `player_*` dimens (R7) to the views that use them. The activity handles
     * size changes itself, so the layout is inflated once; without this a tablet rotated from
     * portrait to landscape during playback would keep the phone sizes. The chapter thumbnails are
     * left out: the chapter list inflates them each time it opens.
     */
    private fun applyPlayerDimens() {
        val expanded = isExpandedWindow()
        if (expanded == expandedPlayerDimens) return
        expandedPlayerDimens = expanded

        fun px(id: Int) = resources.getDimensionPixelSize(id)
        fun View.size(width: Int = layoutParams.width, height: Int = layoutParams.height) {
            if (layoutParams.width == width && layoutParams.height == height) return
            layoutParams = layoutParams.also {
                it.width = width
                it.height = height
            }
        }

        val playerView = binding.playerView
        val spacing = px(R.dimen.player_transport_spacing)
        val transport = playerView.findViewById<ViewGroup>(R.id.transport_controls)
        for (i in 0 until transport.childCount) {
            (transport.getChildAt(i) as? Space)?.size(width = spacing)
        }
        playerView
            .findViewById<View>(R.id.image_preview)
            .size(px(R.dimen.player_image_preview_width), px(R.dimen.player_image_preview_height))
        binding.progressScrubberTrickplay.size(
            px(R.dimen.player_scrubber_trickplay_width),
            px(R.dimen.player_scrubber_trickplay_height),
        )
        val panelWidth = px(R.dimen.player_gesture_panel_width)
        binding.gestureVolumeLayout.size(width = panelWidth)
        binding.gestureBrightnessLayout.size(width = panelWidth)
        val barHeight = px(R.dimen.player_gesture_bar_height)
        binding.gestureVolumeProgressBar.size(height = barHeight)
        binding.gestureBrightnessProgressBar.size(height = barHeight)
        val timeSize = resources.getDimension(R.dimen.player_time_text_size)
        for (id in listOf(R.id.exo_position, R.id.exo_duration)) {
            playerView.findViewById<TextView>(id).setTextSize(TypedValue.COMPLEX_UNIT_PX, timeSize)
        }
        val ripple = px(R.dimen.player_seek_ripple_size)
        binding.imageFfwdAnimationRipple.size(ripple, ripple)
        binding.imageRewindAnimationRipple.size(ripple, ripple)
        binding.imagePlaybackAnimationRipple.size(ripple, ripple)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updatePlayerOrientation()
        if (::binding.isInitialized) {
            applyPlayerDimens()
            updateEpisodesButton()
        }
        // CE (#455): a fold, an unfold or a resize can bring the system bars back; until now they
        // were hidden again only on resume and when the controls hide.
        if (!isInPictureInPictureMode) hideSystemUI()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        // CE (#455): as in onConfigurationChanged. In split-screen some bars stay up anyway, and
        // configureInsets keeps the controls clear of them.
        if (!isInPictureInPictureMode) hideSystemUI()
        // CE (#363): split-screen and freeform windows are left free to turn.
        updatePlayerOrientation()
    }

    /**
     * CE (R10, design §9): the episode sheet. Its season comes from [SeasonViewModel], created the
     * first time the sheet opens; the sheet's content is set up then too.
     */
    private val seasonViewModel: SeasonViewModel by viewModels()
    private val episodeSheetVisible = MutableTransitionState(false)
    private var episodeSheetComposed = false
    private lateinit var episodesButton: ImageButton
    private lateinit var episodesSpace: Space

    /**
     * CE (#272): the "Up next" card may show: not in picture-in-picture, where it would cover the
     * small window, nor with the controls locked, where its buttons would get round the lock.
     */
    private val upNextCardAllowed = mutableStateOf(true)

    private fun updateUpNextCardAllowed() {
        upNextCardAllowed.value = !isInPictureInPictureMode && !isControlsLocked
    }

    /** The controls' hide timeout, paused (0) while the sheet is open. */
    private var controlsHideTimeoutMs = 0

    /** Back, and Escape (which presses Back), closes the sheet before it leaves the player. */
    private val episodeSheetBackCallback =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = closeEpisodeSheet()
        }

    /**
     * The episodes button is offered at expanded width (R7's bucket, read from the window, never
     * the device type) while an episode plays, outside picture-in-picture. Leaving that closes the
     * sheet at once.
     */
    private fun updateEpisodesButton() {
        if (!::episodesButton.isInitialized) return
        val show =
            isExpandedWindow() &&
                !isInPictureInPictureMode &&
                viewModel.uiState.value.currentItem?.seasonId != null
        episodesButton.isVisible = show
        episodesSpace.isVisible = show
        if (!show) closeEpisodeSheet(animate = false)
    }

    private fun openEpisodeSheet() {
        val item = viewModel.uiState.value.currentItem ?: return
        val seasonId = item.seasonId ?: return
        if (isControlsLocked || isInPictureInPictureMode) return
        if (seasonViewModel.shouldReload(seasonId)) {
            seasonViewModel.loadSeason(seasonId = seasonId, seriesId = item.seriesId)
        }
        if (!episodeSheetComposed) {
            episodeSheetComposed = true
            binding.episodeSheet.setContent {
                FindroidTheme(darkTheme = true) {
                    val seasonState by seasonViewModel.state.collectAsStateWithLifecycle()
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    val currentItem = uiState.currentItem
                    PlayerEpisodeSheet(
                        visibleState = episodeSheetVisible,
                        // Until the playing season has loaded, the sheet shows it loading rather
                        // than the season it showed last.
                        state =
                            if (seasonState.season?.id == currentItem?.seasonId) seasonState
                            else SeasonState(error = seasonState.error),
                        seasonNumber = currentItem?.parentIndexNumber,
                        currentEpisodeId = uiState.currentItemId,
                        onEpisodeClick = { episode ->
                            if (episode.id != uiState.currentItemId) {
                                viewModel.playEpisode(episode.id)
                            }
                            closeEpisodeSheet()
                        },
                        onDismiss = { closeEpisodeSheet() },
                        onClosed = { binding.episodeSheet.isVisible = false },
                        onBoundsChanged = { bounds ->
                            if (bounds != episodeSheetBounds) {
                                episodeSheetBounds = bounds
                                updateControlsUnderEpisodeSheet()
                            }
                        },
                        wantsFocus = episodeSheetWantsFocus.value,
                        mayTakeFocus = {
                            episodeSheetTakesFocus(
                                requested = episodeSheetWantsFocus.value,
                                sheetOpen = episodeSheetBackCallback.isEnabled,
                                focusParked =
                                    currentFocus.let { it == null || it === binding.playerView },
                            )
                        },
                        onFocusSettled = { focused ->
                            episodeSheetWantsFocus.value = false
                            // The user opened the sheet from the keyboard, so the row is a
                            // control they chose: the arrows and Space go to it (#448).
                            if (focused) keyboardFocusChosen = true
                        },
                    )
                }
            }
        }
        binding.episodeSheet.isVisible = true
        episodeSheetVisible.targetState = true
        episodeSheetBackCallback.isEnabled = true
        // The controls stay up while the sheet is open.
        binding.playerView.controllerShowTimeoutMs = 0
        binding.playerView.showController()
        updateControlsUnderEpisodeSheet()
    }

    /** Closes the sheet with its fade, or at once when [animate] is false. */
    private fun closeEpisodeSheet(animate: Boolean = true) {
        if (!episodeSheetBackCallback.isEnabled) return
        episodeSheetBackCallback.isEnabled = false
        episodeSheetWantsFocus.value = false
        val hadFocus = binding.episodeSheet.hasFocus()
        episodeSheetVisible.targetState = false
        if (!animate) binding.episodeSheet.isVisible = false
        // Restarts the hide timer when the controls are showing.
        binding.playerView.controllerShowTimeoutMs = controlsHideTimeoutMs
        updateControlsUnderEpisodeSheet(animate)
        // CE (#588): focus goes back where it came from, not to whatever Android finds first.
        if (hadFocus && !episodesButton.requestFocus()) parkFocusOnPlayerView(force = true)
    }

    /**
     * CE (#588): the sheet was opened from the keyboard and has yet to focus the playing episode's
     * row. A Compose state: the sheet acts on it when its rows are there.
     */
    private val episodeSheetWantsFocus = mutableStateOf(false)

    /** CE (#456): the sheet's bounds in the window, from the sheet; null until it is laid out. */
    private var episodeSheetBounds: Rect? = null

    /**
     * CE (#456, #523): the sheet is 92 % opaque (design §9), so the controls under it showed
     * through. While it is open, the top-row and transport buttons it covers or cuts, the skip
     * button and the whole seek row (the time labels and the seek bar, so the bar is never half
     * visible) fade out with the sheet's motion and leave the focus order; closing brings them
     * back, at once when [animate] is false.
     */
    private fun updateControlsUnderEpisodeSheet(animate: Boolean = true) {
        // The sheet also counts for what lies past the window's edge on its side: top-row buttons
        // scrolled out of a narrow window there would come under the sheet with the focus.
        val sheet =
            episodeSheetBounds
                ?.takeIf { episodeSheetBackCallback.isEnabled }
                ?.let { Rect(it) }
                ?.apply {
                    if (right >= binding.root.width) right = Int.MAX_VALUE
                    else if (left <= 0) left = Int.MIN_VALUE
                }
        val playerView = binding.playerView
        val topRow =
            playerView.findViewById<ViewGroup>(R.id.extra_buttons).getChildAt(0) as ViewGroup
        val transport = playerView.findViewById<ViewGroup>(R.id.transport_controls)
        val seekRow = playerView.findViewById<View>(R.id.seek_row)
        val controls =
            (topRow.children + transport.children).filter { it !is Space } +
                seekRow +
                skipSegmentButton
        val bounds = controls.associateWith { it.boundsInWindow() }
        val covered = controlsUnderEpisodeSheet(bounds, sheet)
        for (view in bounds.keys) setUnderEpisodeSheet(view, view in covered, animate)
    }

    /**
     * CE: a top row too narrow for every button starts scrolled to its end, and stays there through
     * a resize or rotation until the user scrolls it (by touch, or by moving focus to a button out
     * of view, which the scroll view brings in). Scrolling also moves buttons under or out from
     * under the open episode sheet (#456).
     */
    private fun configureTopRowScroll() {
        val topRow = binding.playerView.findViewById<HorizontalScrollView>(R.id.extra_buttons)
        val buttons = topRow.getChildAt(0)
        fun maxScroll() =
            topRowMaxScroll(
                contentWidth = buttons.width,
                viewportWidth = topRow.width - topRow.paddingLeft - topRow.paddingRight,
            )
        var followsEnd = true
        topRow.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            followsEnd = topRowFollowsEnd(scrollX, maxScroll())
            if (episodeSheetBackCallback.isEnabled) updateControlsUnderEpisodeSheet()
        }
        val keepAtEnd = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (followsEnd) topRow.scrollTo(maxScroll(), 0)
        }
        topRow.addOnLayoutChangeListener(keepAtEnd)
        buttons.addOnLayoutChangeListener(keepAtEnd)
    }

    private fun View.boundsInWindow(): Rect {
        val location = IntArray(2)
        getLocationInWindow(location)
        return Rect(location[0], location[1], location[0] + width, location[1] + height)
    }

    /** The running fade of each control the episode sheet hides or brings back. */
    private val episodeSheetFades = HashMap<View, ValueAnimator>()

    /** The controls the episode sheet hides now, faded out or on their way. */
    private val hiddenUnderEpisodeSheet = HashSet<View>()

    /**
     * Hides [view] under the open episode sheet, or brings it back. The fade is the view's
     * transition alpha, which multiplies its alpha: media3 sets the alpha of the transport buttons
     * itself (a disabled Next is dimmed), and the visibility other code sets is not touched either.
     * A hidden control takes no focus and is not in the accessibility tree. Nothing happens to a
     * control that is already where it should be, so a fade goes on.
     */
    private fun setUnderEpisodeSheet(view: View, covered: Boolean, animate: Boolean) {
        val changed =
            if (covered) hiddenUnderEpisodeSheet.add(view) else hiddenUnderEpisodeSheet.remove(view)
        if (!changed && animate) return
        val hadFocus = view.hasFocus()
        if (covered && view is ViewGroup) {
            // CE (#523): a drag on the seek bar that is still going on ends without a seek;
            // disabling the bar is media3's way to cancel one.
            val timeBar = view.findViewById<DefaultTimeBar>(R.id.exo_progress)
            if (timeBar.isEnabled) {
                timeBar.isEnabled = false
                timeBar.isEnabled = true
            }
        }
        if (covered && hadFocus) {
            // Opened from the keyboard: the Episodes button has focus. It moves to the player
            // view, rather than to the first control.
            // CE (#588): the sheet takes it from there, for the playing episode's row, once the
            // rows are there. Asking the sheet's view for focus instead found nothing to focus
            // on the first open, and the first row on a later one.
            binding.episodeSheet.post {
                parkFocusOnPlayerView()
                if (episodeSheetBackCallback.isEnabled) {
                    view.setFocusBlocked(true)
                    episodeSheetWantsFocus.value = true
                }
            }
        } else {
            view.setFocusBlocked(covered)
        }
        view.importantForAccessibility =
            when {
                !covered -> View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                view is ViewGroup -> View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                else -> View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }

        val alpha = if (covered) 0f else 1f
        episodeSheetFades.remove(view)?.cancel()
        if (!animate || view.transitionAlpha == alpha) {
            view.transitionAlpha = alpha
            return
        }
        val easing = if (covered) EphyraMotion.EasingEnter else EphyraMotion.EasingExit
        val fade = ValueAnimator.ofFloat(view.transitionAlpha, alpha)
        fade.duration =
            (if (covered) EphyraMotion.DURATION_MEDIUM else EphyraMotion.DURATION_SHORT).toLong()
        fade.interpolator = TimeInterpolator { easing.transform(it) }
        fade.addUpdateListener { view.transitionAlpha = it.animatedValue as Float }
        episodeSheetFades[view] = fade
        fade.start()
    }

    /** Keeps focus off a control, or off everything in a group, or lets it back. */
    private fun View.setFocusBlocked(blocked: Boolean) {
        when (this) {
            is ViewGroup ->
                descendantFocusability =
                    if (blocked) ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    else ViewGroup.FOCUS_BEFORE_DESCENDANTS
            is ImageButton,
            is Button -> isFocusable = !blocked
        }
    }

    private val isPipSupported by lazy {
        // Check if device has PiP feature
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            return@lazy false
        }

        // Check if PiP is enabled for the app
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager?
        appOps?.checkOpNoThrow(
            AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
            Process.myUid(),
            packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    /**
     * CE (#271): the skip button shows in its first seconds in a segment (the ViewModel's
     * [PlayerViewModel.UiState.skipButtonInWindow]) and, for the rest of the segment, whenever the
     * controls are visible. Never in picture-in-picture.
     */
    private fun updateSkipSegmentButton(uiState: PlayerViewModel.UiState) {
        val segment = uiState.currentSegment
        skipSegmentButton.isVisible =
            segment != null &&
                !isInPictureInPictureMode &&
                // CE (#272): the "Up next" card has its own Play now.
                viewModel.upNext.value == null &&
                (uiState.skipButtonInWindow || binding.playerView.isControllerFullyVisible)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Without an item the media notification brought us back to a session that is still
        // playing in the background. If it is gone by now there is nothing to show.
        val itemIdExtra = intent.extras?.getString("itemId")
        if (itemIdExtra == null && !sessionStore.hasSession) {
            finish()
            return
        }
        val itemId = itemIdExtra?.let { UUID.fromString(it) }
        val itemKind = intent.extras?.getString("itemKind")
        val startFromBeginning = intent.extras?.getBoolean("startFromBeginning") ?: false
        val mediaSourceId = intent.extras?.getString("mediaSourceId")
        // CE (#638): a show or season played in random order, from the Shuffle button.
        val shuffle = intent.extras?.getBoolean("shuffle") ?: false

        isControlsLocked = false
        updatePlayerOrientation()

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyPlayerDimens()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        viewModel.attachWindow(window)

        binding.playerView.player = viewModel.player
        binding.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (visibility == View.GONE) {
                    hideSystemUI()
                    // CE (#448): focus left in the hidden controls is no longer the user's.
                    parkFocusOnPlayerView()
                }
                // CE (R8): a mouse pointer hides with the controls and comes back with them.
                binding.playerView.pointerIcon =
                    if (visibility == View.GONE) {
                        PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL)
                    } else {
                        null
                    }
                updateSkipSegmentButton(viewModel.uiState.value)
            }
        )

        // CE (#448): Android also focuses a control by itself: the first control when a key ends
        // touch mode (the back button, with the controls up), play/pause when the controls show,
        // the next control when the focused one hides. Move focus back to the player view, so only
        // a control the user moved to with Tab or the arrows has focus.
        binding.playerView.isFocusableInTouchMode = true
        binding.playerView.viewTreeObserver.addOnTouchModeChangeListener { inTouchMode ->
            keyboardFocusChosen = false
            keepFocusThroughTouchMode(inTouchMode)
        }
        binding.playerView.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            val playerView = binding.playerView
            if (!keyboardFocusChosen && newFocus?.isInside(playerView) == true) {
                playerView.post { if (!keyboardFocusChosen) parkFocusOnPlayerView() }
            }
        }

        val playerControls = binding.playerView.findViewById<View>(R.id.player_controls)
        val lockedControls = binding.playerView.findViewById<View>(R.id.locked_player_view)

        configureInsets(playerControls)
        configureInsets(lockedControls)

        // CE (R9): the tabletop margins depend on the player's height, which changes without a
        // new posture when the window is resized or rotated.
        binding.playerView.addOnLayoutChangeListener { view, _, top, _, bottom, _, oldT, _, oldB ->
            if (bottom - top != oldB - oldT) view.post { applyTabletopLayout() }
        }

        if (appPreferences.getValue(appPreferences.playerGestures)) {
            playerGestureHelper =
                PlayerGestureHelper(
                    appPreferences,
                    this,
                    binding.playerView,
                    getSystemService(AUDIO_SERVICE) as AudioManager,
                )
        }

        binding.playerView.findViewById<View>(R.id.back_button).setOnClickListener {
            finishPlayback()
        }

        // Apply user-configured subtitle appearance to the embedded SubtitleView. The view is
        // created by the inflated exo_player_view.xml and exposed via the @id/exo_subtitles id.
        binding.playerView.subtitleView?.applySubtitleStyle(appPreferences)

        val videoNameTextView = binding.playerView.findViewById<TextView>(R.id.video_name)
        val seriesNameTextView = binding.playerView.findViewById<TextView>(R.id.video_series_name)

        val audioButton = binding.playerView.findViewById<ImageButton>(R.id.btn_audio_track)
        val subtitleButton = binding.playerView.findViewById<ImageButton>(R.id.btn_subtitle)
        val speedButton = binding.playerView.findViewById<ImageButton>(R.id.btn_speed)
        val sleepTimerButton = binding.playerView.findViewById<ImageButton>(R.id.btn_sleep_timer)
        val sleepTimerRemaining =
            binding.playerView.findViewById<TextView>(R.id.sleep_timer_remaining)
        val qualityButton = binding.playerView.findViewById<ImageButton>(R.id.btn_quality)
        val qualitySpace = binding.playerView.findViewById<Space>(R.id.space_quality)
        skipSegmentButton = binding.playerView.findViewById(R.id.btn_skip_segment)
        // CE (#523): the skip button can appear while the episode sheet is open, and never shows
        // under it; the other controls can move under it or away, when the window is resized.
        skipSegmentButton.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val sheet = episodeSheetBounds?.takeIf { episodeSheetBackCallback.isEnabled }
            val covered = sheet != null && isUnderEpisodeSheet(view.boundsInWindow(), sheet)
            setUnderEpisodeSheet(view, covered, animate = false)
        }
        binding.playerView.viewTreeObserver.addOnGlobalLayoutListener {
            if (episodeSheetBackCallback.isEnabled) updateControlsUnderEpisodeSheet()
        }
        configureTopRowScroll()
        val pipButton = binding.playerView.findViewById<ImageButton>(R.id.btn_pip)
        val lockButton = binding.playerView.findViewById<ImageButton>(R.id.btn_lockview)
        val unlockButton = binding.playerView.findViewById<ImageButton>(R.id.btn_unlock)
        val chaptersButton = binding.playerView.findViewById<ImageButton>(R.id.btn_chapters)
        val chaptersSpace = binding.playerView.findViewById<Space>(R.id.space_chapters)
        episodesButton = binding.playerView.findViewById(R.id.btn_episodes)
        episodesSpace = binding.playerView.findViewById(R.id.space_episodes)
        controlsHideTimeoutMs = binding.playerView.controllerShowTimeoutMs
        onBackPressedDispatcher.addCallback(this, episodeSheetBackCallback)

        // CE (#272): the "Up next" card, over the video whether or not the controls show.
        updateUpNextCardAllowed()
        binding.upNextCard.setContent {
            FindroidTheme(darkTheme = true) {
                val upNext by viewModel.upNext.collectAsStateWithLifecycle()
                PlayerUpNextCard(
                    state = upNext,
                    visible = upNextCardAllowed.value,
                    onPlayNow = viewModel::playUpNextNow,
                    onCancel = viewModel::cancelUpNext,
                )
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // CE (R9, #51): tabletop posture on a foldable.
                launch {
                    WindowInfoTracker.getOrCreate(this@PlayerActivity)
                        .windowLayoutInfo(this@PlayerActivity)
                        .collect { layoutInfo ->
                            val folds =
                                layoutInfo.displayFeatures.filterIsInstance<FoldingFeature>()
                            tabletopFold =
                                folds
                                    .firstOrNull {
                                        it.state == FoldingFeature.State.HALF_OPENED &&
                                            it.orientation == FoldingFeature.Orientation.HORIZONTAL
                                    }
                                    ?.bounds
                            applyTabletopLayout()
                            // CE (#363): any orientation, see playerOrientation.
                            foldHalfOpened = folds.any {
                                it.state == FoldingFeature.State.HALF_OPENED
                            }
                            updatePlayerOrientation()
                        }
                }

                // CE (#363): the setting can change while the player is open in the background or
                // in picture-in-picture. observe() sends the current value first, so coming back
                // to the player applies a change made meanwhile.
                launch {
                    appPreferences.observe(appPreferences.playerKeepLandscape).collect {
                        updatePlayerOrientation()
                    }
                }

                launch {
                    viewModel.uiState.collect { uiState ->
                        Timber.d("$uiState")
                        uiState.apply {
                            // Title
                            videoNameTextView.text = currentItemTitle
                            // CE (#452): the series name on its own line above.
                            seriesNameTextView.text = currentItemSeriesName
                            seriesNameTextView.isVisible = currentItemSeriesName != null

                            // CE (#450): waiting for the network, with the controls up so Play
                            // (retry now) is at hand.
                            if (waitingForNetwork && !binding.connectionLost.isVisible) {
                                binding.playerView.showController()
                            }
                            binding.connectionLost.isVisible = waitingForNetwork

                            // Media segment (#271)
                            currentSegment?.let { segment ->
                                skipSegmentButton.text = getString(currentSkipButtonStringRes)
                                skipSegmentButton.setOnClickListener {
                                    viewModel.skipSegment(segment)
                                    skipSegmentButton.isVisible = false
                                }
                            }
                            updateSkipSegmentButton(this)

                            // Trickplay
                            previewScrubListener?.let { it.currentTrickplay = currentTrickplay }

                            playerGestureHelper?.let { it.currentTrickplay = currentTrickplay }

                            // Chapters
                            val playerControlView =
                                findViewById<PlayerControlView>(R.id.exo_controller)
                            val hasChapters = currentChapters.isNotEmpty()
                            val showChapterMarkers =
                                hasChapters &&
                                    appPreferences.getValue(appPreferences.playerChapterMarkers)
                            if (showChapterMarkers) {
                                val numOfChapters = currentChapters.size
                                playerControlView.setExtraAdGroupMarkers(
                                    LongArray(numOfChapters) { index ->
                                        currentChapters[index].startPosition
                                    },
                                    BooleanArray(numOfChapters) { false },
                                )
                            } else {
                                playerControlView.setExtraAdGroupMarkers(null, null)
                            }
                            // Hide the spacer too so the controls bar does not end in dead space.
                            chaptersButton.isVisible = hasChapters
                            chaptersSpace.isVisible = hasChapters

                            // Episodes (R10)
                            updateEpisodesButton()

                            // File Loaded
                            if (fileLoaded) {
                                audioButton.isEnabled = true
                                audioButton.imageAlpha = 255
                                lockButton.isEnabled = true
                                lockButton.imageAlpha = 255
                                subtitleButton.isEnabled = true
                                subtitleButton.imageAlpha = 255
                                speedButton.isEnabled = true
                                speedButton.imageAlpha = 255
                                sleepTimerButton.isEnabled = true
                                sleepTimerButton.imageAlpha = 255
                                qualityButton.isEnabled = true
                                qualityButton.imageAlpha = 255
                                pipButton.isEnabled = true
                                pipButton.imageAlpha = 255
                            }

                            // Still watching prompt — show / hide a dialog driven by the
                            // ViewModel. The ViewModel is the source of truth for the timeout;
                            // the dialog is purely a render of the state.
                            if (showStillWatching && stillWatchingDialog == null) {
                                val dialog = StillWatchingDialogFragment()
                                stillWatchingDialog = dialog
                                dialog.show(supportFragmentManager, "stillwatchingdialog")
                            } else if (!showStillWatching && stillWatchingDialog != null) {
                                stillWatchingDialog?.dismissAllowingStateLoss()
                                stillWatchingDialog = null
                            }
                        }
                    }
                }

                launch {
                    viewModel.eventsChannelFlow.collect { event ->
                        when (event) {
                            is PlayerEvents.NavigateBack -> finishPlayback()
                            is PlayerEvents.IsPlayingChanged -> {
                                if (event.isPlaying) {
                                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                } else {
                                    window.clearFlags(
                                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                                    )
                                }

                                // CE (#480): also with the gesture off, so auto-enter follows a
                                // setting changed while the player was open.
                                updatePipParams(event.isPlaying, playStateChanged = true)
                            }
                            is PlayerEvents.PlayerError -> {
                                // CE (#450): shown as the waiting state, not as an error.
                                if (event.waitingForNetwork) return@collect
                                // Logged in PlayerViewModel. Without this the player sat there
                                // without a word after a stream failed to open (#306).
                                Toast.makeText(
                                        this@PlayerActivity,
                                        PlayerR.string.player_error_title,
                                        Toast.LENGTH_LONG,
                                    )
                                    .show()
                                // Nothing loaded, so nothing will ever play: back to where the
                                // user came from instead of an empty player (#374). With an item
                                // loaded the player stays, and Play retries.
                                if (event.nothingLoaded) finishPlayback()
                            }
                            // CE (#298): paused with the controls up, so a tap on play resumes.
                            is PlayerEvents.SleepTimerFired -> binding.playerView.showController()
                        }
                    }
                }

                // CE (#272): the skip button gives way to the "Up next" card.
                launch {
                    viewModel.upNext.collect { updateSkipSegmentButton(viewModel.uiState.value) }
                }

                // CE (#298): the sleep timer button shows when it runs and how long is left.
                launch {
                    val activeTint =
                        MaterialColors.getColor(
                            sleepTimerButton,
                            androidx.appcompat.R.attr.colorPrimary,
                        )
                    viewModel.sleepTimerState.collect { state ->
                        val active = state.option != SleepTimerOption.Off
                        sleepTimerButton.setImageResource(
                            if (active) CoreR.drawable.ic_moon_star else CoreR.drawable.ic_moon
                        )
                        sleepTimerButton.setColorFilter(if (active) activeTint else Color.WHITE)
                        val remainingMs = state.remainingMs
                        sleepTimerRemaining.isVisible = active && remainingMs != null
                        if (remainingMs != null) {
                            // Rounded up, so it reads 0:00 only as it pauses.
                            sleepTimerRemaining.text =
                                DateUtils.formatElapsedTime((remainingMs + 999) / 1000)
                        }
                    }
                }

                launch {
                    while (true) {
                        viewModel.updatePlaybackProgress()
                        delay(5000L)
                    }
                }

                // Keeps the position used for the stop report on next/previous within a second.
                launch {
                    while (true) {
                        viewModel.refreshPlayingSnapshot()
                        delay(1000L)
                    }
                }

                if (viewModel.shouldPollSegments()) {
                    launch {
                        while (true) {
                            viewModel.updateCurrentSegment()
                            delay(1000L)
                        }
                    }
                }
            }
        }

        audioButton.isEnabled = false
        audioButton.imageAlpha = 75

        lockButton.isEnabled = false
        lockButton.imageAlpha = 75

        subtitleButton.isEnabled = false
        subtitleButton.imageAlpha = 75

        speedButton.isEnabled = false
        speedButton.imageAlpha = 75

        sleepTimerButton.isEnabled = false
        sleepTimerButton.imageAlpha = 75

        // A bitrate cap only makes sense against the server; offline playback
        // always plays the local file as-is.
        if (appPreferences.getValue(appPreferences.offlineMode)) {
            qualityButton.isVisible = false
            qualitySpace.isVisible = false
        } else {
            qualityButton.isEnabled = false
            qualityButton.imageAlpha = 75
        }

        if (isPipSupported) {
            pipButton.isEnabled = false
            pipButton.imageAlpha = 75
        } else {
            val pipSpace = binding.playerView.findViewById<Space>(R.id.space_pip)
            pipButton.isVisible = false
            pipSpace.isVisible = false
        }

        audioButton.setOnClickListener {
            TrackSelectionDialogFragment.newInstance(C.TRACK_TYPE_AUDIO)
                .show(supportFragmentManager, "trackselectiondialog")
        }

        val exoPlayerControlView = findViewById<FrameLayout>(R.id.player_controls)
        val lockedLayout = findViewById<FrameLayout>(R.id.locked_player_view)

        lockButton.setOnClickListener {
            closeEpisodeSheet(animate = false)
            exoPlayerControlView.visibility = View.GONE
            lockedLayout.visibility = View.VISIBLE
            isControlsLocked = true
            // SCREEN_ORIENTATION_LOCKED: the current orientation, whatever it is.
            updatePlayerOrientation()
            updateUpNextCardAllowed()
            parkFocusOnPlayerView()
        }

        unlockButton.setOnClickListener {
            exoPlayerControlView.visibility = View.VISIBLE
            lockedLayout.visibility = View.GONE
            isControlsLocked = false
            updatePlayerOrientation()
            updateUpNextCardAllowed()
            parkFocusOnPlayerView()
        }

        // CE (R8): the L key presses whichever of the two is showing.
        toggleLock = {
            if (isControlsLocked) unlockButton.performClick()
            else if (lockButton.isEnabled) lockButton.performClick()
        }

        subtitleButton.setOnClickListener {
            SubtitleSyncDialogFragment.newInstance()
                .show(supportFragmentManager, "subtitlesyncdialog")
        }

        speedButton.setOnClickListener {
            SpeedSelectionDialogFragment().show(supportFragmentManager, "speedselectiondialog")
        }

        sleepTimerButton.setOnClickListener {
            SleepTimerDialogFragment().show(supportFragmentManager, "sleeptimerdialog")
        }

        qualityButton.setOnClickListener {
            QualitySelectionDialogFragment().show(supportFragmentManager, "qualityselectiondialog")
        }

        chaptersButton.setOnClickListener {
            ChapterListDialogFragment().show(supportFragmentManager, "chapterlistdialog")
        }

        pipButton.setOnClickListener { pictureInPicture() }

        episodesButton.setOnClickListener { openEpisodeSheet() }

        // Set marker color
        val timeBar = binding.playerView.findViewById<DefaultTimeBar>(R.id.exo_progress)
        timeBar.setAdMarkerColor(Color.WHITE)

        if (appPreferences.getValue(appPreferences.playerTrickplay)) {
            val imagePreview = binding.playerView.findViewById<ImageView>(R.id.image_preview)
            previewScrubListener = PreviewScrubListener(imagePreview, timeBar, viewModel.player)

            timeBar.addListener(previewScrubListener!!)
        }

        if (itemId != null) {
            viewModel.initializePlayer(
                itemId = itemId,
                itemKind = itemKind ?: "",
                startFromBeginning = startFromBeginning,
                mediaSourceId = mediaSourceId,
                shuffle = shuffle,
            )
        }
        hideSystemUI()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::binding.isInitialized && onPlayerKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * CE (R8, T15): the player's keyboard map. It runs before the view hierarchy, so media3's
     * `PlayerView` only gets the keys this leaves alone. Returns true when the key was handled.
     *
     * |Key                    |Action                                                            |
     * |-----------------------|------------------------------------------------------------------|
     * |Space, media play/pause|play or pause                                                     |
     * |Left / Right           |seek back / forward by the TV steps                               |
     * |Up / Down              |volume one step up / down, in the swipe's volume indicator        |
     * |M                      |mute or unmute                                                    |
     * |L                      |lock or unlock the controls                                       |
     * |Escape                 |close the episode sheet (R10), else leave the player, as Back does|
     * |Media next / previous  |next / previous item in the queue                                 |
     *
     * Any key but the media keys and Escape also shows the controls, or keeps them up. While the
     * controls are showing and the user moved focus to one of them (with Tab), the arrows and Space
     * go to that control; focus Android gave a control by itself does not count (#448). A row of
     * the open episode sheet is such a control too (#588). With the controls locked only L, Escape
     * and the media keys do anything, and Space presses Unlock or a skip button only when the user
     * moved to it.
     */
    private fun onPlayerKey(event: KeyEvent): Boolean {
        // A picture-in-picture window takes no keys; leave everything to the system.
        if (isInPictureInPictureMode) return false
        val keyCode = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN

        mediaKeyAction(keyCode)?.let { action ->
            if (down && event.repeatCount == 0) {
                viewModel.markUserInteraction()
                action()
            }
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_ESCAPE && event.hasNoModifiers()) {
            // On the up event, as Back is. Back closes the episode sheet first, when it is open.
            if (!down && !event.isCanceled) onBackPressedDispatcher.onBackPressed()
            return true
        }

        if (!down || KeyEvent.isModifierKey(keyCode) || isSystemKey(keyCode)) return false

        val playerView = binding.playerView
        // Judged before the controls are shown: showing them may move focus into them.
        val controlFocused =
            keyGoesToFocusedControl(
                focusChosenByKeyboard = keyboardFocusChosen,
                focusOnControl =
                    focusIsOnControl(
                        insideControls =
                            currentFocus.let {
                                it != null && it !== playerView && it.isInside(playerView)
                            },
                        insideEpisodeSheet = currentFocus?.isInside(binding.episodeSheet) == true,
                        episodeSheetOpen = episodeSheetBackCallback.isEnabled,
                    ),
                controlsShowing = playerView.isControllerFullyVisible,
            )
        viewModel.markUserInteraction()
        // Shows the controls, or restarts their hide timer when they are up.
        if (playerView.useController) playerView.showController()

        // CE (#448): Tab, and the arrows this passes on, move focus: the user chose that control.
        // Any other key keeps focus off the controls unless the user chose one. Showing the
        // controls puts focus on play/pause, which would turn the next arrow into focus navigation
        // and let Space or Enter press a control the user never moved to.
        val arrowPassedOn = !event.hasNoModifiers() || isControlsLocked || controlFocused
        if (keyCode == KeyEvent.KEYCODE_TAB || (isArrowKey(keyCode) && arrowPassedOn)) {
            keyboardFocusChosen = true
        } else if (!controlFocused) {
            parkFocusOnPlayerView()
        }

        if (!event.hasNoModifiers()) return false
        if (keyCode == KeyEvent.KEYCODE_L) {
            if (event.repeatCount == 0) toggleLock()
            return true
        }
        if (isControlsLocked) {
            // With Unlock the only control, Android's Tab search finds nothing from the player
            // view itself (it needs two focusables), so Tab moves to Unlock here.
            if (keyCode == KeyEvent.KEYCODE_TAB && !controlFocused) {
                if (playerView.findViewById<View>(R.id.btn_unlock).requestFocus()) return true
            }
            // Space presses only an Unlock or skip the user moved to; other keys go on as before.
            return keyCode == KeyEvent.KEYCODE_SPACE &&
                spaceKeyAction(locked = true, controlFocused) == SpaceKeyAction.NOTHING
        }

        val handled =
            when (keyCode) {
                KeyEvent.KEYCODE_SPACE -> {
                    val action = spaceKeyAction(locked = false, controlFocused)
                    if (action == SpaceKeyAction.PLAY_PAUSE && event.repeatCount == 0) {
                        togglePlayPause()
                    }
                    action != SpaceKeyAction.FOCUSED_CONTROL
                }
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!controlFocused) {
                        seekByStep(forward = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                    }
                    !controlFocused
                }
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!controlFocused) {
                        changeVolume(
                            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) AudioManager.ADJUST_RAISE
                            else AudioManager.ADJUST_LOWER
                        )
                    }
                    !controlFocused
                }
                KeyEvent.KEYCODE_M -> {
                    if (event.repeatCount == 0) changeVolume(AudioManager.ADJUST_TOGGLE_MUTE)
                    true
                }
                else -> false
            }
        return handled
    }

    /**
     * Moves focus from a control to the player view itself, not its first focusable child, so no
     * control has focus until the user moves to one (#448). Focus outside the player stays put.
     */
    private fun parkFocusOnPlayerView(force: Boolean = false) {
        keyboardFocusChosen = false
        val playerView = binding.playerView
        if (!force && currentFocus?.isInside(playerView) != true) return
        val focusability = playerView.descendantFocusability
        playerView.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        playerView.requestFocus()
        playerView.descendantFocusability = focusability
    }

    /**
     * CE: the first Space (or arrow, Tab, Enter) after a touch did nothing. That key ends touch
     * mode, and when nothing has focus, or a group that hands focus on to its children, Android
     * gives a control focus and drops the key before [onPlayerKey] sees it. So the player view
     * keeps focus in touch mode (Android moves a control's focus to it, as it is focusable in touch
     * mode), and for the key that ends touch mode it keeps focus itself, so the key goes on.
     */
    private fun keepFocusThroughTouchMode(inTouchMode: Boolean) {
        val playerView = binding.playerView
        if (inTouchMode) {
            if (currentFocus == null) playerView.requestFocus()
            return
        }
        if (currentFocus !== playerView) return
        val focusability = playerView.descendantFocusability
        playerView.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        playerView.post { playerView.descendantFocusability = focusability }
    }

    private fun isArrowKey(keyCode: Int): Boolean =
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> true
            else -> false
        }

    /** The media keys, which act without showing the controls. */
    private fun mediaKeyAction(keyCode: Int): (() -> Unit)? {
        val player = viewModel.player
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { ->
                togglePlayPause()
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { ->
                player.play()
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { ->
                player.pause()
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { ->
                seekByStep(forward = true)
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { ->
                seekByStep(forward = false)
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { ->
                if (player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)) player.seekToNext()
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { ->
                if (player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)) {
                    player.seekToPrevious()
                }
            }
            else -> null
        }
    }

    /**
     * Keys the system acts on (volume, Back, Home, power...) are left to it. The media keys are
     * listed here instead of asking `KeyEvent.isMediaSessionKey`: that is API 31, and calling it
     * crashed the player on Android 10 and 11 at the first key press (#350).
     */
    private fun isSystemKey(keyCode: Int): Boolean =
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_RECORD,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MUTE,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_APP_SWITCH -> true
            else -> false
        }

    private fun View.isInside(ancestor: View): Boolean {
        var current = parent
        while (current is View) {
            if (current === ancestor) return true
            current = current.parent
        }
        return false
    }

    private fun togglePlayPause() {
        viewModel.player.playWhenReady = !viewModel.player.playWhenReady
    }

    private fun seekByStep(forward: Boolean) {
        val player = viewModel.player
        val duration = player.duration
        val target = player.currentPosition + if (forward) keySeekForwardMs else -keySeekBackMs
        player.seekTo(
            if (duration != C.TIME_UNSET && duration > 0) target.coerceIn(0L, duration)
            else target.coerceAtLeast(0L)
        )
    }

    /** One step of [AudioManager.adjustStreamVolume], shown in the swipe's volume indicator. */
    @SuppressLint("SetTextI18n")
    private fun changeVolume(direction: Int) {
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        } catch (e: SecurityException) {
            // Do Not Disturb can refuse a mute change.
            Timber.w(e)
            return
        }
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val volume =
            if (audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) 0
            else audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val percent = if (max > 0) volume * 100 / max else 0
        binding.gestureVolumeProgressBar.max = max * 100
        binding.gestureVolumeProgressBar.progress = volume * 100
        binding.gestureVolumeText.text = "$percent%"
        binding.gestureVolumeImage.setImageLevel(percent)
        binding.gestureVolumeLayout.visibility = View.VISIBLE
        binding.gestureVolumeLayout.removeCallbacks(hideVolumeIndicator)
        binding.gestureVolumeLayout.postDelayed(hideVolumeIndicator, 1000)
    }

    /**
     * CE (R8): the mouse in the player. Moving the pointer shows the controls and restarts their
     * hide timer; the wheel seeks one Left/Right step per notch (up or right is forward). Taken at
     * the activity, before the controls' buttons consume the hover events.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (
            ::binding.isInitialized &&
                !isInPictureInPictureMode &&
                event.isFromSource(InputDevice.SOURCE_MOUSE)
        ) {
            val playerView = binding.playerView
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE ->
                    if (playerView.useController) playerView.showController()
                MotionEvent.ACTION_SCROLL ->
                    if (!isControlsLocked) {
                        pendingScroll +=
                            event.getAxisValue(MotionEvent.AXIS_VSCROLL) +
                                event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                        while (pendingScroll >= 1f) {
                            pendingScroll -= 1f
                            seekByStep(forward = true)
                        }
                        while (pendingScroll <= -1f) {
                            pendingScroll += 1f
                            seekByStep(forward = false)
                        }
                        viewModel.markUserInteraction()
                        if (playerView.useController) playerView.showController()
                        return true
                    }
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // CE (#568): the media notification's intent carries no item. It only brings the player
        // back, which goes on with what it plays, as onCreate does for it.
        val itemIdExtra = intent.extras?.getString("itemId") ?: return
        setIntent(intent)

        val itemId = UUID.fromString(itemIdExtra)
        val itemKind = intent.extras!!.getString("itemKind")
        val startFromBeginning = intent.extras!!.getBoolean("startFromBeginning")
        val mediaSourceId = intent.extras!!.getString("mediaSourceId")
        val shuffle = intent.extras!!.getBoolean("shuffle")

        viewModel.initializePlayer(
            itemId = itemId,
            itemKind = itemKind ?: "",
            startFromBeginning = startFromBeginning,
            mediaSourceId = mediaSourceId,
            shuffle = shuffle,
        )
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                appPreferences.getValue(appPreferences.playerPipGesture) &&
                viewModel.player.isPlaying &&
                !isControlsLocked
        ) {
            pictureInPicture()
        }
    }

    private fun finishPlayback() {
        try {
            viewModel.player.clearVideoSurfaceView(
                binding.playerView.videoSurfaceView as SurfaceView
            )
        } catch (e: Exception) {
            Timber.e(e)
        }
        finish()
    }

    private fun pipParams(enableAutoEnter: Boolean): PictureInPictureParams {
        val displayAspectRatio = Rational(binding.playerView.width, binding.playerView.height)

        // videoSize is VideoSize.UNKNOWN (0x0) until the file is loaded, which would yield a NaN
        // Rational and crash setAspectRatio. Bail with IAE so the existing call-site catches
        // (setPictureInPictureParams / enterPictureInPictureMode both wrap in try { } catch (_:
        // IAE)).
        val videoSize = binding.playerView.player?.videoSize
        val aspectRatio =
            if (videoSize != null && videoSize.width > 0 && videoSize.height > 0) {
                Rational(
                    videoSize.width.coerceAtMost((videoSize.height * 2.39f).toInt()),
                    videoSize.height.coerceAtMost((videoSize.width * 2.39f).toInt()),
                )
            } else {
                throw IllegalArgumentException("Video size unknown, cannot build PiP params")
            }

        val sourceRectHint =
            if (displayAspectRatio < aspectRatio) {
                val space =
                    ((binding.playerView.height -
                            (binding.playerView.width.toFloat() / aspectRatio.toFloat())) / 2)
                        .toInt()
                Rect(
                    0,
                    space,
                    binding.playerView.width,
                    (binding.playerView.width.toFloat() / aspectRatio.toFloat()).toInt() + space,
                )
            } else {
                val space =
                    ((binding.playerView.width -
                            (binding.playerView.height.toFloat() * aspectRatio.toFloat())) / 2)
                        .toInt()
                Rect(
                    space,
                    0,
                    (binding.playerView.height.toFloat() * aspectRatio.toFloat()).toInt() + space,
                    binding.playerView.height,
                )
            }

        val builder =
            PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .setSourceRectHint(sourceRectHint)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(enableAutoEnter)
        }

        return builder.build()
    }

    /** CE (#480): auto-enter in the PiP params last given to the system; none given means off. */
    private var pipAutoEnterSet = false

    /**
     * CE (#480): give the system the PiP params for the current setting and play state. It keeps
     * the last params set (also the PiP button's), so this runs on every play/pause change and
     * whenever the player returns, from PiP or from Settings, whatever the gesture setting.
     *
     * Building the params reads the video size, which on mpv waits for its core and can hold up the
     * main thread while mpv sets up its output again (an ANR on a return). So they are only built
     * again when auto-enter changes, or on a play/pause change with the gesture on, as before.
     */
    private fun updatePipParams(
        isPlaying: Boolean = viewModel.player.isPlaying,
        playStateChanged: Boolean = false,
    ) {
        if (!isPipSupported) return
        val gestureEnabled = appPreferences.getValue(appPreferences.playerPipGesture)
        val autoEnter = pipAutoEnterEnabled(gestureEnabled, isPlaying)
        if (!pipParamsNeedUpdate(pipAutoEnterSet, autoEnter, playStateChanged && gestureEnabled)) {
            return
        }
        try {
            setPictureInPictureParams(pipParams(autoEnter))
            pipAutoEnterSet = autoEnter
        } catch (_: IllegalArgumentException) {}
    }

    private fun pictureInPicture() {
        if (!isPipSupported) {
            return
        }

        try {
            val autoEnter =
                pipAutoEnterEnabled(
                    gestureEnabled = appPreferences.getValue(appPreferences.playerPipGesture),
                    isPlaying = viewModel.player.isPlaying,
                )
            enterPictureInPictureMode(pipParams(autoEnter))
            pipAutoEnterSet = autoEnter
        } catch (_: IllegalArgumentException) {}
    }

    override fun onStart() {
        super.onStart()
        viewModel.setVideoEnabled(true)
    }

    override fun onResume() {
        super.onResume()
        // CE (#480): the PiP home gesture may have changed in Settings while the player was in PiP
        // or in the background. After super, which may have given back playWhenReady.
        updatePipParams()
    }

    override fun onStop() {
        super.onStop()
        // Background audio: keep playing without a picture. The player stays attached to
        // PlayerView: detaching it drops mpv's surface callbacks and closes the shutter.
        if (viewModel.isBackgroundAudioEnabled && viewModel.player.isPlaying) {
            viewModel.setVideoEnabled(false)
        }
    }

    override fun onDestroy() {
        viewModel.detachWindow()
        // Cancel both trickplay MainScopes so any in-flight tile fetches don't outlive the
        // activity. onDestroy is the right hook here: previewScrubListener and
        // playerGestureHelper are bound to this activity's view hierarchy (binding,
        // playerView) and the listener is attached to a view-owned TimeBar, so they must
        // survive across onStop/onStart (e.g. PiP, screen off) and are only safe to tear
        // down when the activity itself is being destroyed.
        previewScrubListener?.dispose()
        playerGestureHelper?.dispose()
        super.onDestroy()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        viewModel.isInPictureInPictureMode = isInPictureInPictureMode
        // CE (#363): free in picture-in-picture; the setting applies again on the way back.
        updatePlayerOrientation()
        updateUpNextCardAllowed()
        applyTabletopLayout()
        updateEpisodesButton()
        when (isInPictureInPictureMode) {
            true -> {
                binding.playerView.useController = false
                skipSegmentButton.isVisible = false

                wasZoom = playerGestureHelper?.isZoomEnabled == true
                playerGestureHelper?.updateZoomMode(false)

                // Brightness mode Auto
                window.attributes =
                    window.attributes.apply {
                        screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
            }

            false -> {
                binding.playerView.useController = true
                playerGestureHelper?.updateZoomMode(wasZoom)

                // Override auto brightness
                if (
                    appPreferences.getValue(appPreferences.playerGesturesVB) &&
                        appPreferences.getValue(appPreferences.playerGesturesBrightnessRemember)
                ) {
                    window.attributes =
                        window.attributes.apply {
                            screenBrightness =
                                appPreferences.getValue(appPreferences.playerBrightness)
                        }
                }
            }
        }
    }
}
