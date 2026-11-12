package dev.jdtech.jellyfin.film.presentation.episode

import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItemPerson
import dev.jdtech.jellyfin.models.VideoMetadata

data class EpisodeState(
    val episode: FindroidEpisode? = null,
    val videoMetadata: VideoMetadata? = null,
    val actors: List<FindroidItemPerson> = emptyList(),
    val error: Exception? = null,
    /**
     * CE (#630): the "Use episode images" setting. The phone page's header shows the episode's own
     * image with it and the show's backdrop without, like the card that led here.
     */
    val useEpisodeImages: Boolean = false,
)
