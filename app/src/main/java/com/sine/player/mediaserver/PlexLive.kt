package com.sine.player.mediaserver

import com.sine.player.data.RemoteServer
import com.sine.player.livetv.Channel
import com.sine.player.livetv.Programme
import com.sine.player.net.Net
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Plex DVR Live TV: DVRs from /livetv/dvrs, their lineup's channels, the guide grid, and tuning a
 * channel, which starts a Plex HLS session. Written from Plex's (undocumented) web-client API and
 * not yet tried against a real Plex tuner.
 */
class PlexLive(private val server: RemoteServer, private val deviceId: String) {
    private val base = server.address.trimEnd('/')

    data class Dvr(val key: String, val lineup: String?, val epgIdentifier: String?)

    fun dvrs(): List<Dvr> = parseDvrs(get("/livetv/dvrs"))

    /** Channels of every DVR; [Channel.url] is a plexlive:// URI tuned when opened. */
    fun channels(sourceId: String): List<Channel> = dvrs().flatMap { dvr ->
        val lineup = dvr.lineup ?: return@flatMap emptyList()
        parseChannels(get("/livetv/epg/lineupchannels?lineup=${enc(lineup)}"), server.id, dvr.key, sourceId, base, server.token)
    }.distinctBy { it.id }

    /** Guide for every DVR's EPG, keyed like [Channel.guideId]. */
    fun guide(fromMs: Long, toMs: Long): List<Programme> = dvrs().mapNotNull { it.epgIdentifier }.distinct().flatMap { epg ->
        runCatching {
            parseGrid(get("/$epg/grid?type=1,4&beginsAt%3C=${toMs / 1000}&endsAt%3E=${fromMs / 1000}"))
        }.getOrDefault(emptyList())
    }

    /** Starts a live session for [channelVcn] on [dvrKey]; returns the absolute HLS playlist URL. */
    fun tune(dvrKey: String, channelVcn: String): String {
        val json = Net.json(request("/livetv/dvrs/${enc(dvrKey)}/channels/${enc(channelVcn)}/tune").post(ByteArray(0).toRequestBody(null)).build())
        val path = findSessionPath(json) ?: error("Plex didn't return a live session (is a tuner free?)")
        val playlist = if (path.contains(".m3u8")) path else path.trimEnd('/') + "/index.m3u8"
        return absolute(playlist, base, server.token)
    }

    /** Fetches a playlist with the Plex headers. */
    fun fetch(url: String): String = Net.text(PlexClient.headers(Request.Builder().url(url), deviceId).header("X-Plex-Token", server.token).get().build())

    private fun get(path: String): JSONObject = Net.json(request(path).get().build())

    private fun request(path: String) = PlexClient.headers(Request.Builder().url(base + path), deviceId).header("X-Plex-Token", server.token)

    companion object {
        const val SCHEME = "plexlive"

        private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

        private fun array(container: JSONObject?, name: String): List<JSONObject> {
            val a = container?.optJSONArray(name) ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        }

        fun parseDvrs(json: JSONObject): List<Dvr> = array(json.optJSONObject("MediaContainer"), "Dvr").map { d ->
            Dvr(d.optString("key").ifEmpty { d.optString("uuid") }, d.optString("lineup").ifEmpty { null }, d.optString("epgIdentifier").ifEmpty { null })
        }.filter { it.key.isNotEmpty() }

        /** Channels sit under MediaContainer.Lineup[].Channel[] or directly under MediaContainer.Channel[]. */
        fun parseChannels(json: JSONObject, serverId: String, dvrKey: String, sourceId: String, base: String, token: String): List<Channel> {
            val container = json.optJSONObject("MediaContainer")
            val raw = array(container, "Lineup").flatMap { array(it, "Channel") } + array(container, "Channel")
            return raw.mapNotNull { c ->
                val vcn = c.optString("channelVcn").ifEmpty { c.optString("vcn") }.ifEmpty { return@mapNotNull null }
                val identifier = c.optString("identifier").ifEmpty { null }
                Channel(
                    id = "$sourceId:$dvrKey:$vcn",
                    name = c.optString("title").ifEmpty { c.optString("callSign") }.ifEmpty { vcn },
                    number = vcn,
                    group = null,
                    logo = c.optString("thumb").ifEmpty { null }?.let { absolute(it, base, token) },
                    url = "$SCHEME://$serverId/${URLEncoder.encode(dvrKey, "UTF-8")}/${URLEncoder.encode(vcn, "UTF-8")}/index.m3u8",
                    guideId = identifier ?: vcn,
                    sourceId = sourceId,
                )
            }
        }

        /** Grid items: one programme per Media entry (a show can air on several channels). */
        fun parseGrid(json: JSONObject): List<Programme> = array(json.optJSONObject("MediaContainer"), "Metadata").flatMap { m ->
            val show = m.optString("grandparentTitle").ifEmpty { null }
            val title = show ?: m.optString("title").ifEmpty { "No information" }
            val episode = if (show != null) m.optString("title").ifEmpty { null } else null
            array(m, "Media").mapNotNull { media ->
                val channel = media.optString("channelIdentifier").ifEmpty { media.optString("channelVcn") }.ifEmpty { return@mapNotNull null }
                val start = media.optLong("beginsAt") * 1000
                val end = media.optLong("endsAt") * 1000
                if (start <= 0 || end <= start) return@mapNotNull null
                Programme(channel, start, end, title, episode, m.optString("summary").ifEmpty { null })
            }
        }

        /** The first `/livetv/sessions/…` path anywhere in the tune response. */
        fun findSessionPath(value: Any?): String? = when (value) {
            is String -> value.takeIf { it.contains("/livetv/sessions/") }
            is JSONObject -> value.keys().asSequence().firstNotNullOfOrNull { findSessionPath(value.opt(it)) }
            is JSONArray -> (0 until value.length()).firstNotNullOfOrNull { findSessionPath(value.opt(it)) }
            else -> null
        }

        fun absolute(pathOrUrl: String, base: String, token: String): String {
            val url = if (pathOrUrl.startsWith("http")) pathOrUrl else base + (if (pathOrUrl.startsWith("/")) "" else "/") + pathOrUrl
            return if (url.contains("X-Plex-Token=")) url else url + (if (url.contains('?')) "&" else "?") + "X-Plex-Token=" + enc(token)
        }

        /**
         * Makes every URI in an HLS playlist absolute (with the token), so the player can fetch
         * segments and variant playlists straight from Plex even though it opened plexlive://.
         */
        fun rewritePlaylist(text: String, playlistUrl: String, token: String): String {
            val dir = playlistUrl.substringBefore('?').substringBeforeLast('/') + "/"
            val origin = Regex("""^(https?://[^/]+)""").find(playlistUrl)?.value.orEmpty()
            fun resolve(uri: String): String {
                val full = when {
                    uri.startsWith("http://") || uri.startsWith("https://") -> uri
                    uri.startsWith("/") -> origin + uri
                    else -> dir + uri
                }
                return if (full.contains("X-Plex-Token=")) full else full + (if (full.contains('?')) "&" else "?") + "X-Plex-Token=" + enc(token)
            }
            return text.lineSequence().joinToString("\n") { line ->
                val trimmed = line.trim()
                when {
                    trimmed.isEmpty() -> line
                    trimmed.startsWith("#") -> Regex("""URI="([^"]+)"""").replace(line) { "URI=\"${resolve(it.groupValues[1])}\"" }
                    else -> resolve(trimmed)
                }
            }
        }
    }
}
