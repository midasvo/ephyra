package dev.jdtech.jellyfin.film.presentation

import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * CE (#332): the rows at the bottom of a detail page, each from one extra request that is made
 * after the page is on screen. An empty list is a hidden row.
 */
data class RelatedItems(
    /** Local trailers and special features, playable in the app's own player. */
    val extras: List<FindroidMovie> = emptyList(),
    /** The collections (box sets) the item is part of. */
    val collections: List<FindroidItem> = emptyList(),
    /** "More like this". */
    val similar: List<FindroidItem> = emptyList(),
)

/** Which of the [RelatedItems] to ask the server for. */
data class RelatedItemsRequest(
    val localTrailers: Boolean,
    val specialFeatures: Boolean,
    val collections: Boolean,
    val similar: Boolean = true,
) {
    companion object {
        /**
         * Asks for extras only when the item has them. A missing special feature count (an older
         * server) is no reason to skip them; a zero is.
         */
        fun forItem(localTrailerCount: Int, specialFeatureCount: Int?, collections: Boolean) =
            RelatedItemsRequest(
                localTrailers = localTrailerCount > 0,
                specialFeatures = specialFeatureCount != 0,
                collections = collections,
            )
    }
}

/** Without the rows [request] doesn't ask for. */
fun RelatedItems.keeping(request: RelatedItemsRequest) =
    copy(
        extras = if (request.localTrailers || request.specialFeatures) extras else emptyList(),
        collections = if (request.collections) collections else emptyList(),
        similar = if (request.similar) similar else emptyList(),
    )

/**
 * Loads the [RelatedItems] of [itemId], each kind in its own coroutine so a slow one never holds
 * the others back. Every result is passed to [update] as soon as it is there; a failure is only
 * logged and leaves its row empty, since none of these rows is worth an error on the page.
 */
fun CoroutineScope.launchRelatedItems(
    repository: JellyfinRepository,
    itemId: UUID,
    request: RelatedItemsRequest,
    update: ((RelatedItems) -> RelatedItems) -> Unit,
): Job = launch {
    if (request.localTrailers || request.specialFeatures) {
        launch {
            optional("extras") {
                    repository.getExtras(itemId, request.localTrailers, request.specialFeatures)
                }
                ?.takeIf { it.isNotEmpty() }
                ?.let { extras -> update { it.copy(extras = extras) } }
        }
    }
    if (request.collections) {
        launch {
            optional("collections") { repository.getItemCollections(itemId) }
                ?.takeIf { it.isNotEmpty() }
                ?.let { collections -> update { it.copy(collections = collections) } }
        }
    }
    if (request.similar) {
        launch {
            optional("similar items") { repository.getSimilarItems(itemId) }
                ?.filter { it.id != itemId }
                ?.takeIf { it.isNotEmpty() }
                ?.let { similar -> update { it.copy(similar = similar) } }
        }
    }
}

private suspend fun <T> optional(what: String, block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Failed to load the $what")
        null
    }
