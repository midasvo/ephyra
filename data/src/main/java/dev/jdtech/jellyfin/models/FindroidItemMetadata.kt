package dev.jdtech.jellyfin.models

import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.DayOfWeek as JellyfinDayOfWeek
import org.jellyfin.sdk.model.api.ExternalUrl
import org.jellyfin.sdk.model.api.MediaUrl

/**
 * CE (#332): metadata the server already sends in the item response and the detail pages show.
 * Everything here comes from the user's own server; an empty value means the page hides it.
 */

/** A link to the item on an outside site (IMDb, TMDB, TVDB, Trakt…), named after that site. */
data class FindroidExternalLink(val name: String, val url: String)

/** A trailer hosted elsewhere (usually YouTube). [name] is the server's title for it, if any. */
data class FindroidTrailer(val name: String?, val url: String)

/** All remote trailers that have a URL, in the server's order, without duplicates. */
fun List<MediaUrl>?.toFindroidTrailers(): List<FindroidTrailer> =
    orEmpty()
        .mapNotNull { trailer ->
            trailer.url
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    FindroidTrailer(name = trailer.name?.takeIf { n -> n.isNotBlank() }, url = it)
                }
        }
        .distinctBy { it.url }

/** The first tagline, or null when there is none. */
fun List<String>?.firstTagline(): String? = orEmpty().firstOrNull { it.isNotBlank() }?.trim()

/**
 * The item's outside links. The server builds [externalUrls] from its metadata providers, so they
 * are used as they come. A server that leaves them out still sends the provider ids, so the common
 * sites are built from those instead.
 */
fun externalLinks(
    externalUrls: List<ExternalUrl>?,
    providerIds: Map<String, String?>?,
    type: BaseItemKind?,
): List<FindroidExternalLink> {
    val fromServer =
        externalUrls.orEmpty().mapNotNull { link ->
            val name = link.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = link.url?.takeIf { it.isWebUrl() } ?: return@mapNotNull null
            FindroidExternalLink(name, url)
        }
    if (fromServer.isNotEmpty()) return fromServer.distinctBy { it.url }

    val ids = providerIds.orEmpty().mapKeys { it.key.lowercase() }
    return buildList {
            ids["imdb"]
                ?.takeIf { it.isNotBlank() }
                ?.let { add(FindroidExternalLink("IMDb", "https://www.imdb.com/title/$it")) }
            ids["tmdb"]
                ?.takeIf { it.isNotBlank() }
                ?.let { id ->
                    when (type) {
                        BaseItemKind.MOVIE ->
                            add(
                                FindroidExternalLink(
                                    "TheMovieDb",
                                    "https://www.themoviedb.org/movie/$id",
                                )
                            )
                        BaseItemKind.SERIES ->
                            add(
                                FindroidExternalLink(
                                    "TheMovieDb",
                                    "https://www.themoviedb.org/tv/$id",
                                )
                            )
                        else -> Unit
                    }
                }
            ids["tvdb"]
                ?.takeIf { it.isNotBlank() }
                ?.let { id ->
                    if (type == BaseItemKind.SERIES) {
                        add(
                            FindroidExternalLink(
                                "TheTVDB",
                                "https://thetvdb.com/?tab=series&id=$id",
                            )
                        )
                    }
                }
        }
        .distinctBy { it.url }
}

fun BaseItemDto.toExternalLinks(): List<FindroidExternalLink> =
    externalLinks(externalUrls, providerIds, type)

/** The studio (movie) or network (show) names, in the server's order. */
fun BaseItemDto.studioNames(): List<String> =
    studios.orEmpty().mapNotNull { it.name?.takeIf { name -> name.isNotBlank() } }.distinct()

fun List<JellyfinDayOfWeek>?.toDaysOfWeek(): List<DayOfWeek> =
    orEmpty().mapNotNull { day -> DayOfWeek.entries.firstOrNull { it.name == day.name } }.distinct()

/**
 * The days and time a running show airs, such as "Thursday 21:00", "Mon, Thu 21:00" or, when it
 * airs every day, "[daily] 21:00"; Monday first. Null when the show has ended or the server has no
 * schedule.
 */
fun FindroidShow.airSchedule(locale: Locale, daily: String): String? {
    if (!status.equals("Continuing", ignoreCase = true)) return null
    val days = airDays.sorted()
    val style = if (days.size == 1) TextStyle.FULL else TextStyle.SHORT
    val dayText =
        if (days.size == DayOfWeek.entries.size) daily
        else days.joinToString { it.getDisplayName(style, locale) }
    return listOfNotNull(dayText.ifEmpty { null }, airTime).joinToString(" ").ifEmpty { null }
}

private fun String.isWebUrl(): Boolean = startsWith("https://") || startsWith("http://")
