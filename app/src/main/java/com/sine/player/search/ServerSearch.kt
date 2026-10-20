package com.sine.player.search

import com.sine.player.data.RemoteServer
import com.sine.player.data.ServerKind
import com.sine.player.mediaserver.JellyfinClient
import com.sine.player.mediaserver.MediaServerUris
import com.sine.player.mediaserver.PlexClient
import com.sine.player.net.Net
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/** Asks each Jellyfin/Emby/Plex server for titles matching a search. */
object ServerSearch {
    fun search(server: RemoteServer, query: String, deviceId: String): List<SearchResult> = when (server.kind) {
        ServerKind.PLEX -> {
            val base = server.address.trimEnd('/')
            val json = Net.json(
                PlexClient.headers(Request.Builder().url("$base/hubs/search?query=${enc(query)}&limit=10"), deviceId)
                    .header("X-Plex-Token", server.token).build(),
            )
            parsePlex(json, server, base)
        }
        ServerKind.JELLYFIN, ServerKind.EMBY -> {
            val emby = server.kind == ServerKind.EMBY
            val base = JellyfinClient.apiBase(server.address, emby)
            val json = Net.json(
                Request.Builder()
                    .url(
                        "$base/Users/${enc(server.userId)}/Items?searchTerm=${enc(query)}&Recursive=true&Limit=20" +
                            "&IncludeItemTypes=Movie,Series,Episode&Fields=MediaSources,MediaStreams,Path,ProductionYear&EnableImages=true",
                    )
                    .header(if (emby) "X-Emby-Authorization" else "Authorization", JellyfinClient.authorization(deviceId, server.token))
                    .header("X-Emby-Token", server.token)
                    .header("Accept", "application/json")
                    .build(),
            )
            parseJellyfin(json, server, base)
        }
        else -> emptyList()
    }

    fun parseJellyfin(json: JSONObject, server: RemoteServer, base: String): List<SearchResult> {
        val items = json.optJSONArray("Items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val o = items.getJSONObject(i)
            val entry = JellyfinClient.parseItem(o, server, base) ?: return@mapNotNull null
            val id = o.optString("Id")
            val type = o.optString("Type")
            SearchResult(
                kind = if (entry.isDirectory) ResultKind.SERVER_SHOW else ResultKind.SERVER_VIDEO,
                title = entry.name,
                subtitle = listOfNotNull(if (type == "Series") "Show" else type.ifEmpty { null }, server.displayName).joinToString(" · "),
                target = entry.uri,
                folder = o.optString("SeasonId").ifEmpty { null }?.let { MediaServerUris.folder(server, it, o.optString("SeasonName").ifEmpty { "Season" }) },
                poster = if (o.optJSONObject("ImageTags")?.has("Primary") == true) "$base/Items/$id/Images/Primary?maxHeight=300" else null,
                year = o.optInt("ProductionYear").takeIf { it > 0 },
            )
        }
    }

    fun parsePlex(json: JSONObject, server: RemoteServer, base: String): List<SearchResult> {
        val hubs = json.optJSONObject("MediaContainer")?.optJSONArray("Hub") ?: return emptyList()
        val token = enc(server.token)
        return (0 until hubs.length()).flatMap { h ->
            val metadata = hubs.getJSONObject(h).optJSONArray("Metadata") ?: return@flatMap emptyList()
            (0 until metadata.length()).mapNotNull { i ->
                val o = metadata.getJSONObject(i)
                val type = o.optString("type")
                if (type !in setOf("movie", "show", "episode")) return@mapNotNull null
                val entry = PlexClient.parseItem(o, server, base) ?: return@mapNotNull null
                SearchResult(
                    kind = if (entry.isDirectory) ResultKind.SERVER_SHOW else ResultKind.SERVER_VIDEO,
                    title = entry.name,
                    subtitle = listOf(type.replaceFirstChar { it.uppercase() }, server.displayName).joinToString(" · "),
                    target = entry.uri,
                    folder = o.optString("parentRatingKey").ifEmpty { null }?.let {
                        MediaServerUris.folder(server, "/library/metadata/$it/children", o.optString("parentTitle").ifEmpty { "Season" })
                    },
                    poster = o.optString("thumb").ifEmpty { null }?.let { "$base$it?X-Plex-Token=$token" },
                    year = o.optInt("year").takeIf { it > 0 },
                )
            }
        }
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}
