package dev.jdtech.jellyfin.testing

import dev.jdtech.jellyfin.domain.ports.PlaybackPort
import dev.jdtech.jellyfin.domain.ports.PlaybackReport
import dev.jdtech.jellyfin.domain.ports.PlaybackRequest
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.MediaStreamType
import dev.jdtech.jellyfin.models.PlaybackTarget
import dev.jdtech.jellyfin.models.PlayerEngineId
import dev.jdtech.jellyfin.testing.ContractLibrary.Part
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The contract of [PlaybackPort] (#771): sources, the reports and what the player shows alongside.
 * The tests for a feature behind a capability start with an assumption on it.
 */
abstract class PlaybackContract : MediaServerContract() {
    /** mpv: it direct-plays what the server has, whatever the device's decoders. */
    private val player =
        PlaybackTarget.LocalPlayer(PlayerEngineId.MPV, directPlayDolbyVision = false)

    @Test
    fun `a direct-play source has a URL and is not transcoded`() = runTest {
        val sources =
            server()
                .playback
                .sources(PlaybackRequest(library.movieWithResume.id, player, includePath = true))

        val source = sources.single()
        assertEquals(ItemSourceType.REMOTE, source.type)
        assertTrue(source.path.isNotBlank(), "no URL: $source")
        assertFalse(source.transcoded)
        assertNull(source.playSessionId)
    }

    @Test
    fun `a capped request for a source above the cap is transcoded with a play session`() =
        runTest {
            val source =
                server()
                    .playback
                    .sources(
                        PlaybackRequest(library.movieWithResume.id, player, maxBitrate = 100_000)
                    )
                    .single()

            assertTrue(source.transcoded, "not transcoded: $source")
            assertTrue(source.path.isNotBlank(), "no URL: $source")
            assertTrue(!source.playSessionId.isNullOrBlank(), "no play session: $source")
        }

    @Test
    fun `a capped download carries no play session`() = runTest {
        val source =
            server()
                .playback
                .sources(
                    PlaybackRequest(
                        library.movieWithResume.id,
                        PlaybackTarget.Download(transcodeDolbyVision = false),
                        maxBitrate = 100_000,
                    )
                )
                .single()

        assertTrue(source.transcoded, "not transcoded: $source")
        assertNull(source.playSessionId)
    }

    /**
     * The index goes to the server only with the source it is of (#580). The outcome binds a server
     * that would honour a lone index; one that ignores it (Jellyfin) gives equal answers either
     * way. For the Jellyfin adapter the recordings hold what it asks: they are matched on the index
     * the request carries, so an adapter that sent it alone finds no recording.
     */
    @Test
    fun `audioStreamIndex without mediaSourceId is ignored`() = runTest {
        val playback = server().playback
        val id = library.movieWithExternalSubtitle.id
        fun List<dev.jdtech.jellyfin.models.ItemSource>.decision() = map {
            Triple(it.id, it.transcoded, it.defaultAudioStreamIndex)
        }

        val plain = playback.sources(PlaybackRequest(id, player)).decision()
        val withIndex = playback.sources(PlaybackRequest(id, player, audioStreamIndex = 5))

        assertEquals(plain, withIndex.decision())
    }

    @Test
    fun `the movie with two versions has two sources`() = runTest {
        val sources =
            server().playback.sources(PlaybackRequest(library.movieWithTwoVersions.id, player))

        assertEquals(2, sources.size)
        assertEquals(2, sources.map { it.id }.distinct().size)
        assertEquals(
            library.movieWithTwoVersions.sources.map { it.name }.toSet(),
            sources.map { it.name }.toSet(),
        )
    }

    @Test
    fun `an external subtitle is listed with its source`() = runTest {
        val source =
            server()
                .playback
                .sources(PlaybackRequest(library.movieWithExternalSubtitle.id, player))
                .single()

        assertTrue(
            source.mediaStreams.any { it.type == MediaStreamType.SUBTITLE && it.isExternal },
            "streams: ${source.mediaStreams}",
        )
    }

    @Test
    fun `start, progress and stop are accepted in order`() = runTest {
        val playback = server().playback
        val id = library.favoriteMovie.id

        playback.reportStart(report(id, 0L))
        playback.reportProgress(report(id, 10_000_000L))
        playback.reportStopped(report(id, 20_000_000L))
    }

    @Test
    fun `a stop halfway keeps the position`() = runTest {
        val server = server()
        val movie = library.movieWithResume
        val halfway = movie.runtimeTicks / 2

        server.playback.reportStart(report(movie.id, movie.playbackPositionTicks))
        server.playback.reportStopped(report(movie.id, halfway))

        val position = server.items.movie(movie.id).playbackPositionTicks
        // A server may round it to its own resolution.
        assertTrue(kotlin.math.abs(position - halfway) < 10_000_000L, "position $position")
    }

    @Test
    fun `the episode with segments has its intro`() = runTest {
        val server = server()
        assumeTrue(server.capabilities.mediaSegments)
        assumeOnServer(Part.SEGMENTS)

        val segments = server.playback.segments(library.episodeWithSegments.id)

        assertEquals(listOf(library.introOfEpisodeWithSegments), segments)
    }

    @Test
    fun `a trickplay tile of an episode has bytes`() = runTest {
        val server = server()
        assumeTrue(server.capabilities.trickplay)
        val episode = library.season1Episodes.first()
        val info = assertNotNull(episode.trickplayInfo?.values?.firstOrNull())

        val tile = server.playback.trickplayTile(episode.id, info.width, 0, mediaSourceId = null)

        assertTrue(tile != null && tile.isNotEmpty(), "no tile")
    }

    @Test
    fun `the server's resume thresholds are in order`() = runTest {
        val server = server()
        assumeTrue(server.capabilities.serverResumeThresholds)

        val thresholds = assertNotNull(server.playback.resumeThresholds())

        assertTrue(thresholds.minResumePct < thresholds.maxResumePct, "$thresholds")
    }

    @Test
    fun `a server without segments gives none`() = runTest {
        val server = server()
        assumeFalse(server.capabilities.mediaSegments)

        assertTrue(server.playback.segments(library.episodeWithSegments.id).isEmpty())
    }

    @Test
    fun `a server without trickplay gives no tile`() = runTest {
        val server = server()
        assumeFalse(server.capabilities.trickplay)
        val episode = library.season1Episodes.first()

        assertNull(server.playback.trickplayTile(episode.id, 320, 0, mediaSourceId = null))
    }

    @Test
    fun `a server without resume thresholds gives none`() = runTest {
        val server = server()
        assumeFalse(server.capabilities.serverResumeThresholds)

        assertNull(server.playback.resumeThresholds())
    }

    @Test
    fun `a server without the auto-play setting gives none`() = runTest {
        val server = server()
        assumeFalse(server.capabilities.autoPlayNextSetting)

        assertNull(server.session.autoPlayNextEpisode())
    }

    @Test
    fun `a failing server gives an error status`() = runTest {
        val playback = failingServer().playback
        val id = library.movieWithResume.id

        assertErrorStatus { playback.sources(PlaybackRequest(id, player)) }
        assertErrorStatus { playback.reportStart(report(id, 0L)) }
        assertErrorStatus { playback.reportStopped(report(id, 0L)) }
    }

    @Test
    fun `a server that does not answer gives an unanswered failure`() = runTest {
        val playback = unreachableServer().playback
        val id = library.movieWithResume.id

        assertUnanswered { playback.sources(PlaybackRequest(id, player)) }
        assertUnanswered { playback.reportProgress(report(id, 0L)) }
    }

    @Test
    fun `a request for sources cancelled while it waits is cancelled`() = runTest {
        val playback = stallingServer().playback

        assertCancellable { playback.sources(PlaybackRequest(library.movieWithResume.id, player)) }
    }

    private fun report(id: ItemId, positionTicks: Long) =
        PlaybackReport(
            itemId = id,
            positionTicks = positionTicks,
            isPaused = false,
            transcoded = false,
            playSessionId = null,
        )
}
