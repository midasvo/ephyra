package dev.jdtech.jellyfin.film.presentation

import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.settings.domain.AppPreferences

/**
 * CE (#332): which parts of the server's metadata the detail pages show, each a switch under
 * Settings > Interface > Detail pages. A part that is off is cleared from the item before it
 * reaches the page, which already hides empty values, and its row is never requested.
 */
data class DetailSections(
    val criticRating: Boolean = true,
    val tagline: Boolean = true,
    val studios: Boolean = true,
    val airSchedule: Boolean = true,
    val watchHistory: Boolean = true,
    val externalLinks: Boolean = true,
    val extras: Boolean = true,
    val collections: Boolean = true,
    val similar: Boolean = true,
)

fun AppPreferences.detailSections() =
    DetailSections(
        criticRating = getValue(detailCriticRating),
        tagline = getValue(detailTagline),
        studios = getValue(detailStudios),
        airSchedule = getValue(detailAirSchedule),
        watchHistory = getValue(detailWatchHistory),
        externalLinks = getValue(detailExternalLinks),
        extras = getValue(detailExtras),
        collections = getValue(detailCollections),
        similar = getValue(detailSimilar),
    )

fun FindroidMovie.shownIn(sections: DetailSections) =
    copy(
        criticRating = criticRating.takeIf { sections.criticRating },
        tagline = tagline.takeIf { sections.tagline },
        studios = if (sections.studios) studios else emptyList(),
        playCount = if (sections.watchHistory) playCount else 0,
        externalLinks = if (sections.externalLinks) externalLinks else emptyList(),
    )

fun FindroidShow.shownIn(sections: DetailSections) =
    copy(
        criticRating = criticRating.takeIf { sections.criticRating },
        tagline = tagline.takeIf { sections.tagline },
        studios = if (sections.studios) studios else emptyList(),
        airDays = if (sections.airSchedule) airDays else emptyList(),
        airTime = airTime.takeIf { sections.airSchedule },
        externalLinks = if (sections.externalLinks) externalLinks else emptyList(),
    )

fun FindroidEpisode.shownIn(sections: DetailSections) =
    copy(
        playCount = if (sections.watchHistory) playCount else 0,
        externalLinks = if (sections.externalLinks) externalLinks else emptyList(),
    )

fun RelatedItemsRequest.shownIn(sections: DetailSections) =
    copy(
        localTrailers = localTrailers && sections.extras,
        specialFeatures = specialFeatures && sections.extras,
        collections = collections && sections.collections,
        similar = similar && sections.similar,
    )
