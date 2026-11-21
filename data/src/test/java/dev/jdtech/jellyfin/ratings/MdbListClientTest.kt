package dev.jdtech.jellyfin.ratings

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** CE (#331): [MdbListClient] against [FakeMdbList], one test per kind of answer. */
class MdbListClientTest {
    private val server = FakeMdbList()
    private val lookup = RatingsLookup("tmdb", "movie", "278")

    @AfterEach fun stop() = server.close()

    private fun title(readTimeoutMs: Long = 2_000) = runBlocking {
        server.client(readTimeoutMs).title(lookup, "secret-key")
    }

    @Test
    fun `asks for the documented route with the key as apikey`() {
        title()
        assertEquals(listOf("/tmdb/movie/278/?apikey=secret-key"), server.requests.toList())
    }

    @Test
    fun `keeps the shown sites in a fixed order`() {
        val result = title()
        assertEquals(
            MdbListClient.Result.Found(
                listOf(
                    ExternalRating(RatingSource.IMDB, 9.3f),
                    ExternalRating(RatingSource.ROTTEN_TOMATOES, 89f),
                    ExternalRating(RatingSource.METACRITIC, 82f),
                    ExternalRating(RatingSource.LETTERBOXD, 4.5f),
                )
            ),
            result,
        )
    }

    @Test
    fun `drops sites without a score`() {
        server.respond = { FakeMdbList.Reply(200, FakeMdbList.SPARSE_BODY) }
        assertEquals(
            MdbListClient.Result.Found(listOf(ExternalRating(RatingSource.IMDB, 6.1f))),
            title(),
        )
    }

    @Test
    fun `a title without ratings is found with none`() {
        server.respond = { FakeMdbList.Reply(200, """{"id": 1, "title": "x"}""") }
        assertEquals(MdbListClient.Result.Found(emptyList()), title())
    }

    @Test
    fun `401 and 403 are an invalid key`() {
        server.respond = { FakeMdbList.Reply(401, FakeMdbList.ERROR_BODY) }
        assertEquals(MdbListClient.Result.InvalidKey, title())
        server.respond = { FakeMdbList.Reply(403, FakeMdbList.ERROR_BODY) }
        assertEquals(MdbListClient.Result.InvalidKey, title())
    }

    @Test
    fun `404 is not found`() {
        server.respond = { FakeMdbList.Reply(404, """{"error": "Not found"}""") }
        assertEquals(MdbListClient.Result.NotFound, title())
    }

    @Test
    fun `429 passes Retry-After on`() {
        server.respond = {
            FakeMdbList.Reply(
                429,
                """{"error": "API rate limit exceeded!"}""",
                headers = mapOf("Retry-After" to "120"),
            )
        }
        assertEquals(MdbListClient.Result.RateLimited(120), title())
    }

    @Test
    fun `429 without Retry-After`() {
        server.respond = { FakeMdbList.Reply(429, """{"error": "Daily API limit exceeded!"}""") }
        assertEquals(MdbListClient.Result.RateLimited(null), title())
    }

    @Test
    fun `a timeout fails`() {
        server.respond = { FakeMdbList.Reply(200, FakeMdbList.TITLE_BODY, delayMs = 1_500) }
        assertEquals(MdbListClient.Result.Failed, title(readTimeoutMs = 300))
    }

    @Test
    fun `a malformed body fails`() {
        for (body in listOf("<html>oops</html>", "[]", """{"ratings": {"imdb": 7}}""", "")) {
            server.respond = { FakeMdbList.Reply(200, body) }
            assertEquals(MdbListClient.Result.Failed, title(), message = body)
        }
    }

    @Test
    fun `a server error fails`() {
        server.respond = { FakeMdbList.Reply(502, "Bad gateway") }
        assertEquals(MdbListClient.Result.Failed, title())
    }

    @Test
    fun `nothing reachable fails`() {
        val url = server.baseUrl
        server.close()
        val client = MdbListClient(url.toHttpUrl())
        assertEquals(MdbListClient.Result.Failed, runBlocking { client.title(lookup, "k") })
    }

    @Test
    fun `a redirect is not followed, so the key stays with MDBList`() {
        FakeMdbList().use { elsewhere ->
            server.respond = {
                FakeMdbList.Reply(302, headers = mapOf("Location" to elsewhere.baseUrl + "steal"))
            }
            assertEquals(MdbListClient.Result.Failed, title())
            assertTrue(elsewhere.requests.isEmpty(), message = elsewhere.requests.toString())
        }
    }

    @Test
    fun `the key check asks for user`() {
        server.respond = { FakeMdbList.Reply(200, """{"user_id": 1, "username": "u"}""") }
        val result = runBlocking { server.client().user("secret-key") }
        assertEquals(MdbListClient.Result.Found(emptyList()), result)
        assertEquals(listOf("/user?apikey=secret-key"), server.requests.toList())
    }

    @Test
    fun `the key check turns a 401 into an invalid key`() {
        server.respond = { FakeMdbList.Reply(401, FakeMdbList.ERROR_BODY) }
        assertEquals(MdbListClient.Result.InvalidKey, runBlocking { server.client().user("wrong") })
    }
}
