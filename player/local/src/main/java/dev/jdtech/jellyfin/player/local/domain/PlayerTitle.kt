package dev.jdtech.jellyfin.player.local.domain

import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem

/**
 * CE (#452): the phone player's title. [seriesName] is the small line above [title], null for a
 * movie; [title] is "S1:E6 · Name" for an episode ("S1:E6-7 · Name" for a double episode) and the
 * name for anything else.
 */
data class PlayerTitle(val seriesName: String?, val title: String)

fun playerTitle(item: PlayerItem): PlayerTitle =
    playerTitle(
        name = item.name,
        seriesName = item.seriesName,
        season = item.parentIndexNumber,
        episode = item.indexNumber,
        episodeEnd = item.indexNumberEnd,
    )

fun playerTitle(
    name: String,
    seriesName: String?,
    season: Int?,
    episode: Int?,
    episodeEnd: Int?,
): PlayerTitle {
    val title =
        if (season != null && episode != null) {
            val number =
                if (episodeEnd == null || episodeEnd == episode) "S$season:E$episode"
                else "S$season:E$episode-$episodeEnd"
            "$number · $name"
        } else {
            name
        }
    return PlayerTitle(seriesName?.trim()?.takeIf { it.isNotEmpty() }, title)
}
