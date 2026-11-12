package dev.jdtech.jellyfin.utils

import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.UiText
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.SecureConnectionException
import org.jellyfin.sdk.api.client.exception.TimeoutException

/**
 * CE (#377): what a screen that failed to load tells the user. Never the exception's own text: it
 * holds class names, hosts and addresses, and the user can't act on it.
 */
object LoadErrorText {
    /** The reason in plain words. */
    fun reason(error: Throwable): UiText {
        val status = error.causes().filterIsInstance<InvalidStatusException>().firstOrNull()
        return when {
            status != null -> UiText.StringResource(CoreR.string.ce_error_server_status, status.status)
            isConnectionError(error) ->
                UiText.StringResource(CoreR.string.download_error_server_unreachable)
            else -> UiText.StringResource(CoreR.string.unknown_error)
        }
    }

    /** Whether the server could not be reached at all, so checking the connection may help. */
    fun isConnectionError(error: Throwable): Boolean =
        error.causes().any {
            it is TimeoutException ||
                it is SecureConnectionException ||
                it is SocketException ||
                it is SocketTimeoutException ||
                it is UnknownHostException ||
                it is SSLException
        } && error.causes().none { it is InvalidStatusException }

    /** The full trace for a bug report, with anything that looks like a token blanked out. */
    fun shareText(error: Throwable): String = redact(error.stackTraceToString())

    private val secret =
        Regex(
            """((?:api_?key|access_?token|token)["']?\s*[=:]\s*["']?)[^\s&"',;]+""",
            RegexOption.IGNORE_CASE,
        )

    fun redact(text: String): String = text.replace(secret) { "${it.groupValues[1]}******" }

    private fun Throwable.causes(): Sequence<Throwable> =
        generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }.take(16)
}
