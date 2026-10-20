package com.sine.player.livetv

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class Channel(
    /** Stable within Sine: source id + tvg-id/number/name. */
    val id: String,
    val name: String,
    val number: String?,
    val group: String?,
    val logo: String?,
    val url: String,
    /** XMLTV channel id (tvg-id) used to match the guide. */
    val guideId: String?,
    val sourceId: String,
)

data class Programme(
    val channel: String,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
) {
    fun isOn(now: Long) = now in startMs until endMs
}

data class M3uPlaylist(val channels: List<Channel>, val guideUrl: String?)

/** Extended M3U as used by IPTV providers: `#EXTINF:-1 tvg-id=".." tvg-logo=".." group-title="..",Name`. */
object M3u {
    private val attribute = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(text: String, sourceId: String): M3uPlaylist {
        var guideUrl: String? = null
        val channels = mutableListOf<Channel>()
        var info: String? = null
        var group: String? = null
        val ids = mutableMapOf<String, Int>()
        text.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.startsWith("#EXTM3U") -> {
                    val attrs = attribute.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    guideUrl = (attrs["url-tvg"] ?: attrs["x-tvg-url"])?.split(',')?.firstOrNull()?.trim()?.ifEmpty { null }
                }
                line.startsWith("#EXTINF") -> info = line
                line.startsWith("#EXTGRP:") -> group = line.substringAfter(':').trim()
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    val extinf = info.orEmpty()
                    val attrs = attribute.findAll(extinf).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val name = displayName(extinf).ifEmpty { attrs["tvg-name"].orEmpty() }.ifEmpty { line.substringAfterLast('/') }
                    val number = attrs["tvg-chno"] ?: attrs["channel-number"]
                    val guideId = attrs["tvg-id"]?.ifEmpty { null }
                    val base = "$sourceId:" + (guideId ?: number ?: name)
                    val count = ids.merge(base, 1, Int::plus)!!
                    channels += Channel(
                        id = if (count == 1) base else "$base#$count",
                        name = name,
                        number = number?.ifEmpty { null },
                        group = (attrs["group-title"] ?: group)?.ifEmpty { null },
                        logo = attrs["tvg-logo"]?.ifEmpty { null },
                        // Kodi-style "url|User-Agent=…" headers aren't supported; play the bare URL.
                        url = line.substringBefore('|'),
                        guideId = guideId,
                        sourceId = sourceId,
                    )
                    info = null
                    group = null
                }
            }
        }
        return M3uPlaylist(channels, guideUrl)
    }

    /** The display name follows the first comma outside quotes (names may contain commas). */
    fun displayName(extinf: String): String {
        var quoted = false
        extinf.forEachIndexed { i, c ->
            if (c == '"') quoted = !quoted
            if (c == ',' && !quoted) return extinf.substring(i + 1).trim()
        }
        return ""
    }
}

/** XMLTV guide data, streamed so a large multi-day guide doesn't have to fit in memory as text. */
object Xmltv {
    data class Guide(val names: Map<String, List<String>>, val programmes: List<Programme>)

    private val withZone = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US) }
    private val utc = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }

    /** "20261020183000 +0800" (zone optional, UTC assumed; seconds optional). */
    fun parseTime(text: String): Long? {
        val trimmed = text.trim()
        val digits = trimmed.takeWhile { it.isDigit() }.padEnd(14, '0').take(14)
        val zone = trimmed.substringAfter(' ', "").trim()
        return runCatching {
            if (zone.isNotEmpty()) withZone.get()!!.parse("$digits $zone")!!.time else utc.get()!!.parse(digits)!!.time
        }.getOrNull()
    }

    /** Keeps programmes that overlap [fromMs, toMs]. */
    fun parse(input: InputStream, fromMs: Long, toMs: Long): Guide {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        val names = mutableMapOf<String, MutableList<String>>()
        val programmes = mutableListOf<Programme>()
        var channelId: String? = null
        var programme: Triple<String, Long, Long>? = null
        var title: String? = null
        var subtitle: String? = null
        var description: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> channelId = parser.getAttributeValue(null, "id")
                    "display-name" -> channelId?.let { id -> names.getOrPut(id) { mutableListOf() } += parser.nextText().trim() }
                    "programme" -> {
                        val start = parser.getAttributeValue(null, "start")?.let(::parseTime)
                        val stop = parser.getAttributeValue(null, "stop")?.let(::parseTime)
                        val channel = parser.getAttributeValue(null, "channel")
                        programme = if (start != null && channel != null) Triple(channel, start, stop ?: (start + 30 * 60_000)) else null
                        title = null
                        subtitle = null
                        description = null
                    }
                    "title" -> if (programme != null && title == null) title = parser.nextText().trim()
                    "sub-title" -> if (programme != null && subtitle == null) subtitle = parser.nextText().trim()
                    "desc" -> if (programme != null && description == null) description = parser.nextText().trim()
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "channel" -> channelId = null
                    "programme" -> {
                        programme?.let { (channel, start, end) ->
                            if (end > fromMs && start < toMs) programmes += Programme(channel, start, end, title ?: "No information", subtitle, description)
                        }
                        programme = null
                    }
                }
            }
        }
        return Guide(names, programmes)
    }

    fun normalize(name: String) = name.lowercase(Locale.US).replace(Regex("""\b(hd|fhd|uhd|4k|sd)\b"""), "").replace(Regex("""[^\p{L}\p{N}]+"""), "")
}
