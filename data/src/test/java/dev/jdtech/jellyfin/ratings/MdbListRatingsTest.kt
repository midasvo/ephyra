package dev.jdtech.jellyfin.ratings

import android.content.SharedPreferences
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.ExternalRatingsKeyStatus
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * CE (#331): the rules around the requests: nothing while off, the cache and its TTLs, the negative
 * cache, Retry-After and a rejected key. The clock is the test's.
 */
class MdbListRatingsTest {
    @TempDir lateinit var cacheDir: File

    private val server = FakeMdbList()
    private val values =
        mutableMapOf<String, Any?>("pref_external_ratings" to true, "pref_mdblist_api_key" to "key")
    private var now = 1_000_000_000L
    private val lookup = RatingsLookup("tmdb", "movie", "278")
    private val other = RatingsLookup("imdb", "movie", "tt0111161")

    /** Runs on every clock read: the first one inside a lookup comes after the cache read. */
    private var onClock: () -> Unit = {}

    private val ratings by lazy {
        MdbListRatings(appPreferences(), server.client(), ExternalRatingsCache(cacheDir)) {
            onClock()
            now
        }
    }

    @AfterEach fun stop() = server.close()

    @Test
    fun `switched off during the cache read, nothing is sent`() {
        onClock = { values["pref_external_ratings"] = false }
        assertEquals(emptyList(), get())
        assertEquals(0, requests)
    }

    @Test
    fun `a key changed during the cache read, the new one is sent`() {
        onClock = { values["pref_mdblist_api_key"] = "new-key" }
        get()
        assertEquals(listOf("/tmdb/movie/278/?apikey=new-key"), server.requests.toList())
    }

    @Test
    fun `two pages asking at once share one request`() = runBlocking {
        server.respond = { FakeMdbList.Reply(200, FakeMdbList.TITLE_BODY, delayMs = 300) }
        val both =
            withContext(Dispatchers.Default) {
                listOf(async { ratings.ratings(lookup) }, async { ratings.ratings(lookup) })
                    .awaitAll()
            }
        assertEquals(4, both[0].size)
        assertEquals(both[0], both[1])
        assertEquals(1, requests)
    }

    @Test
    fun `with a rejected key the cached ratings still show, and room is kept for them`() {
        val cached = get()
        now += MdbListRatings.FOUND_TTL_MS
        server.respond = { FakeMdbList.Reply(401, FakeMdbList.ERROR_BODY) }
        assertEquals(cached, get(), message = "stale ratings after the 401")
        assertTrue(ratings.isOn())
        assertFalse(ratings.canRequest(lookup))
        assertTrue(ratings.mightHaveRatings(lookup), message = "the cached title keeps its room")
        assertFalse(ratings.mightHaveRatings(other), message = "a title without ratings doesn't")
        assertEquals(cached, get())
        assertEquals(2, requests)
    }

    @Test
    fun `during a 429 wait a title with cached ratings keeps its room`() {
        get()
        now += MdbListRatings.FOUND_TTL_MS
        server.respond = { FakeMdbList.Reply(429, headers = mapOf("Retry-After" to "600")) }
        get()
        assertTrue(ratings.mightHaveRatings(lookup))
        assertFalse(ratings.mightHaveRatings(other))
    }

    private fun get(which: RatingsLookup = lookup) = runBlocking { ratings.ratings(which) }

    private val requests: Int
        get() = server.requests.size

    @Test
    fun `off sends nothing and shows nothing`() {
        values["pref_external_ratings"] = false
        assertEquals(emptyList(), get())
        assertFalse(ratings.isOn())
        assertFalse(ratings.mightHaveRatings(lookup))
        assertEquals(0, requests)
    }

    @Test
    fun `no key sends nothing`() {
        values["pref_mdblist_api_key"] = null
        assertEquals(emptyList(), get())
        values["pref_mdblist_api_key"] = "   "
        assertEquals(emptyList(), get())
        assertEquals(0, requests)
    }

    @Test
    fun `a second visit comes from the cache`() {
        val first = get()
        assertEquals(4, first.size)
        now += MdbListRatings.FOUND_TTL_MS - 1
        assertEquals(first, get())
        assertEquals(1, requests)
    }

    @Test
    fun `the cache expires after its TTL`() {
        get()
        now += MdbListRatings.FOUND_TTL_MS
        get()
        assertEquals(2, requests)
    }

    @Test
    fun `an unknown title is remembered for a day`() {
        server.respond = { FakeMdbList.Reply(404, """{"error": "Not found"}""") }
        assertEquals(emptyList(), get())
        now += MdbListRatings.EMPTY_TTL_MS - 1
        get()
        assertEquals(1, requests)
        now += 1
        get()
        assertEquals(2, requests)
    }

    @Test
    fun `a failure is not repeated for a while, and the cached ratings stay`() {
        val cached = get()
        now += MdbListRatings.FOUND_TTL_MS
        server.respond = { FakeMdbList.Reply(500, "") }
        assertEquals(cached, get(), message = "stale ratings on a failure")
        assertFalse(ratings.canRequest(lookup))
        assertEquals(cached, get())
        assertEquals(2, requests)
        now += MdbListRatings.FAILURE_BACKOFF_MS
        assertTrue(ratings.canRequest(lookup))
        get()
        assertEquals(3, requests)
    }

    @Test
    fun `a failure of one title doesn't hold back another`() {
        server.respond = { FakeMdbList.Reply(500, "") }
        get()
        server.respond = { FakeMdbList.Reply(200, FakeMdbList.TITLE_BODY) }
        assertEquals(4, get(other).size)
    }

    @Test
    fun `429 stops every request for Retry-After seconds`() {
        server.respond = { FakeMdbList.Reply(429, headers = mapOf("Retry-After" to "600")) }
        assertEquals(emptyList(), get())
        server.respond = { FakeMdbList.Reply(200, FakeMdbList.TITLE_BODY) }
        assertFalse(ratings.mightHaveRatings(other))
        assertEquals(emptyList(), get(other))
        now += 599_000
        get(other)
        assertEquals(1, requests)
        now += 1_000
        assertEquals(4, get(other).size)
        assertEquals(2, requests)
    }

    @Test
    fun `429 without Retry-After waits an hour`() {
        server.respond = { FakeMdbList.Reply(429) }
        get()
        now += MdbListRatings.DEFAULT_BACKOFF_MS - 1
        get(other)
        assertEquals(1, requests)
    }

    @Test
    fun `a rejected key isn't used again until it changes`() {
        server.respond = { FakeMdbList.Reply(401, FakeMdbList.ERROR_BODY) }
        get()
        assertFalse(ratings.canRequest(other))
        get(other)
        assertEquals(1, requests)
        values["pref_mdblist_api_key"] = "new-key"
        assertTrue(ratings.canRequest(other))
        get(other)
        assertEquals(2, requests)
    }

    @Test
    fun `the Test button clears a rejected key that works after all`() {
        server.respond = { FakeMdbList.Reply(401, FakeMdbList.ERROR_BODY) }
        get()
        server.respond = { FakeMdbList.Reply(200, """{"user_id": 1}""") }
        assertEquals(ExternalRatingsKeyStatus.VALID, runBlocking { ratings.check("key") })
        assertTrue(ratings.canRequest(lookup))
    }

    @Test
    fun `the Test button reports each answer`() {
        val cases =
            mapOf(
                FakeMdbList.Reply(200, """{"user_id": 1}""") to ExternalRatingsKeyStatus.VALID,
                FakeMdbList.Reply(401, FakeMdbList.ERROR_BODY) to ExternalRatingsKeyStatus.INVALID,
                FakeMdbList.Reply(403, FakeMdbList.ERROR_BODY) to ExternalRatingsKeyStatus.INVALID,
                FakeMdbList.Reply(429) to ExternalRatingsKeyStatus.RATE_LIMITED,
                FakeMdbList.Reply(500) to ExternalRatingsKeyStatus.UNREACHABLE,
                FakeMdbList.Reply(200, "not json") to ExternalRatingsKeyStatus.UNREACHABLE,
            )
        for ((reply, expected) in cases) {
            server.respond = { reply }
            assertEquals(expected, runBlocking { ratings.check("key") }, message = reply.toString())
        }
    }

    @Test
    fun `the key is sent only in the query, never in the cache`() {
        values["pref_mdblist_api_key"] = "very-secret"
        get()
        assertTrue(server.requests.single().endsWith("?apikey=very-secret"))
        val stored = cacheDir.walk().filter { it.isFile }.joinToString { it.readText() }
        assertFalse(stored.contains("very-secret"), message = stored)
    }

    private fun appPreferences(): AppPreferences {
        val preferences =
            Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getBoolean",
                    "getString" -> {
                        val key = args[0] as String
                        if (key in values) values[key] else args[1]
                    }
                    else -> throw NotImplementedError("SharedPreferences.${method.name}")
                }
            } as SharedPreferences
        return AppPreferences(preferences)
    }
}
