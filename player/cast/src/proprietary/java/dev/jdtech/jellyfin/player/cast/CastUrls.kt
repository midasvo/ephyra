package dev.jdtech.jellyfin.player.cast

import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.util.UUID

private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

private data class Origin(val scheme: String, val host: String, val port: Int)

/** Scheme, host and port of an http(s) URL, `null` for anything else or what cannot be parsed. */
private fun originOf(url: String): Origin? {
    val uri =
        try {
            URI(url)
        } catch (_: URISyntaxException) {
            return null
        }
    val scheme = uri.scheme?.lowercase() ?: return null
    val defaultPort =
        when (scheme) {
            "http" -> 80
            "https" -> 443
            else -> return null
        }
    // Not URI.host: it is null for a name URI does not take for a host, such as one with an
    // underscore in it.
    val hostAndPort = uri.rawAuthority?.substringAfterLast('@') ?: return null
    val host: String
    val port: String
    if (hostAndPort.startsWith("[")) {
        host = hostAndPort.substringBefore(']') + "]"
        val rest = hostAndPort.substringAfter(']', "")
        if (rest.isNotEmpty() && !rest.startsWith(":")) return null
        port = rest.removePrefix(":")
    } else {
        host = hostAndPort.substringBefore(':')
        port = hostAndPort.substringAfter(':', "")
    }
    if (host.isEmpty()) return null
    return Origin(
        scheme = scheme,
        host = host.lowercase(),
        port = if (port.isEmpty()) defaultPort else port.toIntOrNull() ?: return null,
    )
}

/**
 * [url] as the receiver can fetch it: a path on the server, or an absolute URL on the server
 * itself (same scheme, host and port as [baseUrl]). The receiver sits on the user's network and
 * fetches whatever it is given, so a URL that points anywhere else is not passed on.
 */
internal fun receiverUrl(baseUrl: String, url: String): String? {
    if (url.isEmpty()) return null
    val base = baseUrl.removeSuffix("/")
    // "//host/path" names a host as much as "https://host/path" does.
    if (url.startsWith("//")) {
        val absolute = base.substringBefore("://", "") + ":" + url
        return absolute.takeIf { originOf(it) != null && originOf(it) == originOf(base) }
    }
    if (!SCHEME.containsMatchIn(url)) return base + "/" + url.removePrefix("/")
    return url.takeIf { originOf(it) != null && originOf(it) == originOf(base) }
}

private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/** The file of [itemId] as it is, with the access token the receiver needs to fetch it. */
internal fun directPlayUrl(
    baseUrl: String,
    itemId: UUID,
    mediaSourceId: String?,
    accessToken: String?,
): String {
    val query =
        listOfNotNull(
                "static" to "true",
                mediaSourceId?.let { "mediaSourceId" to it },
                accessToken?.let { "api_key" to it },
            )
            .joinToString("&") { (name, value) -> "$name=${encode(value)}" }
    return "${baseUrl.removeSuffix("/")}/Videos/$itemId/stream?$query"
}

/**
 * What the receiver loads for [itemId]: the server's [transcodingUrl] when it gave one, which has
 * the access token in it, the file itself otherwise.
 */
internal fun castStreamUrl(
    baseUrl: String,
    itemId: UUID,
    mediaSourceId: String?,
    transcodingUrl: String?,
    accessToken: String?,
): String =
    if (!transcodingUrl.isNullOrBlank()) {
        baseUrl.removeSuffix("/") + "/" + transcodingUrl.removePrefix("/")
    } else {
        directPlayUrl(baseUrl, itemId, mediaSourceId, accessToken)
    }

/**
 * Direct play is MP4 only (see the device profiles). The source's own container is no help: the
 * server gives ffprobe's list for it, "mov,mp4,m4a,3gp,3g2,mj2".
 */
internal fun castContentType(isTranscoded: Boolean): String =
    if (isTranscoded) "application/x-mpegurl" else "video/mp4"

/**
 * The audio stream a stream URL was asked for, `null` when it names none. The audio track is part
 * of the stream the server was asked for, so this is how a session that is picked up again knows
 * which one plays.
 */
internal fun audioStreamIndexOf(streamUrl: String): Int? =
    streamUrl
        .substringBefore('#')
        .substringAfter('?', "")
        .split('&')
        .firstOrNull { it.substringBefore('=').equals("AudioStreamIndex", ignoreCase = true) }
        ?.substringAfter('=', "")
        ?.toIntOrNull()
