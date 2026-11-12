package dev.jdtech.jellyfin.settings.presentation.models

import org.junit.Assert.assertEquals
import org.junit.Test

class NumberInputTextTest {
    private val strings = mapOf(PREFIX to "every", SUFFIX to "episodes", OFF to "Off")

    /** English plural rule: "1 episode", every other number "n episodes". */
    private fun quantityString(id: Int, quantity: Int): String {
        check(id == PLURAL)
        return if (quantity == 1) "$quantity episode" else "$quantity episodes"
    }

    private fun text(
        value: Long,
        prefixRes: Int? = null,
        suffixRes: Int? = SUFFIX,
        suffixPluralsRes: Int? = null,
        zeroLabelRes: Int? = null,
    ) =
        numberInputText(
            value = value,
            prefixRes = prefixRes,
            suffixRes = suffixRes,
            string = { strings.getValue(it) },
            quantityString = ::quantityString,
            suffixPluralsRes = suffixPluralsRes,
            zeroLabelRes = zeroLabelRes,
        )

    @Test
    fun `a plural gives one episode and several episodes`() {
        assertEquals("1 episode", text(1, suffixPluralsRes = PLURAL))
        assertEquals("3 episodes", text(3, suffixPluralsRes = PLURAL))
    }

    @Test
    fun `the zero label stands for 0`() {
        assertEquals("Off", text(0, suffixPluralsRes = PLURAL, zeroLabelRes = OFF))
        assertEquals("2 episodes", text(2, suffixPluralsRes = PLURAL, zeroLabelRes = OFF))
    }

    @Test
    fun `without a zero label 0 goes through the plural`() {
        assertEquals("0 episodes", text(0, suffixPluralsRes = PLURAL))
    }

    @Test
    fun `without a plural the suffix is joined as before`() {
        assertEquals("1 episodes", text(1))
        assertEquals("every 5 episodes", text(5, prefixRes = PREFIX))
        assertEquals("every 1 episode", text(1, prefixRes = PREFIX, suffixPluralsRes = PLURAL))
    }

    @Test
    fun `a value that is not loaded shows no number`() {
        assertEquals("episodes", text(-1, suffixPluralsRes = PLURAL))
        assertEquals("", text(-1, suffixRes = null))
    }

    private companion object {
        const val PREFIX = 1
        const val SUFFIX = 2
        const val OFF = 3
        const val PLURAL = 4
    }
}
