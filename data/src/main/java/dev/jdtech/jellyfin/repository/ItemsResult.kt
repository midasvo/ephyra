package dev.jdtech.jellyfin.repository

/**
 * CE (#779): a list read that Room can answer while the server is away (favourites, libraries,
 * search), with where it came from. [fromStore] is true when Room answered: the server was known to
 * be away, or the request got no answer. The screens say "downloads only" from this, not from what
 * they knew of the server when the load started, which can trail by a probe.
 *
 * It belongs to the repository, which composes the server with Room; the server's own reads (#756's
 * catalog port) know nothing of Room and keep returning plain lists.
 */
data class ItemsResult<out T>(val items: List<T>, val fromStore: Boolean = false)
