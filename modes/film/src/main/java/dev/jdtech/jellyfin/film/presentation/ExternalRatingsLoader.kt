package dev.jdtech.jellyfin.film.presentation

import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.ratings.ExternalRating
import dev.jdtech.jellyfin.ratings.ExternalRatingsSource
import dev.jdtech.jellyfin.ratings.RatingSource
import dev.jdtech.jellyfin.ratings.RatingsLookup
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

fun FindroidMovie.ratingsLookup(): RatingsLookup? = RatingsLookup.of(providerIds, show = false)

fun FindroidShow.ratingsLookup(): RatingsLookup? = RatingsLookup.of(providerIds, show = true)

/** CE (#331): the external ratings row of a movie or show page. */
sealed interface ExternalRatingsState {
    /** No row: switched off, no id to look up, or nothing came back. */
    data object Hidden : ExternalRatingsState

    /** Room is kept for the row while the ratings load, so the page doesn't move when they come. */
    data object Pending : ExternalRatingsState

    data class Shown(val ratings: List<ExternalRating>) : ExternalRatingsState
}

/**
 * CE (#331): loads the external ratings of one detail page. [onItemLoaded] runs with every load of
 * the item and only decides whether to keep room for the row; [load] runs after the page's first
 * frame and makes the request (in [scope], never holding up anything else), once per item.
 */
class ExternalRatingsLoader(
    private val source: ExternalRatingsSource,
    private val scope: CoroutineScope,
    private val update: (ExternalRatingsState) -> Unit,
) {
    private var loadedFor: UUID? = null
    private var job: Job? = null

    /** The row's state for the page's first frame. No I/O. */
    fun onItemLoaded(
        itemId: UUID,
        lookup: RatingsLookup?,
        current: ExternalRatingsState,
    ): ExternalRatingsState {
        if (lookup == null || !source.isOn()) {
            reset()
            return ExternalRatingsState.Hidden
        }
        if (loadedFor == itemId) return current
        reset()
        return if (source.mightHaveRatings(lookup)) ExternalRatingsState.Pending
        else ExternalRatingsState.Hidden
    }

    /**
     * Fetches the ratings of [itemId], unless that is done already. [rottenTomatoes] is false when
     * the page shows the server's own Rotten Tomatoes score, or the user turned that score off.
     */
    fun load(itemId: UUID, lookup: RatingsLookup?, rottenTomatoes: Boolean) {
        if (lookup == null || loadedFor == itemId || !source.isOn()) return
        loadedFor = itemId
        job?.cancel()
        job = scope.launch {
            val ratings =
                try {
                    source.ratings(lookup)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Failed to load the external ratings")
                    emptyList()
                }
            val shown = ratings.filter {
                rottenTomatoes || it.source != RatingSource.ROTTEN_TOMATOES
            }
            update(
                if (shown.isEmpty()) ExternalRatingsState.Hidden
                else ExternalRatingsState.Shown(shown)
            )
        }
    }

    private fun reset() {
        job?.cancel()
        job = null
        loadedFor = null
    }
}
