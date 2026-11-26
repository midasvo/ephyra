package dev.jdtech.jellyfin.utils

import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.repository.DownloadText
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * What a download that could not start tells the user. Never the exception's own text: it can hold
 * URLs, hosts or stack fragments. CE (#530): the Jellyfin SDK wraps a refused or unreachable
 * connection in its own exception, so the cause chain is searched too; that read "Unknown error".
 */
fun downloadErrorText(e: Throwable): UiText =
    when {
        e is UnknownHostException || e is ConnectException ->
            UiText.StringResource(CoreR.string.download_error_server_unreachable)
        e is SocketTimeoutException -> UiText.StringResource(CoreR.string.download_error_timeout)
        LoadErrorText.isConnectionError(e) ->
            UiText.StringResource(CoreR.string.download_error_server_unreachable)
        e is IOException -> UiText.StringResource(CoreR.string.download_error_network)
        else -> UiText.StringResource(CoreR.string.unknown_error)
    }

/** Converts a port [DownloadText] from `application` into the Compose-ready [UiText]. */
fun DownloadText.toUiText(): UiText =
    when (this) {
        is DownloadText.Dynamic -> UiText.DynamicString(value)
        is DownloadText.Resource -> UiText.StringResource(resId, *args)
    }
