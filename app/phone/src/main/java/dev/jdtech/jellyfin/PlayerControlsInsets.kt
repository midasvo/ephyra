package dev.jdtech.jellyfin

import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat

/**
 * CE (#455): the padding that keeps the player's controls clear of the display cutout and of every
 * system bar the window reports as visible. In split-screen the status bar and the taskbar stay up
 * whatever the player asks; with the bars hidden their insets are 0 and only the cutout counts.
 */
internal fun playerControlsInsets(windowInsets: WindowInsetsCompat): Insets =
    windowInsets.getInsets(
        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
    )
