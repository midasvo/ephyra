package dev.jdtech.jellyfin.player.local.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoSyncSubtitleCueParserTest {

    @Test
    fun parseSrtFormat() {
        val srt =
            """
            1
            00:01:20,000 --> 00:01:23,450
            Hello, how are you?

            2
            00:01:24,000 --> 00:01:27,000
            <i>I am doing well, thank you.</i>
            """
                .trimIndent()

        val cues = AutoSyncSubtitleCueParser.parse(srt, "subs.srt")
        assertEquals(2, cues.size)
        assertEquals(80_000L, cues[0].startTimeMs)
        assertEquals(83_450L, cues[0].endTimeMs)
        assertEquals("Hello, how are you?", cues[0].text)

        assertEquals(84_000L, cues[1].startTimeMs)
        assertEquals("I am doing well, thank you.", cues[1].text)
    }

    @Test
    fun parseWebVttFormat() {
        val vtt =
            """
            WEBVTT

            00:00:15.500 --> 00:00:18.000
            Welcome to the show.

            00:00:19.000 --> 00:00:22.000
            Enjoy your stay!
            """
                .trimIndent()

        val cues = AutoSyncSubtitleCueParser.parse(vtt, "subs.vtt")
        assertEquals(2, cues.size)
        assertEquals(15_500L, cues[0].startTimeMs)
        assertEquals("Welcome to the show.", cues[0].text)
        assertEquals(19_000L, cues[1].startTimeMs)
        assertEquals("Enjoy your stay!", cues[1].text)
    }

    @Test
    fun parseAssFormat() {
        val ass =
            """
            [Script Info]
            Title: Test

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:02:10.50,0:02:13.20,Default,,0,0,0,,{\b1}Look out!{\b0}\NGet down!
            """
                .trimIndent()

        val cues = AutoSyncSubtitleCueParser.parse(ass, "subs.ass")
        assertEquals(1, cues.size)
        assertEquals(130_500L, cues[0].startTimeMs)
        assertEquals(133_200L, cues[0].endTimeMs)
        assertEquals("Look out!\nGet down!", cues[0].text)
    }

    @Test
    fun parseTtmlFormatWithOffsetTimesAndNamespacePrefixes() {
        val ttml =
            """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:tt="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <tt:p begin="1.5s" end="4.2s">Hello from prefixed TTML</tt:p>
                  <p begin="00:01:10.250" end="00:01:15.500">Unprefixed clock time</p>
                  <tt:p begin="500ms">Quick line</tt:p>
                </div>
              </body>
            </tt>
            """
                .trimIndent()

        val cues = AutoSyncSubtitleCueParser.parse(ttml, "subs.ttml")
        assertEquals(3, cues.size)

        assertEquals(500L, cues[0].startTimeMs)
        assertEquals("Quick line", cues[0].text)

        assertEquals(1_500L, cues[1].startTimeMs)
        assertEquals(4_200L, cues[1].endTimeMs)
        assertEquals("Hello from prefixed TTML", cues[1].text)

        assertEquals(70_250L, cues[2].startTimeMs)
        assertEquals(75_500L, cues[2].endTimeMs)
        assertEquals("Unprefixed clock time", cues[2].text)
    }
}
