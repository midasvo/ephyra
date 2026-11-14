package dev.jdtech.jellyfin.film.presentation.movie

import dev.jdtech.jellyfin.models.FindroidItem
import java.util.UUID

sealed interface MovieAction {
    data class Play(val startFromBeginning: Boolean = false, val mediaSourceId: String? = null) :
        MovieAction

    data class PlayTrailer(val trailer: String) : MovieAction

    data object MarkAsPlayed : MovieAction

    data object UnmarkAsPlayed : MovieAction

    data object MarkAsFavorite : MovieAction

    data object UnmarkAsFavorite : MovieAction

    data object OnRetryClick : MovieAction

    data object OnBackClick : MovieAction

    data object OnHomeClick : MovieAction

    data class NavigateToPerson(val personId: UUID) : MovieAction

    /** CE (#332): a collection or a "More like this" item. */
    data class NavigateToItem(val item: FindroidItem) : MovieAction

    /** CE (#332): plays a local trailer or special feature in the app's player. */
    data class PlayExtra(val extraId: UUID) : MovieAction

    /** CE (#332): opens the movie on IMDb, TMDB and the like. */
    data class OpenExternalLink(val url: String) : MovieAction
}
