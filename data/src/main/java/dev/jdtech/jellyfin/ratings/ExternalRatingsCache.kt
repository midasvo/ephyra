package dev.jdtech.jellyfin.ratings

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * CE (#331): MDBList's answers on disk, one small file per title in the app's cache directory, so a
 * second visit needs no request and the page still has its ratings when MDBList can't be reached.
 * An empty list is stored too: MDBList doesn't know the title, or has none of the shown scores.
 * Android may clear the directory when space runs out, which only costs a request.
 */
class ExternalRatingsCache(private val directory: File) {
    data class Entry(val fetchedAt: Long, val ratings: List<ExternalRating>)

    @Serializable private class StoredRating(val source: String, val value: Float)

    @Serializable private class Stored(val fetchedAt: Long, val ratings: List<StoredRating>)

    fun read(lookup: RatingsLookup): Entry? {
        val file = file(lookup)
        if (!file.isFile) return null
        return try {
            val stored = json.decodeFromString(Stored.serializer(), file.readText())
            Entry(
                fetchedAt = stored.fetchedAt,
                ratings =
                    stored.ratings.mapNotNull { rating ->
                        RatingSource.entries
                            .firstOrNull { it.name == rating.source }
                            ?.let { ExternalRating(it, rating.value) }
                    },
            )
        } catch (e: Exception) {
            Timber.w("Unreadable external ratings cache entry (%s)", e.javaClass.simpleName)
            file.delete()
            null
        }
    }

    fun write(lookup: RatingsLookup, ratings: List<ExternalRating>, fetchedAt: Long) {
        try {
            directory.mkdirs()
            val stored = Stored(fetchedAt, ratings.map { StoredRating(it.source.name, it.value) })
            val temp = File(directory, "${lookup.key}.tmp")
            temp.writeText(json.encodeToString(Stored.serializer(), stored))
            Files.move(temp.toPath(), file(lookup).toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Timber.w("Could not store external ratings (%s)", e.javaClass.simpleName)
        }
    }

    private fun file(lookup: RatingsLookup) = File(directory, "${lookup.key}.json")

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
