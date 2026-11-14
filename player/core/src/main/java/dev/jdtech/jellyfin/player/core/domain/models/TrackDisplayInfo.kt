package dev.jdtech.jellyfin.player.core.domain.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class TrackDisplayInfo(val name: String, val chips: List<String> = emptyList()) : Parcelable {
    val fullTitle: String
        get() =
            if (chips.isEmpty()) {
                name
            } else {
                "$name (${chips.joinToString(" • ")})"
            }
}
