package dev.jdtech.jellyfin.utils

import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.UiText
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.junit.Assert.assertEquals
import org.junit.Test

/** CE (#530): a download that cannot start because the server is out of reach says so. */
class DownloadErrorTextTest {
    private fun resId(error: Throwable) = (downloadErrorText(error) as UiText.StringResource).resId

    @Test
    fun `a connection the Jellyfin SDK wrapped says the server can't be reached`() {
        val error = TimeoutException("Connection failed", ConnectException("Failed to connect"))

        assertEquals(CoreR.string.download_error_server_unreachable, resId(error))
    }

    @Test
    fun `a plain refused connection says the server can't be reached`() {
        assertEquals(
            CoreR.string.download_error_server_unreachable,
            resId(ConnectException("Failed to connect")),
        )
    }

    @Test
    fun `a read timeout and another IO error keep their own text`() {
        assertEquals(CoreR.string.download_error_timeout, resId(SocketTimeoutException()))
        assertEquals(CoreR.string.download_error_network, resId(IOException("reset")))
    }

    @Test
    fun `anything else is an unknown error`() {
        assertEquals(CoreR.string.unknown_error, resId(IllegalStateException("No sources")))
    }
}
