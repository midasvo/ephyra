package dev.jdtech.jellyfin.ratings

import dev.jdtech.jellyfin.models.toProviderIds
import java.io.File
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** CE (#331): which id a title is looked up by, how scores read, and the cache file. */
class RatingsLookupTest {
    @TempDir lateinit var cacheDir: File

    @Test
    fun `provider ids keep their values with lower-case keys`() {
        assertEquals(
            mapOf("imdb" to "tt1", "tmdb" to "2"),
            mapOf("Imdb" to "tt1", "Tmdb" to " 2 ", "Tvdb" to "", "Other" to null).toProviderIds(),
        )
        assertEquals(emptyMap(), null.toProviderIds())
    }

    @Test
    fun `TMDB first, then IMDb`() {
        assertEquals(
            RatingsLookup("tmdb", "movie", "2"),
            RatingsLookup.of(mapOf("imdb" to "tt1", "tmdb" to "2"), show = false),
        )
        assertEquals(
            RatingsLookup("imdb", "show", "tt1"),
            RatingsLookup.of(mapOf("imdb" to "tt1", "tvdb" to "3"), show = true),
        )
    }

    @Test
    fun `TVDB only for shows`() {
        assertEquals(
            RatingsLookup("tvdb", "show", "3"),
            RatingsLookup.of(mapOf("tvdb" to "3"), show = true),
        )
        assertNull(RatingsLookup.of(mapOf("tvdb" to "3"), show = false))
    }

    @Test
    fun `an id that isn't plain letters and digits is not sent`() {
        assertNull(RatingsLookup.of(mapOf("tmdb" to "../user"), show = false))
        assertNull(RatingsLookup.of(mapOf("imdb" to "tt1?x=y"), show = false))
        assertNull(RatingsLookup.of(emptyMap(), show = false))
    }

    @Test
    fun `scores read as each site writes them`() {
        val locale = Locale.US
        assertEquals("7.8", RatingSource.IMDB.format(7.8f, locale))
        assertEquals("85%", RatingSource.ROTTEN_TOMATOES.format(85f, locale))
        assertEquals("66", RatingSource.METACRITIC.format(66f, locale))
        assertEquals("3.9", RatingSource.LETTERBOXD.format(3.92f, locale))
        assertEquals("7,8", RatingSource.IMDB.format(7.8f, Locale.GERMANY))
    }

    @Test
    fun `the cache reads back what it stored, and drops a broken file`() {
        val cache = ExternalRatingsCache(cacheDir)
        val lookup = RatingsLookup("tmdb", "movie", "1")
        val stored = listOf(ExternalRating(RatingSource.IMDB, 7.5f))
        cache.write(lookup, stored, fetchedAt = 42)
        assertEquals(ExternalRatingsCache.Entry(42, stored), cache.read(lookup))
        cache.write(lookup, emptyList(), fetchedAt = 43)
        assertEquals(ExternalRatingsCache.Entry(43, emptyList()), cache.read(lookup))

        File(cacheDir, "tmdb-movie-1.json").writeText("{broken")
        assertNull(cache.read(lookup))
        assertNull(cache.read(RatingsLookup("tmdb", "movie", "2")))
    }
}
