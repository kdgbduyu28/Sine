package com.sine.player.mediaserver

import com.sine.player.BuildConfig
import com.sine.player.data.RemoteServer
import com.sine.player.net.Net
import com.sine.player.source.MediaEntry
import com.sine.player.source.RemoteProgress
import com.sine.player.source.RemoteSubtitle
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

data class PlexPin(val id: Long, val code: String)

/** A server from plex.tv's resource list, with the address Sine should use on the LAN. */
data class PlexResource(val name: String, val address: String, val token: String)

/** Plex Media Server and the plex.tv sign-in endpoints. */
class PlexClient(private val server: RemoteServer, private val deviceId: String) {
    private val base = server.address.trimEnd('/')

    fun libraries(): List<MediaEntry> = parseSections(get("/library/sections"), server)

    fun children(key: String): List<MediaEntry> = parseMetadata(get(key), server, base)

    fun item(ratingKey: String): MediaEntry? =
        parseMetadata(get("/library/metadata/${enc(ratingKey)}"), server, base).firstOrNull()

    fun report(ratingKey: String, positionMs: Long, durationMs: Long, state: ProgressState) {
        val plexState = when (state) {
            ProgressState.PLAYING -> "playing"
            ProgressState.PAUSED -> "paused"
            ProgressState.STOPPED -> "stopped"
        }
        val path = "/:/timeline?ratingKey=${enc(ratingKey)}&key=${enc("/library/metadata/$ratingKey")}" +
            "&state=$plexState&time=$positionMs&duration=$durationMs&identifier=com.plexapp.plugins.library"
        Net.execute(request(path).get().build()).close()
        if (state == ProgressState.STOPPED && durationMs > 0 && positionMs >= durationMs * 0.92) {
            Net.execute(request("/:/scrobble?identifier=com.plexapp.plugins.library&key=${enc(ratingKey)}").get().build()).close()
        }
    }

    private fun get(path: String): JSONObject = Net.json(request(path).get().build())

    private fun request(path: String) = headers(Request.Builder().url(base + path), deviceId).header("X-Plex-Token", server.token)

