package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.UserId

/**
 * #966: rows a store has read, made into models only when [load] is called. The stored fallback
 * reads the rows before it asks the server, so it knows whether it has an answer at all, and maps
 * them (the user's data, the local sources: more queries) only when the server does not answer.
 */
interface StoredRows<out T> {
    val size: Int

    /** At most [limit] of the rows from [startIndex] (null: all of them), still not mapped. */
    fun slice(startIndex: Int, limit: Int?): StoredRows<T>

    /** The rows as models, with [userId]'s user data, in the order they were read. */
    suspend fun load(userId: UserId): List<T>
}

/** #966: one row a store has read, made into a model only when [load] is called. */
fun interface StoredRow<out T> {
    suspend fun load(userId: UserId): T
}
