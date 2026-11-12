package dev.jdtech.jellyfin.setup.presentation.login

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration

// CE (#375): the server's login disclaimer is HTML, as Jellyfin's web client renders it. It was
// shown as plain text, tags and all.

private val scriptOrStyle =
    Regex(
        "<(script|style)\\b[^>]*>.*?(</\\1\\s*>|$)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

// What `<img>` and other objects leave behind in the text.
private const val OBJECT_REPLACEMENT = '\uFFFC'

private val linkStyles =
    TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline))

/**
 * Turns the server's login disclaimer into styled text: line breaks and paragraphs, bold, italic,
 * underline and strikethrough, and http(s) links that open in the browser. Script and style blocks
 * go with their content, links with any other scheme become plain text, and the server's colours
 * and sizes are dropped. Returns null when nothing is left to show.
 */
fun loginDisclaimerText(html: String?): AnnotatedString? {
    if (html.isNullOrBlank()) return null
    val parsed = AnnotatedString.fromHtml(html.replace(scriptOrStyle, ""))
    val text = parsed.text

    // Drop the object characters, and map the parsed offsets onto the text that is left.
    val kept = IntArray(text.length + 1)
    for (i in text.indices) {
        kept[i + 1] = kept[i] + if (text[i] == OBJECT_REPLACEMENT) 0 else 1
    }
    val builder = AnnotatedString.Builder(text.filterNot { it == OBJECT_REPLACEMENT })
    for (range in parsed.spanStyles) {
        val style =
            SpanStyle(
                fontWeight = range.item.fontWeight,
                fontStyle = range.item.fontStyle,
                textDecoration = range.item.textDecoration,
            )
        if (style != SpanStyle()) builder.addStyle(style, kept[range.start], kept[range.end])
    }
    for (range in parsed.getLinkAnnotations(0, text.length)) {
        val url = (range.item as? LinkAnnotation.Url)?.url?.trim() ?: continue
        val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme == "http" || scheme == "https") {
            builder.addLink(LinkAnnotation.Url(url, linkStyles), kept[range.start], kept[range.end])
        }
    }
    val result = builder.toAnnotatedString()

    val start = result.text.indexOfFirst { !it.isWhitespace() }
    if (start == -1) return null
    val end = result.text.indexOfLast { !it.isWhitespace() } + 1
    return result.subSequence(start, end)
}
