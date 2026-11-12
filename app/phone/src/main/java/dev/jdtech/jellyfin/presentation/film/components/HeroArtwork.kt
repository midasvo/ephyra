package dev.jdtech.jellyfin.presentation.film.components

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.jdtech.jellyfin.core.presentation.ephyra.backdropCrossfadeMillis
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidSeason

/**
 * CE (#433): the artwork behind an item's header and its Suggestions card. Without a backdrop the
 * item's poster stands in, blurred so it reads as a backdrop and not as a stretched poster. The
 * blur needs Android 12, so below that a poster never stands in and the header shrinks instead.
 */
object HeroArtwork {
    /** Blur of a poster standing in for a backdrop. */
    val StandInBlur: Dp = 24.dp

    /** Whether this device can blur a stand-in. */
    val canBlur: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
}

/** Where the artwork is shown: each place has its own preferred image. */
enum class ArtworkPlacement {
    /** The header of the single-column item page, behind the title. */
    Header,

    /** The wide hero (4-design.md §6), beside the poster or an episode's still. */
    Hero,

    /** A Suggestions card on Home. */
    Carousel,
}

/** One image the artwork can show; a [standIn] is a poster, blurred into a backdrop. */
@Immutable
data class ArtworkSource(val uri: Uri, val standIn: Boolean = false)

/**
 * The images to try for [item] at [placement], best first: the backdrop, a season's show
 * backdrop, then the item's own poster as a stand-in when [canBlur]. An episode uses its still
 * and its show's backdrop, which are wide images of their own.
 */
fun artworkSources(
    item: FindroidItem,
    placement: ArtworkPlacement,
    canBlur: Boolean = HeroArtwork.canBlur,
): List<ArtworkSource> {
    val images = item.images
    val backdrops =
        when (item) {
            is FindroidEpisode ->
                when (placement) {
                    // The hero shows the still beside the text, so the show's backdrop goes behind.
                    ArtworkPlacement.Hero -> listOf(images.showBackdrop, images.primary)
                    else -> listOf(images.primary, images.showBackdrop)
                }
            is FindroidSeason -> listOf(images.backdrop, images.showBackdrop)
            else -> listOf(images.backdrop)
        }
    val standIns =
        when {
            !canBlur || item is FindroidEpisode -> emptyList()
            item is FindroidSeason -> listOf(images.primary, images.showPrimary)
            else -> listOf(images.primary)
        }
    val seen = mutableSetOf<Uri>()
    return backdrops.filterNotNull().filter(seen::add).map { ArtworkSource(it) } +
        standIns.filterNotNull().filter(seen::add).map { ArtworkSource(it, standIn = true) }
}

/**
 * Which of an item's [sources] the artwork shows. It moves on to the next when one fails to load
 * (downloaded images always have a path, whether or not the file exists), and has no artwork once
 * none is left: then the header shrinks and the status bar keeps the theme's icons (#437).
 */
@Stable
class ArtworkState internal constructor(internal val sources: List<ArtworkSource>) {
    private var index by mutableIntStateOf(0)

    /** The image shown now, or null when there is none to show. */
    val current: ArtworkSource?
        get() = sources.getOrNull(index)

    /** True while there is an image to show, also while it is still loading. */
    val hasArtwork: Boolean
        get() = current != null

    internal fun onError() {
        if (index < sources.size) index++
    }
}

/** The artwork of [item] at [placement]; none while there is no item (loading, an error). */
@Composable
fun rememberArtworkState(item: FindroidItem?, placement: ArtworkPlacement): ArtworkState {
    val context = LocalContext.current
    return remember(item?.id, item?.images, placement) {
        ArtworkState(
            item?.let { artworkSources(it, placement) }.orEmpty().map {
                it.copy(uri = it.uri.resolveIn(context))
            }
        )
    }
}

/**
 * The image of [state], cropped to fill [modifier]; a stand-in is blurred. Draws nothing when
 * there is no image: the caller shrinks or drops the space.
 */
@Composable
fun ArtworkBackdrop(
    state: ArtworkState,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    alignment: Alignment = Alignment.Center,
) {
    val source = state.current ?: return
    val context = LocalContext.current
    AsyncImage(
        model =
            ImageRequest.Builder(context)
                .data(source.uri)
                .crossfade(backdropCrossfadeMillis())
                .build(),
        contentDescription = contentDescription,
        modifier =
            if (source.standIn) {
                modifier.blur(HeroArtwork.StandInBlur, BlurredEdgeTreatment.Rectangle)
            } else modifier,
        placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainer),
        onError = { state.onError() },
        contentScale = ContentScale.Crop,
        alignment = alignment,
    )
}

/** Downloaded images are stored as paths relative to the files directory. */
private fun Uri.resolveIn(context: Context): Uri =
    if (scheme == null) {
        Uri.Builder().appendEncodedPath("${context.filesDir}").appendEncodedPath(path).build()
    } else this
