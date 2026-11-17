package dev.jdtech.jellyfin.player.local.domain

import android.annotation.SuppressLint
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

/**
 * Spike (#668): ASS/SSA subtitles drawn by libass (nextlib) in the Media3 player. On while the
 * system property `debug.ephyra.libass` is `1`:
 *
 *     adb shell setprop debug.ephyra.libass 1     # then open the player again
 */
@SuppressLint("PrivateApi")
fun libassSpikeEnabled(): Boolean =
    try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, "debug.ephyra.libass") == "1"
    } catch (_: Exception) {
        false
    }

/** media3's parsers without the ASS/SSA one, so a script reaches libass as it is. */
@OptIn(UnstableApi::class)
internal class WithoutSsaParserFactory(
    private val delegate: SubtitleParser.Factory = DefaultSubtitleParserFactory()
) : SubtitleParser.Factory by delegate {
    override fun supportsFormat(format: Format): Boolean =
        format.sampleMimeType != MimeTypes.TEXT_SSA && delegate.supportsFormat(format)
}
