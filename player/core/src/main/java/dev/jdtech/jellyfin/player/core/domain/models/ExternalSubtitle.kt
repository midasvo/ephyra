package dev.jdtech.jellyfin.player.core.domain.models

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
class ExternalSubtitle(
    val title: String,
    val language: String,
    val uri: Uri,
    val mimeType: String,
    /**
     * CE (#597): true for a track that is inside the video file and that the server delivers as a
     * file of its own, which it does while it transcodes. It is not named "External" in the track
     * dialog, and keeps the [forced] and [default] marks it has in the file.
     */
    val embedded: Boolean = false,
    val forced: Boolean = false,
    val default: Boolean = false,
) : Parcelable