    companion object {
        private const val PLEX_TV = "https://plex.tv"

        fun headers(builder: Request.Builder, deviceId: String): Request.Builder = builder
            .header("Accept", "application/json")
            .header("X-Plex-Client-Identifier", deviceId)
            .header("X-Plex-Product", "Sine")
            .header("X-Plex-Version", BuildConfig.VERSION_NAME)
            .header("X-Plex-Platform", "Android")
            .header("X-Plex-Device", "Android TV")
            .header("X-Plex-Device-Name", "Sine")

        /** Starts plex.tv/link sign-in; show [PlexPin.code] and poll [checkPin]. */
        fun createPin(deviceId: String): PlexPin {
            val json = Net.json(headers(Request.Builder().url("$PLEX_TV/api/v2/pins?strong=false"), deviceId).post(FormBody.Builder().build()).build())
            return PlexPin(json.getLong("id"), json.getString("code"))
        }

        /** The account token once the code has been entered at plex.tv/link, else null. */
        fun checkPin(pin: PlexPin, deviceId: String): String? {
            val json = Net.json(headers(Request.Builder().url("$PLEX_TV/api/v2/pins/${pin.id}"), deviceId).get().build())
            return json.optString("authToken").takeIf { it.isNotBlank() && it != "null" }
        }

        fun signIn(username: String, password: String, deviceId: String): String {
            val form = FormBody.Builder().add("login", username).add("password", password).build()
            val json = Net.json(headers(Request.Builder().url("$PLEX_TV/api/v2/users/signin"), deviceId).post(form).build())
            return json.getString("authToken")
        }

        /** Servers on the account, so the address can be filled in after a PIN sign-in. */
        fun servers(accountToken: String, deviceId: String): List<PlexResource> {
            val array = Net.jsonArray(
                headers(Request.Builder().url("$PLEX_TV/api/v2/resources?includeHttps=1&includeRelay=0"), deviceId)
                    .header("X-Plex-Token", accountToken)
                    .get().build(),
            )
            return parseResources(array, accountToken)
        }

        fun parseResources(array: JSONArray, accountToken: String): List<PlexResource> =
            (0 until array.length()).map { array.getJSONObject(it) }
                .filter { it.optString("provides").split(',').contains("server") }
                .mapNotNull { res ->
                    val connections = res.optJSONArray("connections") ?: JSONArray()
                    val list = (0 until connections.length()).map { connections.getJSONObject(it) }
                    // Plain http to the LAN address avoids plex.direct DNS, which many routers block.
                    val local = list.firstOrNull { it.optBoolean("local") && !it.optBoolean("relay") }
                    val address = when {
                        local != null -> "http://${local.optString("address")}:${local.optInt("port", 32400)}"
                        list.isNotEmpty() -> list.first().optString("uri")
                        else -> return@mapNotNull null
                    }
                    PlexResource(res.optString("name"), address, res.optString("accessToken").ifBlank { accountToken })
                }

        fun parseSections(json: JSONObject, server: RemoteServer): List<MediaEntry> {
            val dirs = json.optJSONObject("MediaContainer")?.optJSONArray("Directory") ?: JSONArray()
            return (0 until dirs.length()).map { dirs.getJSONObject(it) }
                .filter { it.optString("type") in setOf("movie", "show") }
                .map { dir ->
                    val name = dir.optString("title")
                    MediaEntry(name, MediaServerUris.folder(server, "/library/sections/${dir.optString("key")}/all", name), isDirectory = true)
                }
        }

        fun parseMetadata(json: JSONObject, server: RemoteServer, base: String): List<MediaEntry> {
            val items = json.optJSONObject("MediaContainer")?.optJSONArray("Metadata") ?: JSONArray()
            return (0 until items.length()).mapNotNull { parseItem(items.getJSONObject(it), server, base) }
        }

        fun parseItem(item: JSONObject, server: RemoteServer, base: String): MediaEntry? {
            val ratingKey = item.optString("ratingKey").ifEmpty { return null }
            val name = displayName(item)
            when (item.optString("type")) {
                "show", "season" -> return MediaEntry(name, MediaServerUris.folder(server, item.optString("key"), name), isDirectory = true)
                "movie", "episode", "clip" -> Unit
                else -> return null
            }
            val part = item.optJSONArray("Media")?.optJSONObject(0)?.optJSONArray("Part")?.optJSONObject(0) ?: return null
            val partKey = part.optString("key").ifEmpty { return null }
            val token = enc(server.token)
            val duration = item.optLong("duration")
            return MediaEntry(
                name = name,
                uri = MediaServerUris.item(server, ratingKey),
                isDirectory = false,
                size = part.optLong("size"),
                playUri = "$base$partKey?X-Plex-Token=$token",
                playable = true,
                subtitles = externalSubtitles(part, base, token),
                remote = RemoteProgress(
                    positionMs = item.optLong("viewOffset"),
                    durationMs = duration,
                    lastPlayedAt = item.optLong("lastViewedAt") * 1000,
                    played = item.optInt("viewCount") > 0 && item.optLong("viewOffset") == 0L,
                ),
            )
        }

        fun displayName(item: JSONObject): String {
            val title = item.optString("title")
            return when (item.optString("type")) {
                "episode" -> {
                    val season = item.optInt("parentIndex", -1)
                    val episode = item.optInt("index", -1)
                    if (season >= 0 && episode >= 0) String.format(Locale.US, "S%02dE%02d · %s", season, episode, title) else title
                }
                "movie" -> item.optInt("year", 0).takeIf { it > 0 }?.let { "$title ($it)" } ?: title
                else -> title
            }
        }

        /** Sidecar subtitles have a `key`; embedded ones don't and play from the file itself. */
        private fun externalSubtitles(part: JSONObject, base: String, token: String): List<RemoteSubtitle> {
            val streams = part.optJSONArray("Stream") ?: return emptyList()
            return (0 until streams.length()).map { streams.getJSONObject(it) }
                .filter { it.optInt("streamType") == 3 && it.optString("key").isNotEmpty() }
                .mapNotNull { s ->
                    val format = when (val codec = s.optString("codec").lowercase(Locale.US)) {
                        "srt", "subrip" -> "srt"
                        "ass", "ssa", "vtt" -> codec
                        "webvtt" -> "vtt"
                        else -> return@mapNotNull null
                    }
                    RemoteSubtitle(
                        url = "$base${s.optString("key")}?X-Plex-Token=$token",
                        label = s.optString("displayTitle").ifBlank { "External" },
                        language = s.optString("languageCode").ifBlank { null },
                        mimeType = null,
                        forced = s.optBoolean("forced"),
                        fileName = "subtitle.$format",
                    )
                }
        }

        private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    }
}
