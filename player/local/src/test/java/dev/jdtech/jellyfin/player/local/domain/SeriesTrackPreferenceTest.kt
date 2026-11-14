package dev.jdtech.jellyfin.player.local.domain

import dev.jdtech.jellyfin.models.FindroidMediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for matching a remembered series audio or subtitle choice to another file's tracks.
 */
class SeriesTrackPreferenceTest {

    private fun track(language: String?, title: String? = null) = TrackCandidate(language, title)

    @Test
    fun `language and title beat position`() {
        val candidates = listOf(track("eng", "Commentary"), track("eng", "Full"), track("eng"))
        val stored = StoredChoice("eng", "Full", 0)
        assertEquals(1, matchSeriesTrack(stored, candidates))
    }

    @Test
    fun `position beats plain language`() {
        val candidates = listOf(track("jpn"), track("eng", "A"), track("eng", "B"))
        val stored = StoredChoice("eng", "Gone", 1)
        assertEquals(2, matchSeriesTrack(stored, candidates))
    }

    @Test
    fun `falls back to first track of the language`() {
        val candidates = listOf(track("jpn"), track("eng", "A"), track("eng", "B"))
        assertEquals(1, matchSeriesTrack(StoredChoice("eng", "Gone", 5), candidates))
        assertEquals(1, matchSeriesTrack(StoredChoice("eng", null, null), candidates))
    }

    @Test
    fun `title only match without a language`() {
        val candidates = listOf(track("eng"), track(null, " forced "))
        assertEquals(1, matchSeriesTrack(StoredChoice(null, "Forced", null), candidates))
    }

    @Test
    fun `two letter and three letter codes match`() {
        val candidates = listOf(track("fra"), track("eng"))
        assertEquals(1, matchSeriesTrack(StoredChoice("en", null, 0), candidates))
        assertEquals(0, matchSeriesTrack(StoredChoice("fr-CA", null, 0), candidates))
    }

    @Test
    fun `no match gives null`() {
        val candidates = listOf(track("jpn"), track("eng"))
        assertNull(matchSeriesTrack(StoredChoice("deu", "Full", 0), candidates))
        assertNull(matchSeriesTrack(StoredChoice(null, "Full", null), candidates))
        assertNull(matchSeriesTrack(StoredChoice("eng", null, 0), emptyList()))
    }

    @Test
    fun `choice records the position within the language`() {
        val candidates = listOf(track("jpn"), track("en", "A"), track("jpn"), track("eng", "B"))
        assertEquals(StoredChoice("eng", "B", 1), seriesTrackChoice(candidates, 3))
        assertEquals(StoredChoice("jpn", null, 1), seriesTrackChoice(candidates, 2))
    }

    @Test
    fun `untagged and untitled tracks give no choice`() {
        val candidates = listOf(track(null), track("und", " "))
        assertNull(seriesTrackChoice(candidates, 0))
        assertNull(seriesTrackChoice(candidates, 1))
        assertNull(seriesTrackChoice(candidates, 2))
    }

    @Test
    fun `title only choice has no position`() {
        val candidates = listOf(track(null, "Forced"))
        assertEquals(StoredChoice(null, "Forced", null), seriesTrackChoice(candidates, 0))
    }

    @Test
    fun `the two ISO 639-2 codes of a language are one language`() {
        // mpv reads "dut" from the file; ExoPlayer and the server make "nl" and "nld" of it.
        assertEquals("nld", normalizeLanguage("dut"))
        assertEquals("nld", normalizeLanguage("nl"))
        assertEquals("deu", normalizeLanguage("ger"))
        assertEquals("fra", normalizeLanguage("FRE"))
        assertEquals("zho", normalizeLanguage("chi"))
        assertEquals("eng", normalizeLanguage("eng"))

        val candidates = listOf(track("eng"), track("dut"), track("ger"))
        assertEquals(1, matchSeriesTrack(StoredChoice("nld", null, 0), candidates))
        assertEquals(2, matchSeriesTrack(StoredChoice("de", null, 0), candidates))
        assertEquals(StoredChoice("nld", null, 0), seriesTrackChoice(candidates, 1))
    }

    private fun stream(
        index: Int?,
        language: String,
        title: String = "",
        type: MediaStreamType = MediaStreamType.AUDIO,
        external: Boolean = false,
    ) =
        FindroidMediaStream(
            title = title,
            displayTitle = null,
            language = language,
            type = type,
            codec = "aac",
            profile = null,
            isExternal = external,
            path = null,
            channelLayout = null,
            videoRangeType = null,
            height = null,
            width = null,
            videoDoViTitle = null,
            index = index,
        )

    @Test
    fun `a choice gives the server's index of the audio stream`() {
        val streams =
            listOf(
                stream(0, "", type = MediaStreamType.VIDEO),
                stream(1, "eng", "English"),
                stream(2, "eng", "Commentary"),
                stream(3, "nld", "Nederlands"),
                stream(4, "nld", type = MediaStreamType.SUBTITLE),
            )
        // The position is among the audio streams of the language, not among all streams.
        assertEquals(3, audioStreamIndex(StoredChoice("dut", "Gone", 0), streams))
        assertEquals(2, audioStreamIndex(StoredChoice("en", "commentary", 0), streams))
        assertEquals(2, audioStreamIndex(StoredChoice("eng", null, 1), streams))
        assertNull(audioStreamIndex(StoredChoice("jpn", null, 0), streams))
    }

    @Test
    fun `the audio dialog of a transcode lists the file's own audio streams in order`() {
        val streams =
            listOf(
                stream(0, "", type = MediaStreamType.VIDEO),
                stream(1, "eng", "English"),
                stream(2, "nld", external = true),
                stream(null, "deu"),
                stream(3, "nld", ""),
                stream(4, "nld", type = MediaStreamType.SUBTITLE),
            )
        val tracks = serverAudioTracks(streams)
        assertEquals(listOf(1, 3), tracks.map { it.index })
        assertEquals(listOf("English", null), tracks.map { it.title })
        assertEquals(listOf("eng", "nld"), tracks.map { it.language })
    }

    @Test
    fun `a pick made in the audio dialog of a transcode is found among the player's tracks`() {
        // #596: the server says "nld", ExoPlayer "nl", mpv "dut".
        val server =
            serverAudioTracks(listOf(stream(1, "eng", "English"), stream(2, "nld", "Dutch")))
        val pick = seriesTrackChoice(server.map { TrackCandidate(it.language, it.title) }, 1)!!
        assertEquals(
            1,
            matchSeriesTrack(pick, listOf(track("en", "English"), track("nl", "Dutch"))),
        )
        assertEquals(1, matchSeriesTrack(pick, listOf(track("eng"), track("dut"))))
    }

    @Test
    fun `external audio and streams without an index are not requested`() {
        val streams =
            listOf(stream(1, "eng"), stream(2, "nld", external = true), stream(null, "deu"))
        assertNull(audioStreamIndex(StoredChoice("nld", null, 0), streams))
        assertNull(audioStreamIndex(StoredChoice("deu", null, 0), streams))
        assertEquals(1, audioStreamIndex(StoredChoice("eng", null, 0), streams))
    }
}
