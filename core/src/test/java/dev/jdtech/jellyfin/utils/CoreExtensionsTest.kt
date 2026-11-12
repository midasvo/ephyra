package dev.jdtech.jellyfin.utils

import java.util.Locale
import org.jellyfin.sdk.model.DateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreExtensionsTest {
    @Test
    fun `format formats date following provided locale`() {
        val dateTime = DateTime.of(1961, 1, 25, 0, 0)
        val formattedUs = dateTime.format(Locale.US)
        val formattedNl = dateTime.format(Locale.forLanguageTag("nl-NL"))

        assertEquals("Jan 25, 1961", formattedUs)
        assertEquals("25 jan 1961", formattedNl)
    }

    /** #414: a two-digit year made The Great Train Robbery (1903) read as 2003. */
    @Test
    fun `format keeps the four-digit year`() {
        val dateTime = DateTime.of(1903, 12, 7, 0, 0)

        assertEquals("Dec 7, 1903", dateTime.format(Locale.US))
        for (tag in listOf("en-US", "nl", "de", "ja", "ar", "es-419")) {
            val formatted = dateTime.format(Locale.forLanguageTag(tag))
            assertTrue("$tag: $formatted", "1903" in formatted)
        }
    }
}
