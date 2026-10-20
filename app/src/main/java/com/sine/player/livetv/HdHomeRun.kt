package com.sine.player.livetv

import com.sine.player.net.Net
import okhttp3.Request
import org.json.JSONArray

/**
 * SiliconDust HDHomeRun network tuners: found through SiliconDust's discovery service (devices
 * behind the same public IP) or by address; channels from lineup.json; guide from their guide API.
 */
object HdHomeRun {
    data class Device(val id: String, val name: String, val baseUrl: String, val lineupUrl: String, val deviceAuth: String?)

    fun discover(): List<Device> {
        val list = Net.jsonArray(Request.Builder().url("https://ipv4-api.hdhomerun.com/discover").build())
        return (0 until list.length()).mapNotNull { i ->
            val o = list.getJSONObject(i)
            val base = o.optString("BaseURL").ifEmpty { o.optString("LocalIP").takeIf { it.isNotEmpty() }?.let { "http://$it" } } ?: return@mapNotNull null
            // Storage-only boxes (DVR) have no lineup.
            if (!o.has("LineupURL") && !o.has("DeviceID")) return@mapNotNull null
            runCatching { device(base) }.getOrNull()
        }
    }

    /** [address] is an IP, host name or base URL. */
    fun device(address: String): Device {
        val base = (if (address.startsWith("http")) address else "http://$address").trimEnd('/')
        val o = Net.json(Request.Builder().url("$base/discover.json").build())
        return Device(
            id = o.optString("DeviceID").ifEmpty { base },
            name = o.optString("FriendlyName").ifEmpty { "HDHomeRun" } + (o.optString("ModelNumber").takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""),
            baseUrl = o.optString("BaseURL").ifEmpty { base },
            lineupUrl = o.optString("LineupURL").ifEmpty { "$base/lineup.json" },
            deviceAuth = o.optString("DeviceAuth").ifEmpty { null },
        )
    }

    fun channels(device: Device, sourceId: String): List<Channel> {
        val lineup = Net.jsonArray(Request.Builder().url(device.lineupUrl).build())
        return parseLineup(lineup, sourceId)
    }

    fun parseLineup(lineup: JSONArray, sourceId: String): List<Channel> = (0 until lineup.length()).mapNotNull { i ->
        val o = lineup.getJSONObject(i)
        if (o.optInt("DRM") == 1) return@mapNotNull null
        val number = o.optString("GuideNumber").ifEmpty { return@mapNotNull null }
        Channel(
            id = "$sourceId:$number",
            name = o.optString("GuideName").ifEmpty { number },
            number = number,
            group = if (o.optInt("HD") == 1) "HD" else null,
            logo = null,
            url = o.optString("URL"),
            guideId = number,
            sourceId = sourceId,
        )
    }

    /** About a day of guide data, keyed by GuideNumber like [channels]. Needs the device's DeviceAuth. */
    fun guide(device: Device): Pair<Map<String, String>, List<Programme>> {
        val auth = device.deviceAuth ?: return emptyMap<String, String>() to emptyList()
        val array = Net.jsonArray(Request.Builder().url("https://api.hdhomerun.com/api/guide.php?DeviceAuth=$auth").build())
        val logos = mutableMapOf<String, String>()
        val programmes = mutableListOf<Programme>()
        for (i in 0 until array.length()) {
            val channel = array.getJSONObject(i)
            val number = channel.optString("GuideNumber")
            channel.optString("ImageURL").takeIf { it.isNotEmpty() }?.let { logos[number] = it }
            val entries = channel.optJSONArray("Guide") ?: continue
            for (j in 0 until entries.length()) {
                val p = entries.getJSONObject(j)
                programmes += Programme(
                    channel = number,
                    startMs = p.optLong("StartTime") * 1000,
                    endMs = p.optLong("EndTime") * 1000,
                    title = p.optString("Title").ifEmpty { "No information" },
                    subtitle = listOfNotNull(p.optString("EpisodeNumber").ifEmpty { null }, p.optString("EpisodeTitle").ifEmpty { null }).joinToString(" · ").ifEmpty { null },
                    description = p.optString("Synopsis").ifEmpty { null },
                )
            }
        }
        return logos to programmes
    }
}
