package dev.jdtech.jellyfin.player.local.domain

import androidx.media3.common.C
import dev.jdtech.jellyfin.models.SeriesTrackPreferenceDto
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * #596: a movie has no saved track choice, so a track picked by hand was lost whenever the stream
 * was loaded again (a quality switch). The picks of the open player are kept per item.
 */
class SessionTrackPicksTest {
    private val movie = UUID.randomUUID()
    private val dutch = TrackPick(C.TRACK_TYPE_AUDIO, StoredChoice("nld", "Dutch 5.1", 0))
    private val german = TrackPick(C.TRACK_TYPE_TEXT, StoredChoice("deu", "External", 0))

    @Test
    fun `an item nothing was picked in has no picks`() {
        assertEquals(emptyList<TrackPick>(), SessionTrackPicks().of(movie))
    }

    @Test
    fun `audio and subtitles are kept next to each other`() {
        val picks = SessionTrackPicks()
        picks.remember(movie, dutch)
        picks.remember(movie, german)

        assertEquals(listOf(dutch, german), picks.of(movie))
    }

    @Test
    fun `a later pick of the same kind replaces the earlier one`() {
        val picks = SessionTrackPicks()
        picks.remember(movie, german)
        val off = TrackPick(C.TRACK_TYPE_TEXT, null)
        picks.remember(movie, off)

        assertEquals(listOf(off), picks.of(movie))
    }

    @Test
    fun `picks belong to one item`() {
        val picks = SessionTrackPicks()
        picks.remember(movie, dutch)

        assertEquals(emptyList<TrackPick>(), picks.of(UUID.randomUUID()))
    }

    @Test
    fun `a forgotten pick leaves the other kind alone`() {
        val picks = SessionTrackPicks()
        picks.remember(movie, dutch)
        picks.remember(movie, german)
        picks.forget(movie, C.TRACK_TYPE_AUDIO)
        picks.forget(UUID.randomUUID(), C.TRACK_TYPE_TEXT)

        assertEquals(listOf(german), picks.of(movie))
    }

    @Test
    fun `a choice is applied again when the item gets more tracks, and not otherwise`() {
        // mpv: the stream's own tracks first, the subtitle files after them.
        val first = trackPreferenceKey("item", 1)
        assertEquals(first, trackPreferenceKey("item", 1))
        assertNotEquals(first, trackPreferenceKey("item", 4))
        assertNotEquals(first, trackPreferenceKey("other", 1))
    }

    @Test
    fun `a saved series choice becomes the same kind of pick`() {
        val userId = UUID.randomUUID()
        val seriesId = UUID.randomUUID()
        val audio =
            SeriesTrackPreferenceDto(
                userId,
                seriesId,
                C.TRACK_TYPE_AUDIO,
                disabled = false,
                language = "nld",
                title = "Dutch 5.1",
                positionInLanguage = 0,
            )
        val subtitlesOff =
            SeriesTrackPreferenceDto(userId, seriesId, C.TRACK_TYPE_TEXT, disabled = true)

        assertEquals(dutch, audio.toTrackPick())
        assertEquals(TrackPick(C.TRACK_TYPE_TEXT, null), subtitlesOff.toTrackPick())
    }
}
