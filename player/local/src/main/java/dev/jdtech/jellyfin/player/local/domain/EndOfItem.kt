package dev.jdtech.jellyfin.player.local.domain

import androidx.media3.common.Player

/**
 * CE (#477): whether an onPlayWhenReadyChanged report is a new end of the current item.
 *
 * Only when playWhenReady really went from true to false there. ExoPlayer also reports the end of
 * an item again while the player is already paused at it, because the reason changed: the
 * still-watching prompt pauses it (reason user request) and re-enabling the video track on return
 * from the background pauses it at the end once more (reason end of media item). Taken as a new
 * end, that prompted a second time with a new deadline.
 */
fun isNewEndOfItem(
    wasPlayWhenReady: Boolean,
    playWhenReady: Boolean,
    reason: Int,
    state: Int,
): Boolean =
    wasPlayWhenReady &&
        !playWhenReady &&
        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM &&
        state == Player.STATE_READY
