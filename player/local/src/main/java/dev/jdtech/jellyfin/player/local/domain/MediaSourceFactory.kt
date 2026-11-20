package dev.jdtech.jellyfin.player.local.domain

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegSubtitleExtractorsFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.withFfmpegSubtitles
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

// Media3 retries on top of these, so a dead server stays on the spinner for several times as long.
private const val HTTP_CONNECT_TIMEOUT_MS = 15_000L
private const val HTTP_READ_TIMEOUT_MS = 60_000L

/**
 * Gives a transcode longer than Media3's default 8 s to return its first bytes.
 *
 * CE (#296): media goes over [apiClient] (the Jellyfin API's OkHttp client, see
 * `JellyfinApi.httpClient`) with its own timeouts. `newBuilder()` keeps the connection pool, so the
 * first media request reuses the connection the PlaybackInfo call just used instead of opening one.
 *
 * CE (#613): an image subtitle loaded as a file of its own is read by [ImageSubtitleParserFactory].
 */
@OptIn(UnstableApi::class)
internal fun buildMediaSourceFactory(
    context: Context,
    apiClient: OkHttpClient,
): MediaSource.Factory {
    val client =
        apiClient
            .newBuilder()
            .connectTimeout(HTTP_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(HTTP_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    // The User-Agent DefaultHttpDataSource sent (the system's), so the server's logs and any
    // proxy rules see the same client as before.
    val dataSources =
        DefaultDataSource.Factory(
            context,
            OkHttpDataSource.Factory(client).setUserAgent(System.getProperty("http.agent")),
        )
    if (!libassSpikeEnabled()) {
        return DefaultMediaSourceFactory(dataSources)
            .setSubtitleParserFactory(ImageSubtitleParserFactory())
    }
    // Spike (#668): ASS/SSA goes to libass unparsed, as a track of the file (with the fonts
    // attached to a Matroska file) and as a file of its own. withFfmpegSubtitles sets a parser
    // factory of its own that also takes the image formats away from media3; ours is set after it,
    // so those stay where they are (#613).
    val factory = DefaultMediaSourceFactory(dataSources, FfmpegSubtitleExtractorsFactory())
    val withAss = factory.withFfmpegSubtitles(dataSources)
    factory.setSubtitleParserFactory(ImageSubtitleParserFactory(WithoutSsaParserFactory()))
    return withAss
}
