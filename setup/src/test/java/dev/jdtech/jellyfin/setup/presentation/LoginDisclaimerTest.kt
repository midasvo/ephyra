package dev.jdtech.jellyfin.setup.presentation

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import dev.jdtech.jellyfin.setup.presentation.login.loginDisclaimerText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CE (#375): the server's login disclaimer is HTML. It showed its tags, such as `<br/>`, as text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LoginDisclaimerTest {
    private fun AnnotatedString.links() =
        getLinkAnnotations(0, length).map {
            text.substring(it.start, it.end) to (it.item as LinkAnnotation.Url).url
        }

    private fun AnnotatedString.styleOf(part: String) =
        text.indexOf(part).let { start ->
            spanStyles.filter { it.start <= start && it.end >= start + part.length }
        }

    @Test
    fun `the demo server's line breaks and entities become text`() {
        val text =
            loginDisclaimerText(
                "For testing and evaluation.<br/>Fish &amp; chips.<br/><p>A paragraph.</p>"
            )!!

        assertEquals("For testing and evaluation.\nFish & chips.\nA paragraph.", text.text)
    }

    @Test
    fun `bold, italic, underline and strikethrough stay`() {
        val text = loginDisclaimerText("<b>bold</b> <i>italic</i> <u>under</u> <s>struck</s>")!!

        assertEquals("bold italic under struck", text.text)
        assertEquals(FontWeight.Bold, text.styleOf("bold").single().item.fontWeight)
        assertEquals(FontStyle.Italic, text.styleOf("italic").single().item.fontStyle)
        assertEquals(TextDecoration.Underline, text.styleOf("under").single().item.textDecoration)
        assertEquals(
            TextDecoration.LineThrough,
            text.styleOf("struck").single().item.textDecoration,
        )
    }

    @Test
    fun `the server's colours and sizes are dropped`() {
        val text =
            loginDisclaimerText(
                "<font color=\"#ff0000\">red</font> <big>big</big> <h1>Title</h1>" +
                    "<span style=\"color:blue;background-color:yellow\">blue</span>"
            )!!

        for (range in text.spanStyles) {
            assertEquals(Color.Unspecified, range.item.color)
            assertEquals(Color.Unspecified, range.item.background)
            assertEquals(TextUnit.Unspecified, range.item.fontSize)
        }
        assertTrue(text.paragraphStyles.isEmpty())
    }

    @Test
    fun `http and https links stay, every other scheme becomes plain text`() {
        val text =
            loginDisclaimerText(
                listOf(
                        "<a href=\"https://jellyfin.org\">secure</a>",
                        "<a href=\"http://example.com\">plain</a>",
                        "<a href=\"javascript:alert(1)\">script</a>",
                        "<a href=\" JavaScript:alert(1)\">mixed</a>",
                        "<a href=\"intent://scan/#Intent;scheme=zxing;end\">intent</a>",
                        "<a href=\"file:///sdcard/secret.txt\">file</a>",
                        "<a href=\"content://x/y\">content</a>",
                        "<a href=\"relative/page\">relative</a>",
                        "<a>none</a>",
                    )
                    .joinToString(" ")
            )!!

        assertEquals("secure plain script mixed intent file content relative none", text.text)
        assertEquals(
            listOf("secure" to "https://jellyfin.org", "plain" to "http://example.com"),
            text.links(),
        )
        for (range in text.getLinkAnnotations(0, text.length)) {
            val styles = (range.item as LinkAnnotation.Url).styles
            assertEquals(TextDecoration.Underline, styles?.style?.textDecoration)
            assertEquals(Color.Unspecified, styles?.style?.color ?: Color.Unspecified)
        }
    }

    @Test
    fun `script and style blocks go with their content`() {
        val text =
            loginDisclaimerText(
                "Before<script>alert('x')</script> middle<SCRIPT type=\"text/javascript\">" +
                    "document.cookie</SCRIPT> <style>p { color: red }</style>after" +
                    "<script>never closed"
            )!!

        assertEquals("Before middle after", text.text)
    }

    @Test
    fun `images, unknown tags and objects leave only their text`() {
        val text =
            loginDisclaimerText(
                "<img src=\"https://x/y.png\">Text <unknown>kept</unknown> <iframe>framed</iframe>"
            )!!

        assertFalse(text.text.contains('\uFFFC'))
        assertEquals("Text kept framed", text.text)
    }

    @Test
    fun `nested, unclosed and broken tags do not crash`() {
        val inputs =
            listOf(
                "<b><i>nested</b></i> after",
                "<b>never closed",
                "<a href=\"https://jellyfin.org\">open link",
                "<div><p><b><i><u>deep",
                "trailing <b",
                "</b></i></a> closers only",
                "<<<>>> &bogus; &#xZZ; &",
                "<a href=\"https://a\"><a href=\"https://b\">twice</a></a>",
            )
        for (input in inputs) {
            assertNotNull(input, loginDisclaimerText(input))
        }
        assertEquals("nested after", loginDisclaimerText("<b><i>nested</b></i> after")!!.text)
    }

    @Test
    fun `a blank result hides the disclaimer`() {
        assertNull(loginDisclaimerText(null))
        assertNull(loginDisclaimerText(""))
        assertNull(loginDisclaimerText("   \n"))
        assertNull(loginDisclaimerText("<br/><p> </p>"))
        assertNull(loginDisclaimerText("<script>only a script</script>"))
        assertNull(loginDisclaimerText("<img src=\"https://x/y.png\">"))
    }

    @Test(timeout = 10_000)
    fun `a very long disclaimer does not hang`() {
        val unit = "<b>Line</b> &amp; <a href=\"https://jellyfin.org\">link</a><br/>"
        val html = unit.repeat(10_000 / unit.length + 1).take(10_000)
        assertNotNull(loginDisclaimerText(html))

        // Many opened scripts that never close, the worst case for the block pattern.
        assertNull(loginDisclaimerText("<script>".repeat(1_250)))
        loginDisclaimerText("x<script ".repeat(1_000))
    }
}
