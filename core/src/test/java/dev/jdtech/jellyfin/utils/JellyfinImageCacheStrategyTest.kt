package dev.jdtech.jellyfin.utils

import coil3.annotation.ExperimentalCoilApi
import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.request.Options
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** #370: a cached poster was fetched again on every load, and did not load offline. */
@OptIn(ExperimentalCoilApi::class, ExperimentalTime::class)
@RunWith(RobolectricTestRunner::class)
class JellyfinImageCacheStrategyTest {
    private val downloaded = Instant.parse("2026-11-10T11:00:00Z")
    private val options = Options(RuntimeEnvironment.getApplication())
    private val tagged = NetworkRequest("https://demo.example/items/abc/Images/Primary?tag=t1")
    private val untagged = NetworkRequest("https://demo.example/items/abc/Images/Primary")
    private val etag = "\"e99d577f80a2c8e9a4456b560b7b3a9b\""

    /** What Jellyfin 10.11 sends for an image whose file last changed [fileAge] ago. */
    private fun cached(
        fileAge: Duration,
        cacheControl: String? = "public, max-age=31536000, immutable",
    ): NetworkResponse {
        val headers =
            NetworkHeaders.Builder().set("Age", fileAge.inWholeSeconds.toString()).set("ETag", etag)
        cacheControl?.let { headers["Cache-Control"] = it }
        return NetworkResponse(
            requestMillis = downloaded.toEpochMilliseconds(),
            responseMillis = downloaded.toEpochMilliseconds(),
            headers = headers.build(),
        )
    }

    private fun read(response: NetworkResponse, request: NetworkRequest, after: Duration) =
        runBlocking {
            JellyfinImageCacheStrategy { downloaded + after }.read(response, request, options)
        }

    @Test
    fun `artwork older than a year is served from the cache`() {
        val result = read(cached(fileAge = 800.days), tagged, after = 5.minutes)

        assertEquals(etag, result.response?.headers?.get("ETag"))
        assertNull(result.request)
    }

    @Test
    fun `the plain strategy asks the server about that same artwork`() {
        val result = runBlocking {
            CacheControlCacheStrategy { downloaded + 5.minutes }
                .read(cached(fileAge = 800.days), tagged, options)
        }

        assertNull(result.response)
        assertEquals(etag, result.request?.headers?.get("If-None-Match"))
    }

    @Test
    fun `a tagged image is served from the cache when a proxy dropped the cache headers`() {
        val result = read(cached(fileAge = 800.days, cacheControl = null), tagged, 400.days)

        assertNotNull(result.response)
        assertNull(result.request)
    }

    @Test
    fun `a tagged and sized image is served from the cache`() {
        val sized = NetworkRequest("${tagged.url}&fillWidth=320&quality=90")
        val result = read(cached(fileAge = 800.days), sized, after = 5.minutes)

        assertNotNull(result.response)
    }

    @Test
    fun `an untagged image follows max-age without the file age`() {
        val result = read(cached(fileAge = 800.days), untagged, after = 5.minutes)

        assertNotNull(result.response)
        assertNull(result.request)
    }

    @Test
    fun `an untagged image is checked again once its max-age has passed`() {
        val result = read(cached(fileAge = 1.days), untagged, after = 366.days)

        assertNull(result.response)
        assertEquals(etag, result.request?.headers?.get("If-None-Match"))
    }

    @Test
    fun `an untagged image without a max-age is checked every time`() {
        val result =
            read(cached(fileAge = 800.days, cacheControl = "public"), untagged, after = 5.minutes)

        assertNull(result.response)
        assertNotNull(result.request)
    }
}
