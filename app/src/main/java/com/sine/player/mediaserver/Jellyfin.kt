package com.sine.player.mediaserver

import com.sine.player.BuildConfig
import com.sine.player.data.RemoteServer
import com.sine.player.data.ServerKind
import com.sine.player.net.Net
import com.sine.player.source.MediaEntry
import com.sine.player.source.RemoteProgress
import com.sine.player.source.RemoteSubtitle
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class SignIn(val token: String, val userId: String, val serverName: String?)

/**
 * Jellyfin and Emby share most of their REST API (Jellyfin forked from Emby). Emby serves it
 * under `/emby` and wants `X-Emby-Authorization`; Jellyfin takes `Authorization`.
 */
class JellyfinClient(private val server: RemoteServer, private val deviceId: String) {
    private val emby = server.kind == ServerKind.EMBY
    private val base = apiBase(server.address, emby)

    fun libraries(): List<MediaEntry> {
        val json = get("/Users/${server.userId}/Views")
        return parseLibraries(json, server)
    }

    fun children(parentId: String): List<MediaEntry> {
        val json = get(
            "/Users/${server.userId}/Items?ParentId=${enc(parentId)}" +
                "&SortBy=ParentIndexNumber,IndexNumber,SortName&SortOrder=Ascending" +
                "&Fields=MediaSources,MediaStreams,Path,DateCreated&EnableImages=false&EnableTotalRecordCount=false",
        )
        return parseItems(json, server, base)
    }

    fun item(itemId: String): MediaEntry? {
        val json = get("/Users/${server.userId}/Items/${enc(itemId)}?Fields=MediaSources,MediaStreams,Path")
        return parseItem(json, server, base)
    }

    fun report(itemId: String, positionMs: Long, state: ProgressState, started: Boolean) {
        val body = JSONObject()
            .put("ItemId", itemId)
            .put("PositionTicks", positionMs * TICKS_PER_MS)
            .put("IsPaused", state == ProgressState.PAUSED)
            .put("CanSeek", true)
            .put("PlayMethod", "DirectPlay")
        val path = when {
            state == ProgressState.STOPPED -> "/Sessions/Playing/Stopped"
            !started -> "/Sessions/Playing"
            else -> "/Sessions/Playing/Progress"
        }
        Net.execute(request(path).post(body.toString().toRequestBody(JSON)).build()).close()
    }

    private fun get(path: String): JSONObject = Net.json(request(path).get().build())

    private fun request(path: String) = Request.Builder()
        .url(base + path)
        .header(if (emby) "X-Emby-Authorization" else "Authorization", authorization(deviceId, server.token))
        .header("X-Emby-Token", server.token)
        .header("Accept", "application/json")

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        const val TICKS_PER_MS = 10_000L

        fun apiBase(address: String, emby: Boolean): String {
            val trimmed = address.trimEnd('/')
            return if (emby && !trimmed.endsWith("/emby", ignoreCase = true)) "$trimmed/emby" else trimmed
        }

        fun authorization(deviceId: String, token: String?): String = buildString {
            append("MediaBrowser Client=\"Sine\", Device=\"Android TV\", DeviceId=\"$deviceId\", Version=\"${BuildConfig.VERSION_NAME}\"")
            if (!token.isNullOrBlank()) append(", Token=\"$token\"")
        }

        fun signIn(address: String, kind: ServerKind, username: String, password: String, deviceId: String): SignIn {
            val emby = kind == ServerKind.EMBY
            val body = JSONObject().put("Username", username).put("Pw", password)
            val request = Request.Builder()
                .url(apiBase(address, emby) + "/Users/AuthenticateByName")
                .header(if (emby) "X-Emby-Authorization" else "Authorization", authorization(deviceId, null))
                .header("Accept", "application/json")
                .post(body.toString().toRequestBody(JSON))
                .build()
            val json = Net.json(request)
            val serverName = runCatching {
                Net.json(Request.Builder().url(apiBase(address, emby) + "/System/Info/Public").build()).optString("ServerName")
            }.getOrNull()
            return SignIn(
                token = json.getString("AccessToken"),
                userId = json.getJSONObject("User").getString("Id"),
                serverName = serverName?.ifBlank { null },
            )
        }

