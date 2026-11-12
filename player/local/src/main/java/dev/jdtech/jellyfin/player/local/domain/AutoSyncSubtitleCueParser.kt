package dev.jdtech.jellyfin.player.local.domain

import dev.jdtech.jellyfin.player.core.domain.models.SubtitleSyncCue
import kotlin.math.max

/**
 * CE (#619): Parser for subtitle files (SRT, WebVTT, ASS/SSA, TTML) to extract timestamped
 * dialogue cues for smart subtitle synchronization.
 */
object AutoSyncSubtitleCueParser {
    private val timestampRegex = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})([.,](\d+))?""")
    private val assOverrideBlockRegex = Regex("""\{[^}]*\}""")
    private val assEventsHeaderRegex =
        Regex("""^[ \t]*\[Events\][ \t]*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    fun parse(text: String, sourceUrl: String? = null): List<SubtitleSyncCue> {
        val cleanedText = cleanText(text)
        return when (detectSubtitleFormat(sourceUrl, cleanedText)) {
            SubtitleFormatHint.WebVtt -> parseVtt(cleanedText)
            SubtitleFormatHint.Ass -> parseAss(cleanedText)
            SubtitleFormatHint.Ttml -> parseTtml(cleanedText)
            SubtitleFormatHint.Srt -> parseSrt(cleanedText)
        }
    }

    private fun cleanText(rawText: String): String =
        rawText
            .replace("\uFEFF", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')

    private enum class SubtitleFormatHint {
        Srt,
        WebVtt,
        Ass,
        Ttml,
    }

    private fun detectSubtitleFormat(sourceUrl: String?, text: String): SubtitleFormatHint {
        val sourcePath =
            sourceUrl
                ?.substringBefore('?')
                ?.substringBefore('#')
                ?.lowercase()
                .orEmpty()

        return when {
            sourcePath.endsWith(".vtt") ||
                sourcePath.endsWith(".webvtt") ||
                text.trimStart().startsWith("WEBVTT") -> SubtitleFormatHint.WebVtt
            sourcePath.endsWith(".ass") ||
                sourcePath.endsWith(".ssa") ||
                assEventsHeaderRegex.containsMatchIn(text) -> SubtitleFormatHint.Ass
            sourcePath.endsWith(".ttml") ||
                sourcePath.endsWith(".dfxp") ||
                sourcePath.endsWith(".xml") ||
                Regex("""<tt[\s>]""", RegexOption.IGNORE_CASE).containsMatchIn(text.take(512)) ->
                SubtitleFormatHint.Ttml
            else -> SubtitleFormatHint.Srt
        }
    }

    private fun parseSrt(text: String): List<SubtitleSyncCue> {
        val blocks = text.split(Regex("""\n\s*\n"""))
        val cues = mutableListOf<SubtitleSyncCue>()
        for (block in blocks) {
            val lines =
                block
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue

            var index = 0
            if (lines[index].all { it.isDigit() } && index + 1 < lines.size) {
                index++
            }
            val timing = lines.getOrNull(index) ?: continue
            if (!timing.contains("-->")) continue
            val (startTimeMs, endTimeMs) = parseStartEndTimeMs(timing) ?: continue
            if (endTimeMs - startTimeMs <= 0) continue
            val textLines = lines.drop(index + 1)
            val cueText = normalizeCueText(textLines.joinToString("\n"))
            if (cueText.isNotBlank()) {
                cues += SubtitleSyncCue(startTimeMs = startTimeMs, endTimeMs = endTimeMs, text = cueText)
            }
        }
        return cues
    }

    private fun parseVtt(text: String): List<SubtitleSyncCue> {
        val lines = text.lines().map { it.trimEnd() }
        val cues = mutableListOf<SubtitleSyncCue>()
        var cursor = 0

        while (cursor < lines.size) {
            val line = lines[cursor].trim()
            if (line.isBlank() || line.startsWith("WEBVTT") || line.startsWith("NOTE")) {
                cursor++
                continue
            }
            if (line == "STYLE" || line == "REGION") {
                while (cursor < lines.size && lines[cursor].isNotBlank()) {
                    cursor++
                }
                cursor++
                continue
            }

            var timingLine = line
            var textStart = cursor + 1
            if (!timingLine.contains("-->")) {
                timingLine = lines.getOrNull(cursor + 1)?.trim().orEmpty()
                textStart = cursor + 2
            }
            if (!timingLine.contains("-->")) {
                cursor++
                continue
            }

            val (startTimeMs, endTimeMs) = parseStartEndTimeMs(timingLine) ?: run {
                cursor++
                continue
            }
            if (endTimeMs - startTimeMs <= 0) {
                cursor++
                continue
            }

            val textParts = mutableListOf<String>()
            var i = textStart
            while (i < lines.size && lines[i].isNotBlank()) {
                textParts += lines[i].trim()
                i++
            }
            val cueText = normalizeCueText(textParts.joinToString("\n"))
            if (cueText.isNotBlank()) {
                cues += SubtitleSyncCue(startTimeMs = startTimeMs, endTimeMs = endTimeMs, text = cueText)
            }
            cursor = i + 1
        }

        return cues
    }

    private fun parseAss(text: String): List<SubtitleSyncCue> {
        var inEvents = false
        var formatFields: List<String>? = null
        val cues = mutableListOf<SubtitleSyncCue>()

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.startsWith("[") && line.endsWith("]")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue

            if (line.startsWith("Format:", ignoreCase = true)) {
                formatFields = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                continue
            }

            if (line.startsWith("Dialogue:", ignoreCase = true)) {
                val payload = line.substringAfter(':')
                val fields = formatFields ?: listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
                val parts = payload.split(',', limit = fields.size).map { it.trim() }

                val startIndex = fields.indexOf("start").takeIf { it >= 0 } ?: 1
                val endIndex = fields.indexOf("end").takeIf { it >= 0 } ?: 2
                val textIndex = fields.indexOf("text").takeIf { it >= 0 } ?: (fields.size - 1)

                if (parts.size > textIndex) {
                    val start = parseTimestampMs(parts[startIndex]) ?: continue
                    val end = parseTimestampMs(parts[endIndex]) ?: (start + 3000L)
                    val rawBody = parts[textIndex]
                    val body =
                        normalizeCueText(
                            rawBody
                                .replace(assOverrideBlockRegex, "")
                                .replace("\\N", "\n")
                                .replace("\\n", "\n")
                                .replace("\\h", " ")
                        )
                    if (body.isNotBlank()) {
                        cues += SubtitleSyncCue(startTimeMs = start, endTimeMs = end, text = body)
                    }
                }
            }
        }
        return cues.sortedBy { it.startTimeMs }
    }

    private fun parseTtml(text: String): List<SubtitleSyncCue> {
        val cues =
            Regex("""(?is)<p\b([^>]*)>(.*?)</p>""")
                .findAll(text)
                .mapNotNull { match ->
                    val attrs = match.groupValues[1]
                    val startRaw =
                        Regex("""\b(?:begin|start)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                            .find(attrs)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?: return@mapNotNull null
                    val endRaw =
                        Regex("""\bend\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                            .find(attrs)
                            ?.groupValues
                            ?.getOrNull(1)
                    val start = parseTimestampMs(startRaw) ?: return@mapNotNull null
                    val end = endRaw?.let { parseTimestampMs(it) } ?: (start + 3000L)
                    val body =
                        normalizeCueText(
                            match.groupValues[2].replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
                        )
                    if (body.isBlank()) null
                    else SubtitleSyncCue(startTimeMs = start, endTimeMs = end, text = body)
                }
                .toList()
        return cues.sortedBy { it.startTimeMs }
    }

    private fun parseStartEndTimeMs(timingLine: String): Pair<Long, Long>? {
        val parts = timingLine.split("-->")
        if (parts.size != 2) return null
        val startTimeMs = parseTimestampMs(parts[0].trim().substringBefore(' ')) ?: return null
        val endTimeMs = parseTimestampMs(parts[1].trim().substringBefore(' ')) ?: return null
        return startTimeMs to endTimeMs
    }

    private fun parseTimestampMs(rawTimestamp: String): Long? {
        val match = timestampRegex.matchEntire(rawTimestamp.trim()) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].toLongOrNull() ?: 0L
        val seconds = match.groupValues[3].toLongOrNull() ?: 0L
        val millisRaw = match.groupValues[5]
        val millis =
            when (millisRaw.length) {
                0 -> 0L
                1 -> "${millisRaw}00".toLong()
                2 -> "${millisRaw}0".toLong()
                else -> millisRaw.take(3).toLongOrNull() ?: 0L
            }
        return ((hours * 3600L) + (minutes * 60L) + seconds) * 1000L + millis
    }

    private fun normalizeCueText(text: String): String {
        return text
            .replace(Regex("""<(?:\d+:)?\d{1,2}:\d{2}(?:[.,]\d+)?>"""), "")
            .replace(Regex("""</?[a-zA-Z0-9._-]+(?: [^>]*)?>"""), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .lines()
            .map { it.replace(Regex("""[ \t]+"""), " ").trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }
}
