package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.toItemId

/**
 * CE (#775): the two libraries the Media tab shows while the server is away, holding the downloaded
 * movies and shows. Room does not know which server library a download came from. The server does
 * not know these ids, so whatever asks for their contents gets Room's answer. #966: here, beside
 * [StoredItemLists], which lists them.
 */
object StoredLibraries {
    val MOVIES: ItemId = "a1b2c3d4-0000-0000-0000-000000000001".toItemId()
    val SHOWS: ItemId = "a1b2c3d4-0000-0000-0000-000000000002".toItemId()

    fun isStored(id: ItemId?): Boolean = id == MOVIES || id == SHOWS
}
