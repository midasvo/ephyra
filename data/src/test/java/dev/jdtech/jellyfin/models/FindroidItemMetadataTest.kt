package dev.jdtech.jellyfin.models

import android.app.Application
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.lang.reflect.Proxy
import java.time.DayOfWeek
import java.util.Locale
import java.time.Instant
import java.time.LocalDateTime
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ExternalUrl
import org.jellyfin.sdk.model.api.MediaUrl
import org.jellyfin.sdk.model.api.NameGuidPair
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.jellyfin.sdk.model.api.Video3dFormat
import org.jellyfin.sdk.model.api.DayOfWeek as JellyfinDayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** CE (#332): the metadata the detail pages take from the item response the app already gets. */
// Robolectric for android.net.Uri, which the image mapping uses.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FindroidItemMetadataTest {
    private val id = UUID.fromString("11111111-1111-1111-1111-111111111111")

    private val repository =
        Proxy.newProxyInstance(
            JellyfinRepository::class.java.classLoader,
            arrayOf(JellyfinRepository::class.java),
        ) { _, method, _ ->
            if (method.name == "getBaseUrl") "https://jf.example"
            else throw NotImplementedError(method.name)
        } as JellyfinRepository

    @Test
    fun `movie maps critic rating, tagline, studios, links, trailers, play count and 3D`() {
        val dto =
            BaseItemDto(
                id = id,
                type = BaseItemKind.MOVIE,
                criticRating = 91f,
                taglines = listOf(" ", "In space no one can hear you scream."),
                studios = listOf(NameGuidPair(name = "Brandywine", id = UUID.randomUUID())),
                externalUrls = listOf(ExternalUrl(name = "IMDb", url = "https://www.imdb.com/title/tt0078748")),
                remoteTrailers =
                    listOf(
                        MediaUrl(url = "https://youtu.be/a", name = "Official trailer"),
                        MediaUrl(url = "https://youtu.be/b", name = null),
                        MediaUrl(url = "https://youtu.be/a", name = "Duplicate"),
                    ),
                userData = userData(playCount = 3),
                video3dFormat = Video3dFormat.HALF_SIDE_BY_SIDE,
                localTrailerCount = 1,
                specialFeatureCount = 4,
            )

        val movie = runBlocking { dto.toFindroidMovie(repository, emptyMap()) }

        assertEquals(91f, movie.criticRating)
        assertEquals("In space no one can hear you scream.", movie.tagline)
        assertEquals(listOf("Brandywine"), movie.studios)
        assertEquals(
            listOf(FindroidExternalLink("IMDb", "https://www.imdb.com/title/tt0078748")),
            movie.externalLinks,
        )
        assertEquals(
            listOf(
                FindroidTrailer("Official trailer", "https://youtu.be/a"),
                FindroidTrailer(null, "https://youtu.be/b"),
            ),
            movie.trailers,
        )
        assertEquals("https://youtu.be/a", movie.trailer)
        assertEquals(3, movie.playCount)
        assertTrue(movie.is3D)
        assertEquals(1, movie.localTrailerCount)
        assertEquals(4, movie.specialFeatureCount)
    }

    @Test
    fun `a bare movie leaves every new field empty`() {
        val movie =
            runBlocking { BaseItemDto(id = id, type = BaseItemKind.MOVIE).toFindroidMovie(repository, emptyMap()) }

        assertNull(movie.criticRating)
        assertNull(movie.tagline)
        assertTrue(movie.studios.isEmpty())
        assertTrue(movie.externalLinks.isEmpty())
        assertTrue(movie.trailers.isEmpty())
        assertEquals(0, movie.playCount)
        assertFalse(movie.is3D)
        assertEquals(0, movie.localTrailerCount)
        assertNull(movie.specialFeatureCount)
    }

    @Test
    fun `show maps networks and the air schedule`() {
        val show =
            BaseItemDto(
                    id = id,
                    type = BaseItemKind.SERIES,
                    status = "Continuing",
                    studios = listOf(NameGuidPair(name = "HBO", id = UUID.randomUUID())),
                    airDays = listOf(JellyfinDayOfWeek.THURSDAY),
                    airTime = " 21:00 ",
                    criticRating = 88f,
                )
                .toFindroidShow(repository)

        assertEquals(listOf("HBO"), show.studios)
        assertEquals(listOf(DayOfWeek.THURSDAY), show.airDays)
        assertEquals("21:00", show.airTime)
        assertEquals(88f, show.criticRating)
        assertEquals("Thursday 21:00", show.airSchedule(Locale.ENGLISH, "daily"))
    }

    @Test
    fun `episode maps links and play count`() {
        val episode = runBlocking {
            BaseItemDto(
                    id = id,
                    type = BaseItemKind.EPISODE,
                    seriesId = UUID.randomUUID(),
                    seasonId = UUID.randomUUID(),
                    providerIds = mapOf("Imdb" to "tt0959621"),
                    userData = userData(playCount = 1),
                )
                .toFindroidEpisode(repository, emptyMap())!!
        }

        assertEquals(
            listOf(FindroidExternalLink("IMDb", "https://www.imdb.com/title/tt0959621")),
            episode.externalLinks,
        )
        assertEquals(1, episode.playCount)
    }

    @Test
    fun `links fall back to the provider ids when the server sends no urls`() {
        val links =
            externalLinks(
                externalUrls = null,
                providerIds = mapOf("Imdb" to "tt1", "Tmdb" to "2", "Tvdb" to "3", "Other" to "4"),
                type = BaseItemKind.SERIES,
            )

        assertEquals(
            listOf(
                FindroidExternalLink("IMDb", "https://www.imdb.com/title/tt1"),
                FindroidExternalLink("TheMovieDb", "https://www.themoviedb.org/tv/2"),
                FindroidExternalLink("TheTVDB", "https://thetvdb.com/?tab=series&id=3"),
            ),
            links,
        )
        // A movie has no TVDB page.
        assertEquals(
            listOf("https://www.themoviedb.org/movie/2"),
            externalLinks(null, mapOf("Tmdb" to "2", "Tvdb" to "3"), BaseItemKind.MOVIE).map { it.url },
        )
    }

    @Test
    fun `server links without a name or a web url are dropped`() {
        val links =
            externalLinks(
                externalUrls =
                    listOf(
                        ExternalUrl(name = "Trakt", url = "https://trakt.tv/movies/alien-1979"),
                        ExternalUrl(name = null, url = "https://example.com"),
                        ExternalUrl(name = "Local", url = "file:///etc/passwd"),
                        ExternalUrl(name = "Blank", url = ""),
                    ),
                providerIds = mapOf("Imdb" to "tt1"),
                type = BaseItemKind.MOVIE,
            )

        assertEquals(listOf(FindroidExternalLink("Trakt", "https://trakt.tv/movies/alien-1979")), links)
    }

    @Test
    fun `air schedule only for running shows`() {
        fun show(status: String, days: List<DayOfWeek>, time: String?) =
            BaseItemDto(id = id, type = BaseItemKind.SERIES)
                .toFindroidShow(repository)
                .copy(status = status, airDays = days, airTime = time)

        assertNull(show("Ended", listOf(DayOfWeek.MONDAY), "20:00").airSchedule(Locale.ENGLISH, "daily"))
        assertNull(show("Continuing", emptyList(), null).airSchedule(Locale.ENGLISH, "daily"))
        assertEquals(
            "Mon, Thu 20:00",
            show("Continuing", listOf(DayOfWeek.THURSDAY, DayOfWeek.MONDAY), "20:00")
                .airSchedule(Locale.ENGLISH, "daily"),
        )
        assertEquals("Friday", show("Continuing", listOf(DayOfWeek.FRIDAY), null).airSchedule(Locale.ENGLISH, "daily"))
        assertEquals("9:00 PM", show("Continuing", emptyList(), "9:00 PM").airSchedule(Locale.ENGLISH, "daily"))
        assertEquals(
            "daily 08:00",
            show("Continuing", DayOfWeek.entries.reversed(), "08:00").airSchedule(Locale.ENGLISH, "daily"),
        )
    }

    @Test
    fun `a download keeps the server's last played date in its row`() {
        // CE (#633): marking it played offline then keeps that date, as the server does.
        val default = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        try {
            val played = LocalDateTime.of(2026, 11, 12, 19, 2, 51)
            val movie =
                runBlocking {
                    BaseItemDto(
                            id = id,
                            type = BaseItemKind.MOVIE,
                            userData = userData(playCount = 1).copy(lastPlayedDate = played),
                        )
                        .toFindroidMovie(repository, emptyMap())
                }
            val never =
                runBlocking {
                    BaseItemDto(id = id, type = BaseItemKind.MOVIE).toFindroidMovie(repository, emptyMap())
                }

            assertEquals(
                Instant.parse("2026-11-13T03:02:51Z").toEpochMilli(),
                movie.toFindroidUserDataDto(id).lastPlayedDate,
            )
            assertNull(never.toFindroidUserDataDto(id).lastPlayedDate)
        } finally {
            TimeZone.setDefault(default)
        }
    }

    private fun userData(playCount: Int) =
        UserItemDataDto(
            playbackPositionTicks = 0,
            playCount = playCount,
            isFavorite = false,
            played = playCount > 0,
            key = "",
            itemId = id,
        )
}
