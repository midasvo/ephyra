package dev.jdtech.jellyfin.presentation.film.components

import android.app.Application
import android.net.Uri
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.core.presentation.dummy.dummySeason
import dev.jdtech.jellyfin.models.FindroidImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #433: an item without a backdrop falls back to its show's backdrop or its own poster, blurred,
 * and below Android 12 (no blur) or with nothing at all its header shrinks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HeroArtworkTest {
    private val backdrop = Uri.parse("https://server/backdrop")
    private val poster = Uri.parse("https://server/poster")
    private val showBackdrop = Uri.parse("https://server/show-backdrop")
    private val showPoster = Uri.parse("https://server/show-poster")
    private val still = Uri.parse("https://server/still")

    @Test
    fun `a movie with a backdrop shows it, then its poster`() {
        val movie = dummyMovie.copy(images = FindroidImages(primary = poster, backdrop = backdrop))

        assertEquals(
            listOf(ArtworkSource(backdrop), ArtworkSource(poster, standIn = true)),
            artworkSources(movie, ArtworkPlacement.Header, canBlur = true),
        )
    }

    @Test
    fun `a movie without a backdrop shows its poster blurred`() {
        val movie = dummyMovie.copy(images = FindroidImages(primary = poster))

        assertEquals(
            listOf(ArtworkSource(poster, standIn = true)),
            artworkSources(movie, ArtworkPlacement.Hero, canBlur = true),
        )
    }

    @Test
    fun `without blur a poster never stands in`() {
        val movie = dummyMovie.copy(images = FindroidImages(primary = poster))

        assertEquals(
            emptyList<ArtworkSource>(),
            artworkSources(movie, ArtworkPlacement.Carousel, canBlur = false),
        )
    }

    @Test
    fun `a season tries its own backdrop, the show's, its poster, then the show's poster`() {
        val season =
            dummySeason.copy(
                images =
                    FindroidImages(
                        primary = poster,
                        backdrop = backdrop,
                        showPrimary = showPoster,
                        showBackdrop = showBackdrop,
                    )
            )

        assertEquals(
            listOf(
                ArtworkSource(backdrop),
                ArtworkSource(showBackdrop),
                ArtworkSource(poster, standIn = true),
                ArtworkSource(showPoster, standIn = true),
            ),
            artworkSources(season, ArtworkPlacement.Header, canBlur = true),
        )
    }

    @Test
    fun `an episode uses its still and the show's backdrop, never a blurred poster`() {
        val episode =
            dummyEpisode.copy(
                images =
                    FindroidImages(
                        primary = still,
                        showPrimary = showPoster,
                        showBackdrop = showBackdrop,
                    )
            )

        assertEquals(
            listOf(ArtworkSource(still), ArtworkSource(showBackdrop)),
            artworkSources(episode, ArtworkPlacement.Header, canBlur = true),
        )
        assertEquals(
            listOf(ArtworkSource(showBackdrop), ArtworkSource(still)),
            artworkSources(episode, ArtworkPlacement.Hero, canBlur = true),
        )
    }

    @Test
    fun `an image that fails moves on, and none left means no artwork`() {
        val state = ArtworkState(listOf(ArtworkSource(backdrop), ArtworkSource(poster, true)))
        assertTrue(state.hasArtwork)

        state.onError()
        assertEquals(ArtworkSource(poster, true), state.current)

        state.onError()
        assertNull(state.current)
        assertFalse(state.hasArtwork)
    }

    @Test
    fun `an item without any image has no artwork`() {
        assertFalse(ArtworkState(emptyList()).hasArtwork)
    }

    @Test
    fun `the collapsed hero puts the poster right below the top bar`() {
        // Status bar 24 dp, top bar 64 dp, a 240 dp poster hanging half over the band.
        assertEquals(208.dp, collapsedHeroHeight(safeTop = 24.dp, posterOverlap = 120.dp))
    }
}
