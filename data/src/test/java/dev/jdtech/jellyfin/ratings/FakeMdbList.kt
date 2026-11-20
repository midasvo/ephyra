package dev.jdtech.jellyfin.ratings

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * CE (#331): a stand-in for api.mdblist.com on localhost. No real key exists for the tests, so the
 * bodies below are written from the API reference (https://api.mdblist.com/docs/), not recorded.
 * [respond] answers each request; every request's path and query is kept in [requests].
 */
class FakeMdbList(var respond: (path: String) -> Reply = { Reply(200, TITLE_BODY) }) :
    AutoCloseable {
    data class Reply(
        val status: Int,
        val body: String = "",
        val headers: Map<String, String> = emptyMap(),
        val delayMs: Long = 0,
    )

    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val uri = exchange.requestURI
                requests += uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
                val reply = respond(uri.rawPath)
                if (reply.delayMs > 0) Thread.sleep(reply.delayMs)
                reply.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
                exchange.responseHeaders.add("Content-Type", "application/json")
                val bytes = reply.body.toByteArray()
                try {
                    exchange.sendResponseHeaders(
                        reply.status,
                        if (bytes.isEmpty()) -1 else bytes.size.toLong(),
                    )
                    if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
                } catch (_: Exception) {
                    // The client gave up (the timeout test).
                } finally {
                    exchange.close()
                }
            }
            start()
        }

    val baseUrl = "http://127.0.0.1:${server.address.port}/"

    /** A client for this server; a short read timeout for the timeout test. */
    fun client(readTimeoutMs: Long = 2_000) =
        MdbListClient(baseUrl.toHttpUrl()) {
            OkHttpClient.Builder().readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS).build()
        }

    override fun close() = server.stop(0)

    companion object {
        /**
         * A title as the reference describes `GET /{provider}/{type}/{id}`: `ratings` is a list of
         * `{source, value, score, votes, url}`, one per site, with `value` null where the site has
         * no score. The item fields of `ratings` are not in the reference's schema; they follow
         * what third-party clients read (`source`, `value`).
         */
        const val TITLE_BODY =
            """
            {
              "id": 278, "imdb_id": "tt0111161", "title": "The Shawshank Redemption",
              "year": 1994, "type": "movie", "score": 92,
              "ratings": [
                {"source": "imdb", "value": 9.3, "score": 93, "votes": 3000000, "url": "x"},
                {"source": "metacritic", "value": 82, "score": 82, "votes": 20},
                {"source": "metacriticuser", "value": 9.1, "score": 91},
                {"source": "trakt", "value": 91, "score": 91},
                {"source": "tomatoes", "value": 89, "score": 89, "votes": 80},
                {"source": "popcorn", "value": 98, "score": 98},
                {"source": "tmdb", "value": 87, "score": 87},
                {"source": "letterboxd", "value": 4.5, "score": 90},
                {"source": "rogerebert", "value": 4, "score": 100},
                {"source": "myanimelist", "value": null, "score": null}
              ],
              "streams": []
            }
            """

        /** A title most sites have no score for: nulls, a zero and a missing value. */
        const val SPARSE_BODY =
            """
            {
              "id": 1, "title": "Obscure", "type": "movie",
              "ratings": [
                {"source": "imdb", "value": 6.1, "score": 61, "votes": 120},
                {"source": "metacritic", "value": null, "score": null},
                {"source": "tomatoes", "value": 0, "score": 0},
                {"source": "letterboxd"}
              ]
            }
            """

        const val ERROR_BODY = """{"error": "Invalid API key!"}"""
    }
}
