package dev.jdtech.jellyfin.models

/**
 * Joins the server address and a path the server sent (a transcoding or subtitle delivery URL) with
 * exactly one slash.
 *
 * The stored address of a server under a path ends with a slash (`https://host/jellyfin/`), one at
 * the root of a host does not, and the server's paths start with one. Joined as plain strings that
 * gave `/jellyfin//videos/...`, which Jellyfin answers with 404 (#444).
 */
fun serverUrl(baseUrl: String, path: String): String =
    baseUrl.trimEnd('/') + "/" + path.trimStart('/')
