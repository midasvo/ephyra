package dev.jdtech.jellyfin.ce.ui.focus

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec

/**
 * Never scrolls to show a focused child: for a page list that scrolls itself to the focused row
 * (the person page, #407; the movie and show pages, #514). Provide the default spec again for the
 * rows inside it, so they still follow focus along the row.
 */
@OptIn(ExperimentalFoundationApi::class)
val NoBringIntoView =
    object : BringIntoViewSpec {
        override fun calculateScrollDistance(
            offset: Float,
            size: Float,
            containerSize: Float,
        ): Float = 0f
    }
