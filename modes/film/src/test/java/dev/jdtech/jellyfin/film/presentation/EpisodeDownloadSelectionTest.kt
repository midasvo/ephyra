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
 * besides leaving out what is downloaded or in flight already. CE (#636): and, when the user asks,
 * only the unwatched ones.
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

    @Test
    fun `unwatched only leaves out watched episodes without counting them as skipped`() {
        val watched = episode(canDownload = true, played = true)
        val unwatched = episode(canDownload = true)
        val halfWatched = episode(canDownload = true, positionTicks = 6_000_000_000L)
        val downloadedWatched = episode(canDownload = true, downloaded = true, played = true)

        val selection =
            selectEpisodesToDownload(
                listOf(watched, unwatched, halfWatched, downloadedWatched),
                emptySet(),
                unwatchedOnly = true,
            )

        assertEquals(listOf(unwatched, halfWatched), selection.toQueue)
        assertEquals(1, selection.skipped)
    }

    @Test
    fun `without unwatched only, watched episodes are queued too`() {
        val watched = episode(canDownload = true, played = true)
        val unwatched = episode(canDownload = true)

        val selection = selectEpisodesToDownload(listOf(watched, unwatched), emptySet())

        assertEquals(listOf(watched, unwatched), selection.toQueue)
    }

    @Test
    fun `a choice is offered when some episodes to queue are watched`() {
        val episodes =
            List(3) { episode(canDownload = true, played = true) } +
                List(7) { episode(canDownload = true) }

        assertEquals(
            EpisodeDownloadChoice(allCount = 10, unwatchedCount = 7),
            downloadChoiceFor(episodes, emptySet()),
        )
    }

    @Test
    fun `a choice is offered when every episode to queue is watched`() {
        val episodes = List(4) { episode(canDownload = true, played = true) }

        assertEquals(
            EpisodeDownloadChoice(allCount = 4, unwatchedCount = 0),
            downloadChoiceFor(episodes, emptySet()),
        )
    }

    @Test
    fun `no choice when nothing to queue is watched`() {
        val unwatched = List(3) { episode(canDownload = true) }
        val halfWatched = episode(canDownload = true, positionTicks = 6_000_000_000L)
        // Watched, but nothing to ask about: downloaded already, in flight, or not allowed.
        val downloadedWatched = episode(canDownload = true, downloaded = true, played = true)
        val inFlightWatched = episode(canDownload = true, played = true)
        val notAllowedWatched = episode(canDownload = false, played = true)

        val choice =
            downloadChoiceFor(
                unwatched + halfWatched + downloadedWatched + inFlightWatched + notAllowedWatched,
                setOf(inFlightWatched.id),
            )

        assertEquals(null, choice)
    }

    @Test
    fun `prompts ask which episodes first, then the quality`() {
        val watched = episode(canDownload = true, played = true)
        val unwatched = episode(canDownload = true)
        val prompts = EpisodeDownloadPrompts()

        prompts.start(listOf(watched, unwatched), emptySet())
        assertEquals(
            DownloadPrompt.Episodes(EpisodeDownloadChoice(allCount = 2, unwatchedCount = 1)),
            prompts.prompt.value,
        )
        // The quality cannot be answered before the episodes are.
        assertEquals(null, prompts.chooseQuality(maxBitrate = 1))

        prompts.chooseEpisodes(unwatchedOnly = true)
        assertEquals(DownloadPrompt.Quality, prompts.prompt.value)

        val answer = prompts.chooseQuality(maxBitrate = 4_000_000)
        assertEquals(
            EpisodeDownloadPrompts.Answer(listOf(watched, unwatched), 4_000_000, true),
            answer,
        )
        assertEquals(null, prompts.prompt.value)
    }

    @Test
    fun `prompts go straight to the quality when nothing is watched`() {
        val episodes = List(2) { episode(canDownload = true) }
        val prompts = EpisodeDownloadPrompts()

        prompts.start(episodes, emptySet())
        assertEquals(DownloadPrompt.Quality, prompts.prompt.value)
        assertEquals(
            EpisodeDownloadPrompts.Answer(episodes, null, false),
            prompts.chooseQuality(maxBitrate = null),
        )
    }

    @Test
    fun `dismissing the prompts downloads nothing`() {
        val prompts = EpisodeDownloadPrompts()
        prompts.start(listOf(episode(canDownload = true, played = true)), emptySet())

        prompts.dismiss()

        assertEquals(null, prompts.prompt.value)
        assertEquals(null, prompts.chooseQuality(maxBitrate = null))
    }

    private fun episode(
        canDownload: Boolean,
        downloaded: Boolean = false,
        played: Boolean = false,
        positionTicks: Long = 0L,
    ) =
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
            played = played,
            favorite = false,
            canPlay = true,
            canDownload = canDownload,
            runtimeTicks = 0L,
            playbackPositionTicks = positionTicks,
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
