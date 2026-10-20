package com.sine.player.livetv

import java.util.Calendar
import java.util.TimeZone

/**
 * Catch-up (time-shifted replay) as IPTV playlists describe it:
 * `catchup="default|append|shift|flussonic|xc" catchup-days="7" catchup-source="…"`.
 */
data class CatchUp(val mode: String, val days: Int, val source: String?) {

    fun covers(programme: Programme, now: Long): Boolean =
        programme.startMs < now && programme.startMs >= now - days * 86_400_000L

    /** The URL that replays [programme] on a channel whose live URL is [liveUrl], or null if the mode is unknown. */
    fun urlFor(liveUrl: String, programme: Programme, now: Long, zone: TimeZone = TimeZone.getDefault()): String? {
        val start = programme.startMs
        val end = minOf(programme.endMs, now)
        return when (mode.lowercase()) {
            "default" -> source?.let { fill(it, start, end, now, zone) }
            "append" -> source?.let { liveUrl + fill(it, start, end, now, zone) }
            "shift", "timeshift" -> liveUrl + (if (liveUrl.contains('?')) "&" else "?") + "utc=${start / 1000}&lutc=${now / 1000}"
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> flussonic(liveUrl, start, end)
            "xc" -> xtream(liveUrl, start, end, zone)
            else -> source?.let { fill(it, start, end, now, zone) }
        }
    }

    companion object {
        /** Reads the catch-up attributes of an #EXTINF line (and the playlist-wide defaults). */
        fun from(attrs: Map<String, String>, defaults: Map<String, String> = emptyMap()): CatchUp? {
            val mode = attrs["catchup"] ?: attrs["catchup-type"] ?: defaults["catchup"] ?: defaults["catchup-type"]
            val source = attrs["catchup-source"] ?: defaults["catchup-source"]
            val days = (attrs["catchup-days"] ?: attrs["timeshift"] ?: defaults["catchup-days"] ?: defaults["timeshift"])?.toIntOrNull()
            // tvg-rec / timeshift alone imply "shift" with that many days.
            val effectiveMode = mode ?: if (attrs["tvg-rec"] != null || attrs["timeshift"] != null) "shift" else return null
            if (effectiveMode.equals("disabled", true)) return null
            val effectiveDays = days ?: attrs["tvg-rec"]?.toIntOrNull() ?: 1
            return CatchUp(effectiveMode, effectiveDays.coerceIn(1, 30), source?.ifEmpty { null })
        }

        private val placeholder = Regex("""\$?\{([a-zA-Z]+)(?::([^}]*))?\}""")

        /**
         * Fills `{utc}`, `{start}`, `{utcend}`, `{end}`, `{lutc}`, `{now}`, `{timestamp}`,
         * `{duration}` (seconds; `{duration:60}` divides), `{offset:60}`, `{Y}{m}{d}{H}{M}{S}`, and
         * formatted forms like `{utc:Y-m-d:H-M}` (letters Y m d H M S). `$` prefixes are accepted.
         */
        fun fill(template: String, startMs: Long, endMs: Long, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): String =
            placeholder.replace(template) { match ->
                val name = match.groupValues[1]
                val arg = match.groupValues[2]
                when (name.lowercase().let { if (name.length == 1) name else it }) {
                    "utc", "start" -> if (arg.isNotEmpty()) format(startMs, arg, zone) else (startMs / 1000).toString()
                    "utcend", "end" -> if (arg.isNotEmpty()) format(endMs, arg, zone) else (endMs / 1000).toString()
                    "lutc", "now", "timestamp" -> if (arg.isNotEmpty()) format(nowMs, arg, zone) else (nowMs / 1000).toString()
                    "duration" -> ((endMs - startMs) / 1000 / (arg.toLongOrNull()?.coerceAtLeast(1) ?: 1)).toString()
                    "offset" -> ((nowMs - startMs) / 1000 / (arg.toLongOrNull()?.coerceAtLeast(1) ?: 1)).toString()
                    "Y", "m", "d", "H", "M", "S" -> format(startMs, name, zone)
                    else -> match.value
                }
            }

        /** Y m d H M S → zero-padded fields of [ms] in [zone]; everything else is kept as is. */
        fun format(ms: Long, pattern: String, zone: TimeZone): String {
            val c = Calendar.getInstance(zone).apply { timeInMillis = ms }
            return buildString {
                pattern.forEach { ch ->
                    append(
                        when (ch) {
                            'Y' -> c.get(Calendar.YEAR).toString()
                            'm' -> "%02d".format(c.get(Calendar.MONTH) + 1)
                            'd' -> "%02d".format(c.get(Calendar.DAY_OF_MONTH))
                            'H' -> "%02d".format(c.get(Calendar.HOUR_OF_DAY))
                            'M' -> "%02d".format(c.get(Calendar.MINUTE))
                            'S' -> "%02d".format(c.get(Calendar.SECOND))
                            else -> ch.toString()
                        },
                    )
                }
            }
        }

        /** Flussonic: `…/index.m3u8` → `…/index-START-DURATION.m3u8`; `…/mpegts` → `…/timeshift_abs-START.ts`. */
        fun flussonic(liveUrl: String, startMs: Long, endMs: Long): String? {
            val query = liveUrl.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
            val path = liveUrl.substringBefore('?')
            val start = startMs / 1000
            val duration = (endMs - startMs) / 1000
            val dir = path.substringBeforeLast('/')
            val file = path.substringAfterLast('/')
            return when {
                file.endsWith(".m3u8") -> "$dir/${file.removeSuffix(".m3u8").let { if (it == "video" || it == "index" || it == "mono") it else "index" }}-$start-$duration.m3u8$query"
                file == "mpegts" || file.endsWith(".ts") -> "$dir/timeshift_abs-$start.ts$query"
                else -> null
            }
        }

        /** Xtream Codes: `http://h/live/user/pass/123.ts` → `http://h/timeshift/user/pass/MINUTES/Y-m-d:H-M/123.ts`. */
        fun xtream(liveUrl: String, startMs: Long, endMs: Long, zone: TimeZone): String? {
            val match = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(\.\w+)?$""").find(liveUrl.substringBefore('?')) ?: return null
            val (host, user, pass, id, ext) = match.destructured
            val minutes = ((endMs - startMs) / 60_000).coerceAtLeast(1)
            return "$host/timeshift/$user/$pass/$minutes/${format(startMs, "Y-m-d:H-M", zone)}/$id${ext.ifEmpty { ".ts" }}"
        }
    }
}
