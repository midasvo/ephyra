package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.CollectionType

/**
 * CE (#775): the names of the stored libraries ([StoredLibraries]), from the app's strings (the
 * data module has none). #899: here, so `core` provides it without depending on data.
 */
fun interface StoredLibraryNames {
    fun nameOf(type: CollectionType): String

    companion object {
        val ENGLISH = StoredLibraryNames {
            if (it == CollectionType.Movies) "Movies" else "TV Shows"
        }
    }
}
