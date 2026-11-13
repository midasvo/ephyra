package dev.jdtech.jellyfin.player.cast

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the URLs the receiver is given (#626). The receiver fetches whatever it gets from
 * inside the user's network, so [receiverUrl] passes on nothing that is not on the server.
 */
class CastUrlsTest {

    private val base = "https://jellyfin.example"
    private val itemId = UUID.fromString("0b0e8b2c-52b5-4c0e-9d5a-6a1c7c1f3a10")

    @Test
    fun `a path on the server becomes an absolute URL`() {
        assertEquals(
            "$base/Videos/1/2/Subtitles/3/0/Stream.vtt?api_key=abc",
            receiverUrl(base, "/Videos/1/2/Subtitles/3/0/Stream.vtt?api_key=abc"),
        )
        assertEquals("$base/Videos/1/Stream.vtt", receiverUrl(base, "Videos/1/Stream.vtt"))
        assertEquals("$base/Videos/1/Stream.vtt", receiverUrl("$base/", "/Videos/1/Stream.vtt"))
    }

    @Test
    fun `a path goes behind the path the server is under`() {
        assertEquals(
            "https://example.org/jellyfin/Videos/1/Stream.vtt",
            receiverUrl("https://example.org/jellyfin", "/Videos/1/Stream.vtt"),
        )
    }

    @Test
    fun `a path with a colon in it is still a path`() {
        // android.net.Uri took everything before the first colon for a scheme and the URL was
        // dropped.
        assertEquals(
            "$base/Videos/1/Stream.vtt?t=00:10:00",
            receiverUrl(base, "/Videos/1/Stream.vtt?t=00:10:00"),
        )
        assertEquals(
            "$base/Videos/1/Stream.vtt?from=http://other.example/x",
            receiverUrl(base, "Videos/1/Stream.vtt?from=http://other.example/x"),
        )
    }

    @Test
    fun `an absolute URL on the server is kept`() {
        assertEquals("$base/Videos/1/Stream.vtt", receiverUrl(base, "$base/Videos/1/Stream.vtt"))
        assertEquals(
            "http://192.168.1.10:8096/Videos/1/Stream.vtt",
            receiverUrl("http://192.168.1.10:8096", "http://192.168.1.10:8096/Videos/1/Stream.vtt"),
        )
        assertEquals(
            "http://[fd00::1]:8096/Videos/1/Stream.vtt",
            receiverUrl("http://[fd00::1]:8096", "http://[fd00::1]:8096/Videos/1/Stream.vtt"),
        )
        assertEquals(
            "http://media_server:8096/Videos/1/Stream.vtt",
            receiverUrl("http://media_server:8096", "http://media_server:8096/Videos/1/Stream.vtt"),
        )
    }

    @Test
    fun `the host and the scheme are compared without case, the default port counts`() {
        assertEquals(
            "HTTPS://JELLYFIN.EXAMPLE/Videos/1/Stream.vtt",
            receiverUrl(base, "HTTPS://JELLYFIN.EXAMPLE/Videos/1/Stream.vtt"),
        )
        assertEquals(
            "https://jellyfin.example:443/Videos/1/Stream.vtt",
            receiverUrl(base, "https://jellyfin.example:443/Videos/1/Stream.vtt"),
        )
        assertEquals(
            "http://jellyfin.example/x.vtt",
            receiverUrl("http://jellyfin.example:80", "http://jellyfin.example/x.vtt"),
        )
    }

    @Test
    fun `a URL on another host is dropped`() {
        assertNull(receiverUrl(base, "https://other.example/Videos/1/Stream.vtt"))
        assertNull(receiverUrl(base, "http://192.168.1.1/admin"))
        assertNull(receiverUrl(base, "https://jellyfin.example.other.example/x.vtt"))
        assertNull(receiverUrl(base, "https://jellyfin.example@other.example/x.vtt"))
        assertNull(receiverUrl(base, "https://other.example#@jellyfin.example/x.vtt"))
        assertNull(receiverUrl(base, "https://other.example\\@jellyfin.example/x.vtt"))
        assertNull(receiverUrl(base, "https:///x.vtt"))
    }

    @Test
    fun `a URL on another port or scheme of the server's host is dropped`() {
        // Another port is another service on that machine.
        assertNull(receiverUrl(base, "https://jellyfin.example:8443/x.vtt"))
        assertNull(receiverUrl(base, "http://jellyfin.example/x.vtt"))
        assertNull(receiverUrl("http://192.168.1.10:8096", "http://192.168.1.10/x.vtt"))
        assertNull(receiverUrl("http://192.168.1.10:8096", "http://192.168.1.10:port/x.vtt"))
    }

    @Test
    fun `a URL without a host of its own is dropped unless it is a path`() {
        assertNull(receiverUrl(base, "file:///etc/passwd"))
        assertNull(receiverUrl(base, "ftp://jellyfin.example/x.vtt"))
        assertNull(receiverUrl(base, "javascript:alert(1)"))
        assertNull(receiverUrl(base, "data:text/vtt,WEBVTT"))
        assertNull(receiverUrl(base, "jellyfin.example:443/x.vtt"))
    }

    @Test
    fun `a scheme-relative URL names a host and is held to the server's`() {
        // It used to be glued behind the server as a path: "https://jellyfin.example//other...".
        assertNull(receiverUrl(base, "//other.example/x.vtt"))
        assertNull(receiverUrl(base, "//jellyfin.example:8443/x.vtt"))
        assertEquals(
            "https://jellyfin.example/x.vtt",
            receiverUrl(base, "//jellyfin.example/x.vtt"),
        )
    }

    @Test
    fun `no URL is no track`() {
        assertNull(receiverUrl(base, ""))
    }

    @Test
    fun `a file that plays as it is gets a static stream URL with the access token`() {
        assertEquals(
            "$base/Videos/$itemId/stream?static=true&mediaSourceId=abc123&api_key=token",
            castStreamUrl(base, itemId, "abc123", transcodingUrl = null, accessToken = "token"),
        )
        // A blank transcoding URL is none.
        assertEquals(
            "$base/Videos/$itemId/stream?static=true&mediaSourceId=abc123&api_key=token",
            castStreamUrl(base, itemId, "abc123", transcodingUrl = " ", accessToken = "token"),
        )
        assertEquals(
            "https://example.org/jellyfin/Videos/$itemId/stream?static=true&mediaSourceId=abc123",
            directPlayUrl("https://example.org/jellyfin/", itemId, "abc123", accessToken = null),
        )
    }

    @Test
    fun `what goes into the static stream URL is encoded`() {
        assertEquals(
            "$base/Videos/$itemId/stream?static=true&mediaSourceId=a%26b%3Dc%20d&api_key=t%2Fk",
            directPlayUrl(base, itemId, "a&b=c d", accessToken = "t/k"),
        )
    }

    @Test
    fun `a source without an id is not asked for by the name null`() {
        // Uri.Builder wrote "mediaSourceId=null".
        assertEquals(
            "$base/Videos/$itemId/stream?static=true&api_key=token",
            directPlayUrl(base, itemId, mediaSourceId = null, accessToken = "token"),
        )
    }

    @Test
    fun `a transcode is the server's URL behind the server's address`() {
        val transcodingUrl = "/videos/$itemId/master.m3u8?MediaSourceId=abc&api_key=token"

        assertEquals(
            base + transcodingUrl,
            castStreamUrl(base, itemId, "abc", transcodingUrl, accessToken = "other"),
        )
        assertEquals(
            "https://example.org/jellyfin/videos/1/master.m3u8",
            castStreamUrl("https://example.org/jellyfin", itemId, "abc", "videos/1/master.m3u8", null),
        )
    }

    @Test
    fun `a transcode is HLS and a file is MP4`() {
        assertEquals("application/x-mpegurl", castContentType(isTranscoded = true))
        assertEquals("video/mp4", castContentType(isTranscoded = false))
    }

    @Test
    fun `the audio stream is read back from a stream URL`() {
        assertEquals(
            2,
            audioStreamIndexOf("$base/videos/1/master.m3u8?MediaSourceId=abc&AudioStreamIndex=2"),
        )
        // The server writes it as it likes.
        assertEquals(3, audioStreamIndexOf("$base/videos/1/master.m3u8?audiostreamindex=3&a=b"))
        assertEquals(4, audioStreamIndexOf("$base/videos/1/main.m3u8?a=b&AUDIOSTREAMINDEX=4#t=10"))
    }

    @Test
    fun `a stream URL that names no audio stream has none`() {
        assertNull(audioStreamIndexOf("$base/Videos/1/stream?static=true&mediaSourceId=abc"))
        assertNull(audioStreamIndexOf("$base/Videos/1/stream"))
        assertNull(audioStreamIndexOf("$base/videos/1/master.m3u8?AudioStreamIndex="))
        assertNull(audioStreamIndexOf("$base/videos/1/master.m3u8?AudioStreamIndex=two"))
        assertNull(audioStreamIndexOf("$base/videos/1/master.m3u8?SubtitleStreamIndex=2"))
        assertNull(audioStreamIndexOf("$base/videos/1/master.m3u8#AudioStreamIndex=2"))
        assertNull(audioStreamIndexOf(""))
    }
}
