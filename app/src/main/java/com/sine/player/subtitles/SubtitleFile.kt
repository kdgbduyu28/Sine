package com.sine.player.subtitles

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import kotlin.math.roundToLong

/** One timed line of a subtitle file. */
data class Cue(val startMs: Long, val endMs: Long, val text: String)

enum class SubtitleFormat { SRT, VTT, ASS }

/**
 * Reads, retimes and writes text subtitles (SRT, WebVTT, ASS/SSA). Pure Kotlin so the sync and
 * translation logic can be unit tested.
 */
object SubtitleFile {

    fun format(fileName: String, text: String): SubtitleFormat = when {
        fileName.endsWith(".ass", true) || fileName.endsWith(".ssa", true) || text.contains("[Script Info]") -> SubtitleFormat.ASS
        fileName.endsWith(".vtt", true) || text.trimStart('\uFEFF', ' ', '\n', '\r').startsWith("WEBVTT") -> SubtitleFormat.VTT
        else -> SubtitleFormat.SRT
    }

    /** Subtitle files come in UTF-8 or an old Windows code page; try UTF-8 strictly first. */
    fun decode(bytes: ByteArray): String {
        val strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            strict.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
        return text.removePrefix("\uFEFF")
    }

    // region Parsing

    private val srtTime = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")
    private val vttTime = Regex("""(?:(\d{1,2}):)?(\d{2}):(\d{2})\.(\d{3})""")
    private val assTime = Regex("""(\d{1,2}):(\d{2}):(\d{2})\.(\d{2})""")
    private val assOverride = Regex("""\{[^}]*}""")
    private val htmlTag = Regex("""</?[a-zA-Z][^>]*>""")

    fun parse(text: String, format: SubtitleFormat): List<Cue> = when (format) {
        SubtitleFormat.SRT, SubtitleFormat.VTT -> parseBlocks(text)
        SubtitleFormat.ASS -> parseAss(text)
    }.sortedBy { it.startMs }

    private fun parseBlocks(text: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.contains("-->")) {
                val (startText, endText) = line.split("-->", limit = 2).map { it.trim() }
                val start = timestamp(startText)
                val end = timestamp(endText.substringBefore(' '))
                i++
                val body = StringBuilder()
                while (i < lines.size && lines[i].isNotBlank()) {
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(lines[i])
                    i++
                }
                if (start != null && end != null) cues += Cue(start, end, clean(body.toString()))
            } else {
                i++
            }
        }
        return cues
    }

    private fun timestamp(text: String): Long? {
        srtTime.find(text)?.let { m ->
            return m.groupValues.let { g -> g[1].toLong() * 3_600_000 + g[2].toLong() * 60_000 + g[3].toLong() * 1000 + millis(g[4]) }
        }
        vttTime.find(text)?.let { m ->
            val g = m.groupValues
            return (g[1].ifEmpty { "0" }.toLong()) * 3_600_000 + g[2].toLong() * 60_000 + g[3].toLong() * 1000 + g[4].toLong()
        }
        return null
    }

    private fun millis(fraction: String) = when (fraction.length) {
        1 -> fraction.toLong() * 100
        2 -> fraction.toLong() * 10
        else -> fraction.take(3).toLong()
    }

    private fun parseAss(text: String): List<Cue> {
        var fields = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        var inEvents = false
        val cues = mutableListOf<Cue>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("[") -> inEvents = line.equals("[Events]", true)
                inEvents && line.startsWith("Format:", true) ->
                    fields = line.substringAfter(':').split(',').map { it.trim().lowercase(Locale.US) }
                inEvents && line.startsWith("Dialogue:", true) -> {
                    val parts = line.substringAfter(':').split(',', limit = fields.size)
                    val start = parts.getOrNull(fields.indexOf("start"))?.let(::assTimestamp)
                    val end = parts.getOrNull(fields.indexOf("end"))?.let(::assTimestamp)
                    val body = parts.getOrNull(fields.indexOf("text")).orEmpty()
                    if (start != null && end != null) cues += Cue(start, end, clean(body.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")))
                }
            }
        }
        return cues
    }

    private fun assTimestamp(text: String): Long? = assTime.find(text.trim())?.groupValues?.let { g ->
        g[1].toLong() * 3_600_000 + g[2].toLong() * 60_000 + g[3].toLong() * 1000 + g[4].toLong() * 10
    }

    private fun clean(text: String) = text.replace(assOverride, "").replace(htmlTag, "").trim()

    // endregion

    // region Writing and retiming

    /** New time for an old one: `old × ratio + offset`. */
    data class Retime(val offsetMs: Long, val ratio: Double = 1.0) {
        fun apply(ms: Long): Long = (ms * ratio + offsetMs).roundToLong().coerceAtLeast(0)
    }

    /**
     * Rewrites every timestamp in [text] and leaves everything else (styles, positioning, tags)
     * exactly as it was.
     */
    fun retime(text: String, format: SubtitleFormat, retime: Retime): String = when (format) {
        SubtitleFormat.SRT -> text.lineSequence().joinToString("\n") { line ->
            if (!line.contains("-->")) line else srtTime.replace(line) { m -> formatSrt(retime.apply(timestamp(m.value)!!)) }
        }
        SubtitleFormat.VTT -> text.lineSequence().joinToString("\n") { line ->
            if (!line.contains("-->")) line else vttTime.replace(line) { m -> formatVtt(retime.apply(timestamp(m.value)!!)) }
        }
        SubtitleFormat.ASS -> text.lineSequence().joinToString("\n") { line ->
            if (!line.trimStart().startsWith("Dialogue:", true)) {
                line
            } else {
                // Only the first two timestamps on a Dialogue line are times; text may contain digits.
                var count = 0
                assTime.replace(line) { m ->
                    count++
                    if (count > 2) m.value else formatAss(retime.apply(assTimestamp(m.value)!!))
                }
            }
        }
    }

    fun toSrt(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { i, cue ->
            append(i + 1).append('\n')
            append(formatSrt(cue.startMs)).append(" --> ").append(formatSrt(cue.endMs)).append('\n')
            append(cue.text).append("\n\n")
        }
    }

    fun formatSrt(ms: Long): String = String.format(Locale.US, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)

    private fun formatVtt(ms: Long): String = String.format(Locale.US, "%02d:%02d:%02d.%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)

    private fun formatAss(ms: Long): String = String.format(Locale.US, "%d:%02d:%02d.%02d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000 / 10)

    /** The cue showing at [positionMs], or null. */
    fun at(cues: List<Cue>, positionMs: Long): List<Cue> = cues.filter { positionMs >= it.startMs && positionMs < it.endMs }

    // endregion
}
