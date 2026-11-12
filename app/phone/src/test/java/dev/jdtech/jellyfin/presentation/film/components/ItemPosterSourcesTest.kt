package dev.jdtech.jellyfin.presentation.film.components

import android.app.Application
import android.net.Uri
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.models.FindroidImages
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #608: the cards of Continue Watching and Next Up show the thumb, the wide art with the title on
 * it, of the movie or of the episode's series. Every other card keeps its artwork.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ItemPosterSourcesTest {
    private val still = Uri.parse("https://server/still")
    private val poster = Uri.parse("https://server/poster")
    private val backdrop = Uri.parse("https://server/backdrop")
    private val thumb = Uri.parse("https://server/thumb")
    private val showBackdrop = Uri.parse("https://server/show-backdrop")
    private val showThumb = Uri.parse("https://server/show-thumb")

    private val episode =
        dummyEpisode.copy(
            images =
                FindroidImages(primary = still, showBackdrop = showBackdrop, showThumb = showThumb)
        )
    private val movie =
        dummyMovie.copy(
            images = FindroidImages(primary = poster, backdrop = backdrop, thumb = thumb)
        )

    @Test
    fun `an episode that prefers the thumb tries the series art before its own frame`() {
        assertEquals(
            listOf(showThumb, showBackdrop, still),
            posterSources(episode, Direction.HORIZONTAL, preferThumb = true),
        )
    }

    @Test
    fun `a movie that prefers the thumb tries it before its backdrop`() {
        assertEquals(
            listOf(thumb, backdrop, poster),
            posterSources(movie, Direction.HORIZONTAL, preferThumb = true),
        )
    }

    @Test
    fun `other horizontal cards keep the episode's image first`() {
        assertEquals(listOf(still, showBackdrop), posterSources(episode, Direction.HORIZONTAL))
        assertEquals(listOf(backdrop, poster), posterSources(movie, Direction.HORIZONTAL))
    }

    @Test
    fun `a missing thumb leaves the next source in line`() {
        val noThumb = episode.copy(images = episode.images.copy(showThumb = null))
        assertEquals(
            listOf(showBackdrop, still),
            posterSources(noThumb, Direction.HORIZONTAL, preferThumb = true).filterNotNull(),
        )
    }
}
