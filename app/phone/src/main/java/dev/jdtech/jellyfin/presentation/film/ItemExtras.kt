package dev.jdtech.jellyfin.presentation.film

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.ui.platform.UriHandler
import dev.jdtech.jellyfin.PlayerActivity
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * CE (#332): plays a local trailer or special feature in the app's own player. The player loads an
 * extra the way it loads a movie: one video item, no queue.
 */
internal fun playExtra(context: Context, extraId: UUID) {
    val intent = Intent(context, PlayerActivity::class.java)
    intent.putExtra("itemId", extraId.toString())
    intent.putExtra("itemKind", BaseItemKind.MOVIE.serialName)
    context.startActivity(intent)
}

/** CE (#332): opens an IMDb, TMDB, TVDB… page in the browser. */
internal fun openExternalLink(context: Context, uriHandler: UriHandler, url: String) {
    try {
        uriHandler.openUri(url)
    } catch (e: IllegalArgumentException) {
        Toast.makeText(context, e.localizedMessage, Toast.LENGTH_SHORT).show()
    }
}
