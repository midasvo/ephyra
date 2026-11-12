package dev.jdtech.jellyfin.utils

import coil3.annotation.ExperimentalCoilApi
import coil3.network.CacheStrategy
import coil3.network.CacheStrategy.ReadResult
import coil3.network.CacheStrategy.WriteResult
import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.request.Options
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private val IMAGE_TAG = Regex("""[?&]tag=[^&]""", RegexOption.IGNORE_CASE)

/**
 * Decides when a cached image is shown without asking the server (#370).
 *
 * [CacheControlCacheStrategy] alone got this wrong for Jellyfin. The server sends `Age` as the time
 * since the image file last changed, not the time the response spent in a cache, so artwork older
 * than its `max-age` of one year counted as expired the moment it arrived. Every such image was
 * checked with the server again each time it was shown, and when the server could not be reached it
 * did not load at all.
 *
 * So:
 * - An image URL with a `tag` is served from the cache, whatever the headers say. The tag names
 *   the image's content: when the artwork changes, the server hands out a new tag and so a new URL.
 * - Anything else follows the server's `Cache-Control`, with `Age` left out of it.
 */
@OptIn(ExperimentalCoilApi::class, ExperimentalTime::class)
class JellyfinImageCacheStrategy(now: () -> Instant = Clock.System::now) : CacheStrategy {
    private val delegate = CacheControlCacheStrategy(now)

    override suspend fun read(
        cacheResponse: NetworkResponse,
        networkRequest: NetworkRequest,
        options: Options,
    ): ReadResult {
        if (IMAGE_TAG.containsMatchIn(networkRequest.url)) return ReadResult(cacheResponse)
        return delegate.read(
            cacheResponse.copy(headers = cacheResponse.headers.withoutAge()),
            networkRequest,
            options,
        )
    }

    override suspend fun write(
        cacheResponse: NetworkResponse?,
        networkRequest: NetworkRequest,
        networkResponse: NetworkResponse,
        options: Options,
    ): WriteResult = delegate.write(cacheResponse, networkRequest, networkResponse, options)

    private fun NetworkHeaders.withoutAge(): NetworkHeaders {
        val builder = NetworkHeaders.Builder()
        for ((name, values) in asMap()) {
            if (!name.equals("Age", ignoreCase = true)) builder[name] = values
        }
        return builder.build()
    }
}
