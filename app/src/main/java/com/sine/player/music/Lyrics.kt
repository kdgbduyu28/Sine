package com.sine.player.music

/** One line of synced lyrics. */
data class LyricLine(val timeMs: Long, val text: String)

/** Parses `.lrc` files: `[mm:ss.xx]` stamps (several per line allowed) and an optional `[offset:±ms]`. */
object Lyrics {
    private val stamp = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val offsetTag = Regex("""(?i)^\s*\[offset:\s*([+-]?\d+)\s*]""")

    fun parse(text: String): List<LyricLine> {
        var offset = 0L
        val lines = mutableListOf<LyricLine>()
        text.lineSequence().forEach { raw ->
            offsetTag.find(raw)?.let { offset = it.groupValues[1].toLong(); return@forEach }
            val stamps = mutableListOf<Long>()
            var rest = raw.trim()
            while (true) {
                val match = stamp.find(rest) ?: break
                if (match.range.first != 0) break
                val minutes = match.groupValues[1].toLong()
                val seconds = match.groupValues[2].toLong()
                val fraction = match.groupValues[3]
                val millis = when (fraction.length) {
                    0 -> 0L
                    1 -> fraction.toLong() * 100
                    2 -> fraction.toLong() * 10
                    else -> fraction.take(3).toLong()
                }
                stamps += minutes * 60_000 + seconds * 1000 + millis
                rest = rest.substring(match.range.last + 1)
            }
            val lyric = rest.trim()
            // A positive offset in LRC means the lyrics should appear earlier.
            stamps.forEach { lines += LyricLine((it - offset).coerceAtLeast(0), lyric) }
        }
        return lines.sortedBy { it.timeMs }
    }

    /** Index of the line being sung at [positionMs], or -1 before the first line. */
    fun currentIndex(lines: List<LyricLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) / 2
            if (lines[mid].timeMs <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }
}
