package dev.jdtech.jellyfin.settings.presentation.models

/**
 * CE (#400): the text a number setting shows for [value], on the phone and on TV.
 *
 * A zero label stands for 0 where 0 turns the setting off or lifts a limit ("Off", "No limit").
 * A plural puts the number in the word ("1 episode", "2 episodes"). Otherwise the number goes
 * between the prefix and the suffix, as before. A negative value (not loaded yet) shows no number.
 *
 * [string] and [quantityString] look up a string and a plural formatted with the quantity, so the
 * rule can be tested without Android resources.
 */
fun numberInputText(
    value: Long,
    prefixRes: Int?,
    suffixRes: Int?,
    string: (id: Int) -> String,
    quantityString: (id: Int, quantity: Int) -> String,
    suffixPluralsRes: Int? = null,
    zeroLabelRes: Int? = null,
): String {
    if (value == 0L && zeroLabelRes != null) return string(zeroLabelRes)
    val prefix = prefixRes?.let(string)
    if (value >= 0 && suffixPluralsRes != null) {
        return listOfNotNull(prefix, quantityString(suffixPluralsRes, value.toInt()))
            .joinToString(" ")
    }
    return listOfNotNull(prefix, value.takeIf { it >= 0 }?.toString(), suffixRes?.let(string))
        .joinToString(" ")
}
