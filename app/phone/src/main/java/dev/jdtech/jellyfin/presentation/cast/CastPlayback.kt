package dev.jdtech.jellyfin.presentation.cast

import androidx.compose.runtime.staticCompositionLocalOf
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * CE (#626): where a Play button sends its item while a Cast receiver is connected. The screens
 * ask this first and open the local player only when it says no.
 */
fun interface CastPlayback {
    /**
     * Plays the item on the connected receiver. Returns `false` when there is none (always, in a
     * build without Cast), and the caller plays the item on the device.
     *
     * CE (#638): [shuffle] plays a series or season in random order, as the local player does.
     */
    fun play(
        itemId: UUID,
        itemKind: BaseItemKind,
        startFromBeginning: Boolean,
        mediaSourceId: String?,
        shuffle: Boolean,
    ): Boolean
}

val LocalCastPlayback = staticCompositionLocalOf { CastPlayback { _, _, _, _, _ -> false } }
