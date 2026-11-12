package dev.jdtech.jellyfin.film.presentation.home

import dev.jdtech.jellyfin.database.ServerDatabaseDao

/**
 * CE: whether this server has no downloaded movie or show. Offline, Home's rows are the downloads,
 * so an empty Home then says nothing is downloaded instead of the general empty text.
 */
suspend fun nothingDownloaded(database: ServerDatabaseDao, serverId: String): Boolean =
    database.countMoviesByServerId(serverId) == 0 && database.countShowsByServerId(serverId) == 0
