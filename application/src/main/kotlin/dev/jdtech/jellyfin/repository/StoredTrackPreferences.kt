package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.SeriesTrackPreference
import dev.jdtech.jellyfin.models.UserId

/**
 * #966: the tracks each user picked by hand in a series (CE, #580), saved in the database. The
 * store behind the repository's [SeriesTrackPreferences]; each method is one query.
 * `RoomServerStore` in `data` implements it.
 */
interface StoredTrackPreferences {
    /** [userId]'s choices in [seriesId], one per track type. */
    suspend fun trackPreferences(userId: UserId, seriesId: ItemId): List<SeriesTrackPreference>

    /** Saves [preference] for its user, series and track type, replacing the one before. */
    suspend fun saveTrackPreference(preference: SeriesTrackPreference)
}
