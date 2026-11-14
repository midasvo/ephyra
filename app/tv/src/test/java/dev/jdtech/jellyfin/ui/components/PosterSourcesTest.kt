package dev.jdtech.jellyfin.ui.components

import android.app.Application
import android.net.Uri
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.core.presentation.dummy.dummySeason
import dev.jdtech.jellyfin.core.presentation.dummy.dummyShow
import dev.jdtech.jellyfin.models.FindroidImages
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which artwork a TV card tries, and in what order (#416). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PosterSourcesTest {
    private val own = Uri.parse("https://demo.example/items/season/Images/Primary?tag=s")
    private val show = Uri.parse("https://demo.example/items/show/Images/Primary?tag=p")
    private val backdrop = Uri.parse("https://demo.example/items/movie/Images/Backdrop/0?tag=b")

    @Test
    fun `a season without a poster uses the show's`() {
        val season = dummySeason.copy(images = FindroidImages(showPrimary = show))
        assertEquals(listOf(show), posterSources(season, Direction.VERTICAL))
    }

    @Test
    fun `a season with a poster tries its own first, then the show's`() {
        val season = dummySeason.copy(images = FindroidImages(primary = own, showPrimary = show))
        assertEquals(listOf(own, show), posterSources(season, Direction.VERTICAL))
    }

    @Test
    fun `a season of a show without a poster has nothing to show`() {
        val season = dummySeason.copy(images = FindroidImages())
        assertEquals(emptyList<Uri>(), posterSources(season, Direction.VERTICAL))
    }

    @Test
    fun `episodes, movies and shows keep their artwork`() {
        val images = FindroidImages(primary = own, backdrop = backdrop, showPrimary = show)
        assertEquals(
            listOf(show),
            posterSources(dummyEpisode.copy(images = images), Direction.VERTICAL),
        )
        assertEquals(
            listOf(own),
            posterSources(dummyEpisode.copy(images = images), Direction.HORIZONTAL),
        )
        assertEquals(
            listOf(backdrop),
            posterSources(dummyMovie.copy(images = images), Direction.HORIZONTAL),
        )
        assertEquals(
            listOf(own),
            posterSources(dummyMovie.copy(images = images), Direction.VERTICAL),
        )
        assertEquals(
            listOf(own),
            posterSources(dummyShow.copy(images = images), Direction.VERTICAL),
        )
    }

    // #608: the Continue Watching and Next Up rows.
    @Test
    fun `a card that prefers the thumb tries title art before the episode's frame`() {
        val thumb = Uri.parse("https://demo.example/items/show/Images/Thumb?tag=t")
        val showBackdrop = Uri.parse("https://demo.example/items/show/Images/Backdrop/0?tag=b")
        val episode =
            dummyEpisode.copy(
                images =
                    FindroidImages(primary = own, showBackdrop = showBackdrop, showThumb = thumb)
            )
        assertEquals(
            listOf(thumb, showBackdrop, own),
            posterSources(episode, Direction.HORIZONTAL, preferThumb = true),
        )
        assertEquals(listOf(own), posterSources(episode, Direction.HORIZONTAL))

        val movie =
            dummyMovie.copy(
                images = FindroidImages(primary = own, backdrop = backdrop, thumb = thumb)
            )
        assertEquals(
            listOf(thumb, backdrop),
            posterSources(movie, Direction.HORIZONTAL, preferThumb = true),
        )
    }

    @Test
    fun `without a thumb the card falls back to the backdrop, then the episode's image`() {
        val showBackdrop = Uri.parse("https://demo.example/items/show/Images/Backdrop/0?tag=b")
        val episode =
            dummyEpisode.copy(images = FindroidImages(primary = own, showBackdrop = showBackdrop))
        assertEquals(
            listOf(showBackdrop, own),
            posterSources(episode, Direction.HORIZONTAL, preferThumb = true),
        )
        assertEquals(
            listOf(own),
            posterSources(
                dummyEpisode.copy(images = FindroidImages(primary = own)),
                Direction.HORIZONTAL,
                preferThumb = true,
            ),
        )
    }

    @Test
    fun `preferring the thumb leaves vertical cards alone`() {
        val images = FindroidImages(primary = own, showPrimary = show, showThumb = backdrop)
        assertEquals(
            listOf(show),
            posterSources(
                dummyEpisode.copy(images = images),
                Direction.VERTICAL,
                preferThumb = true,
            ),
        )
    }
}