        fun parseLibraries(json: JSONObject, server: RemoteServer): List<MediaEntry> {
            val items = json.optJSONArray("Items") ?: JSONArray()
            return (0 until items.length()).map { items.getJSONObject(it) }
                // Music, photo and book libraries have nothing Sine can play.
                .filter { it.optString("CollectionType") !in setOf("music", "photos", "books", "playlists", "livetv") }
                .map { lib ->
                    val name = lib.optString("Name")
                    MediaEntry(name, MediaServerUris.folder(server, lib.getString("Id"), name), isDirectory = true)
                }
        }

        fun parseItems(json: JSONObject, server: RemoteServer, base: String): List<MediaEntry> {
            val items = json.optJSONArray("Items") ?: JSONArray()
            return (0 until items.length()).mapNotNull { parseItem(items.getJSONObject(it), server, base) }
        }

        fun parseItem(item: JSONObject, server: RemoteServer, base: String): MediaEntry? {
            val id = item.optString("Id").ifEmpty { return null }
            val name = displayName(item)
            if (item.optBoolean("IsFolder")) {
                return MediaEntry(name, MediaServerUris.folder(server, id, name), isDirectory = true)
            }
            if (item.optString("MediaType") != "Video") return null
            val source = item.optJSONArray("MediaSources")?.optJSONObject(0)
            val sourceId = source?.optString("Id")?.ifEmpty { null } ?: id
            val container = source?.optString("Container")?.substringBefore(',')?.ifEmpty { null }
            val stream = buildString {
                append("$base/Videos/$id/stream")
                if (container != null) append(".$container")
                append("?static=true&MediaSourceId=${enc(sourceId)}&api_key=${enc(server.token)}")
            }
            val userData = item.optJSONObject("UserData")
            val durationMs = item.optLong("RunTimeTicks") / TICKS_PER_MS
            return MediaEntry(
                name = name,
                uri = MediaServerUris.item(server, id),
                isDirectory = false,
                size = source?.optLong("Size") ?: 0,
                playUri = stream,
                playable = true,
                subtitles = externalSubtitles(source, base, id, sourceId, server.token),
                remote = userData?.let {
                    RemoteProgress(
                        positionMs = it.optLong("PlaybackPositionTicks") / TICKS_PER_MS,
                        durationMs = durationMs,
                        lastPlayedAt = parseDate(it.optString("LastPlayedDate")),
                        played = it.optBoolean("Played"),
                    )
                },
            )
        }

        /** "S01E02 · Pilot" for episodes, "Title (2019)" for films. */
        fun displayName(item: JSONObject): String {
            val name = item.optString("Name")
            return when (item.optString("Type")) {
                "Episode" -> {
                    val season = item.optInt("ParentIndexNumber", -1)
                    val episode = item.optInt("IndexNumber", -1)
                    if (season >= 0 && episode >= 0) String.format(Locale.US, "S%02dE%02d · %s", season, episode, name) else name
                }
                "Movie" -> item.optInt("ProductionYear", 0).takeIf { it > 0 }?.let { "$name ($it)" } ?: name
                else -> name
            }
        }

        private fun externalSubtitles(source: JSONObject?, base: String, itemId: String, sourceId: String, token: String): List<RemoteSubtitle> {
            val streams = source?.optJSONArray("MediaStreams") ?: return emptyList()
            return (0 until streams.length()).map { streams.getJSONObject(it) }
                .filter { it.optString("Type") == "Subtitle" && it.optBoolean("IsExternal") }
                .mapNotNull { s ->
                    val format = when (s.optString("Codec").lowercase(Locale.US)) {
                        "subrip", "srt" -> "srt"
                        "ass" -> "ass"
                        "ssa" -> "ssa"
                        "webvtt", "vtt" -> "vtt"
                        else -> return@mapNotNull null
                    }
                    RemoteSubtitle(
                        url = "$base/Videos/$itemId/${enc(sourceId)}/Subtitles/${s.optInt("Index")}/Stream.$format?api_key=${enc(token)}",
                        label = s.optString("DisplayTitle").ifBlank { "External" },
                        language = s.optString("Language").ifBlank { null },
                        mimeType = null,
                        forced = s.optBoolean("IsForced"),
                        fileName = "subtitle.$format",
                    )
                }
        }

        /** Jellyfin/Emby send ISO 8601 with 7 fractional digits; seconds precision is plenty. */
        fun parseDate(value: String?): Long {
            if (value.isNullOrBlank() || value.length < 19) return 0
            return runCatching {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(value.substring(0, 19))?.time ?: 0
            }.getOrDefault(0)
        }

        private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    }
}
