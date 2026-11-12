package dev.jdtech.jellyfin.ce.presentation.film

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The language in the season page's audio chip (#415). */
class AudioLanguageNameTest {
    @Test
    fun `an undetermined language has no name`() {
        assertNull(audioLanguageName("und", Locale.ENGLISH))
        assertNull(audioLanguageName("UND", Locale.ENGLISH))
    }

    @Test
    fun `no language has no name`() {
        assertNull(audioLanguageName("", Locale.ENGLISH))
        assertNull(audioLanguageName(" ", Locale.ENGLISH))
    }

    @Test
    fun `a known code is named in the given language`() {
        assertEquals("English", audioLanguageName("eng", Locale.ENGLISH))
        assertEquals("Dutch", audioLanguageName("nld", Locale.ENGLISH))
        assertEquals("Engels", audioLanguageName("en", Locale.forLanguageTag("nl")))
    }
}
