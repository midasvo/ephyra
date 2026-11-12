package dev.jdtech.jellyfin.film.presentation

import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidImages
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSourceType
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #373: a season or show download queues only the episodes the server allows to be downloaded,
 * besides leaving out what is downloaded or in flight already.
 */
class EpisodeDownloadSelectionTest {

    @Test
    fun `all allowed queues everything not downloaded or in flight`() {
        val downloaded = episode(canDownload = true, downloaded = true)
        val inFlight = episode(canDownload = true)
        val new = List(3) { episode(canDownload = true) }

        val selection =
            selectEpisodesToDownload(listOf(downloaded, inFlight) + new, setOf(inFlight.id))

        assertEquals(new, selection.toQueue)
        assertEquals(2, selection.skipped)
    }

    @Test
    fun `none allowed queues nothing`() {
        val episodes = List(3) { episode(canDownload = false) }

        val selection = selectEpisodesToDownload(episodes, emptySet())

        assertEquals(emptyList<FindroidEpisode>(), selection.toQueue)
        assertEquals(0, selection.skipped)
    }

    @Test
    fun `mixed queues only the allowed ones`() {
        val allowed = episode(canDownload = true)
        val notAllowed = episode(canDownload = false)
        val downloadedNotAllowed = episode(canDownload = false, downloaded = true)

        val selection =
            selectEpisodesToDownload(listOf(notAllowed, allowed, downloadedNotAllowed), emptySet())

        assertEquals(listOf(allowed), selection.toQueue)
        assertEquals(1, selection.skipped)
    }

    private fun episode(canDownload: Boolean, downloaded: Boolean = false) =
        FindroidEpisode(
            id = UUID.randomUUID(),
            name = "Episode",
            originalTitle = null,
            overview = "",
            indexNumber = 1,
            indexNumberEnd = null,
            parentIndexNumber = 1,
            sources =
                if (downloaded) {
                    listOf(
                        FindroidSource(
                            id = "local",
                            name = "",
                            type = FindroidSourceType.LOCAL,
                            path = "/downloads/Episode.mkv",
                            size = 0L,
                            mediaStreams = emptyList(),
                        )
                    )
                } else emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = canDownload,
            runtimeTicks = 0L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            seriesId = UUID.randomUUID(),
            seriesName = "Show",
            seasonId = UUID.randomUUID(),
            seasonName = null,
            communityRating = null,
            people = emptyList(),
            missing = false,
            images = FindroidImages(),
            chapters = emptyList(),
            trickplayInfo = null,
        )
}
