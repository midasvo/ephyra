package dev.jdtech.jellyfin.ratings

import java.util.Locale
import kotlin.math.roundToInt

/*
 * CE (#331): scores from outside sites (IMDb, Rotten Tomatoes, ...) that MDBList passes on, shown
 * on the movie and show pages when the user switches them on with a key of their own.
 */

/** The sites the detail pages show, in the order they show them. Others MDBList sends are left. */
enum class RatingSource(
    /** The site's own name; a name, so not translated. */
    val label: String,
    /** The `source` MDBList gives the site's score. */
    val apiName: String,
) {
    IMDB("IMDb", "imdb"),
    ROTTEN_TOMATOES("Rotten Tomatoes", "tomatoes"),
    METACRITIC("Metacritic", "metacritic"),
    LETTERBOXD("Letterboxd", "letterboxd");

    /**
     * The score as the site writes it: IMDb 7.8, Rotten Tomatoes 85%, Metacritic 66, Letterboxd
     * 3.9.
     */
    fun format(value: Float, locale: Locale): String =
        when (this) {
            IMDB,
            LETTERBOXD -> String.format(locale, "%.1f", value)
            ROTTEN_TOMATOES -> "${value.roundToInt()}%"
            METACRITIC -> value.roundToInt().toString()
        }

    companion object {
        fun fromApiName(name: String): RatingSource? = entries.firstOrNull {
            it.apiName.equals(name, ignoreCase = true)
        }
    }
}

data class ExternalRating(val source: RatingSource, val value: Float)

/**
 * The title MDBList is asked about: `/{provider}/{type}/{id}`. Built from the provider ids the
 * server sends with the item, TMDB first, then IMDb, then (shows only) TVDB.
 */
data class RatingsLookup(val provider: String, val type: String, val id: String) {
    /** A file name and a map key: provider, type and id are all plain letters and digits. */
    val key: String
        get() = "$provider-$type-$id"

    companion object {
        private val validId = Regex("^[A-Za-z0-9]{1,32}$")

        /**
         * [providerIds] with lower-case keys, as the models keep them; null without a usable id.
         */
        fun of(providerIds: Map<String, String>, show: Boolean): RatingsLookup? {
            val type = if (show) "show" else "movie"
            val providers = if (show) listOf("tmdb", "imdb", "tvdb") else listOf("tmdb", "imdb")
            return providers.firstNotNullOfOrNull { provider ->
                providerIds[provider]
                    ?.trim()
                    ?.takeIf { validId.matches(it) }
                    ?.let { RatingsLookup(provider, type, it) }
            }
        }
    }
}

/** The external ratings of a title, for the detail pages. */
interface ExternalRatingsSource {
    /** Switched on, with a key. Reads only memory and preferences. */
    fun isOn(): Boolean

    /**
     * Whether MDBList may be asked about [lookup] now: on, with a key it didn't turn down, not
     * waiting out a 429, and no recent failure for this title. Memory and preferences only.
     */
    fun canRequest(lookup: RatingsLookup): Boolean

    /**
     * Whether a page should keep room for ratings of [lookup]: when [canRequest], or when [ratings]
     * would show cached ones without asking. Memory and preferences only, so it is cheap enough for
     * the page's first load.
     */
    fun mightHaveRatings(lookup: RatingsLookup): Boolean

    /**
     * The ratings of [lookup], from the cache or from MDBList. Never throws: off, no key, an error
     * or a title MDBList doesn't know all give an empty list, or the cached ratings when there are
     * any. Makes no request at all while switched off.
     */
    suspend fun ratings(lookup: RatingsLookup): List<ExternalRating>

    /** For tests and previews: never any ratings. */
    object None : ExternalRatingsSource {
        override fun isOn() = false

        override fun canRequest(lookup: RatingsLookup) = false

        override fun mightHaveRatings(lookup: RatingsLookup) = false

        override suspend fun ratings(lookup: RatingsLookup) = emptyList<ExternalRating>()
    }
}
