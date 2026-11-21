package dev.jdtech.jellyfin.film.presentation.movie

import dev.jdtech.jellyfin.film.presentation.ExternalRatingsState
import dev.jdtech.jellyfin.film.presentation.RelatedItems
import dev.jdtech.jellyfin.models.FindroidItemPerson
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.VideoMetadata

data class MovieState(
    val movie: FindroidMovie? = null,
    val videoMetadata: VideoMetadata? = null,
    val actors: List<FindroidItemPerson> = emptyList(),
    val director: FindroidItemPerson? = null,
    val writers: List<FindroidItemPerson> = emptyList(),
    // CE (#332): loaded after the page is shown, see MovieViewModel.loadRelated.
    val related: RelatedItems = RelatedItems(),
    // CE (#331): MDBList's scores, loaded after the page is shown.
    val externalRatings: ExternalRatingsState = ExternalRatingsState.Hidden,
    val error: Exception? = null,
)
