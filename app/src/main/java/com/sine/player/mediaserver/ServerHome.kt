package com.sine.player.mediaserver

import com.sine.player.data.RemoteServer
import com.sine.player.data.ServerKind
import com.sine.player.net.Net
import com.sine.player.source.MediaEntry
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/** One card on the "From your servers" home row. */
data class ServerHomeItem(
    val entry: MediaEntry,
    val title: String,
    val subtitle: String?,
    val image: String?,
    /** The season (or folder) to use as the playlist, so next/previous episode work. */
    val folder: String?,
    /** 0..1, or 0 for a next-up episode that hasn't been started. */
    val progress: Float,
    val lastPlayedAt: Long,
    val serverName: String,
)

/**
 * What each Jellyfin/Emby/Plex server would show as Continue watching and Next up (Plex: On
 * Deck), including things started in other apps.
 */
object ServerHome {

    fun load(server: RemoteServer, deviceId: String): List<ServerHomeItem> = when (server.kind) {
        ServerKind.PLEX -> plex(server, deviceId)
        ServerKind.JELLYFIN, ServerKind.EMBY -> jellyfin(server, deviceId)
        else -> emptyList()
    }

    private fun jellyfin(server: RemoteServer, deviceId: String): List<ServerHomeItem> {
        val emby = server.kind == ServerKind.EMBY
        val base = JellyfinClient.apiBase(server.address, emby)
        fun get(path: String): JSONObject = Net.json(
            Request.Builder().url(base + path)
                .header(if (emby) "X-Emby-Authorization" else "Authorization", JellyfinClient.authorization(deviceId, server.token))
                .header("X-Emby-Token", server.token)
                .header("Accept", "application/json")
                .build(),
        )
        val fields = "Fields=MediaSources,MediaStreams,Path&EnableImages=true&EnableUserData=true"
        val user = enc(server.userId)
        val resume = runCatching { get("/Users/$user/Items/Resume?Limit=20&MediaTypes=Video&$fields") }.getOrNull()
        val nextUp = runCatching { get("/Shows/NextUp?UserId=$user&Limit=20&$fields") }.getOrNull()
        return (listOfNotNull(resume, nextUp).flatMap { parseJellyfin(it, server, base) }).distinctBy { it.entry.uri }
    }

    fun parseJellyfin(json: JSONObject, server: RemoteServer, base: String): List<ServerHomeItem> {
        val items = json.optJSONArray("Items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val o = items.getJSONObject(i)
            val entry = JellyfinClient.parseItem(o, server, base)?.takeIf { !it.isDirectory } ?: return@mapNotNull null
            val id = o.optString("Id")
            val series = o.optString("SeriesName").ifEmpty { null }
            val episode = episodeCode(o.optInt("ParentIndexNumber", -1), o.optInt("IndexNumber", -1))
            val remote = entry.remote
            // Episode stills, else the series' backdrop, else the item's own primary image.
            val image = when {
                o.optJSONObject("ImageTags")?.has("Thumb") == true -> "$base/Items/$id/Images/Thumb?maxHeight=270"
                o.optJSONArray("ParentBackdropImageTags")?.length()?.let { it > 0 } == true ->
                    "$base/Items/${o.optString("ParentBackdropItemId")}/Images/Backdrop?maxHeight=270"
                o.optJSONObject("ImageTags")?.has("Primary") == true -> "$base/Items/$id/Images/Primary?maxHeight=270"
                else -> null
            }
            ServerHomeItem(
                entry = entry,
                title = series ?: o.optString("Name"),
                subtitle = if (series != null) listOfNotNull(episode, o.optString("Name").ifEmpty { null }).joinToString(" · ") else o.optInt("ProductionYear").takeIf { it > 0 }?.toString(),
                image = image,
                folder = o.optString("SeasonId").ifEmpty { null }?.let { MediaServerUris.folder(server, it, o.optString("SeasonName").ifEmpty { "Season" }) },
                progress = remote?.let { if (it.durationMs > 0) (it.positionMs.toFloat() / it.durationMs).coerceIn(0f, 1f) else 0f } ?: 0f,
                lastPlayedAt = remote?.lastPlayedAt ?: 0,
                serverName = server.displayName,
            )
        }
    }

    private fun plex(server: RemoteServer, deviceId: String): List<ServerHomeItem> {
        val base = server.address.trimEnd('/')
        val json = Net.json(PlexClient.headers(Request.Builder().url("$base/library/onDeck"), deviceId).header("X-Plex-Token", server.token).build())
        return parsePlex(json, server, base)
    }

    fun parsePlex(json: JSONObject, server: RemoteServer, base: String): List<ServerHomeItem> {
        val items = json.optJSONObject("MediaContainer")?.optJSONArray("Metadata") ?: return emptyList()
        val token = enc(server.token)
        return (0 until items.length()).mapNotNull { i ->
            val o = items.getJSONObject(i)
            val entry = PlexClient.parseItem(o, server, base)?.takeIf { !it.isDirectory } ?: return@mapNotNull null
            val show = o.optString("grandparentTitle").ifEmpty { null }
            val remote = entry.remote
            val art = o.optString("thumb").ifEmpty { o.optString("grandparentArt") }.ifEmpty { o.optString("art") }.ifEmpty { null }
            ServerHomeItem(
                entry = entry,
                title = show ?: o.optString("title"),
                subtitle = if (show != null) listOfNotNull(episodeCode(o.optInt("parentIndex", -1), o.optInt("index", -1)), o.optString("title").ifEmpty { null }).joinToString(" · ")
                else o.optInt("year").takeIf { it > 0 }?.toString(),
                image = art?.let { "$base/photo/:/transcode?width=480&height=270&minSize=1&url=${enc(it)}&X-Plex-Token=$token" },
                folder = o.optString("parentRatingKey").ifEmpty { null }?.let {
                    MediaServerUris.folder(server, "/library/metadata/$it/children", o.optString("parentTitle").ifEmpty { "Season" })
                },
                progress = remote?.let { if (it.durationMs > 0) (it.positionMs.toFloat() / it.durationMs).coerceIn(0f, 1f) else 0f } ?: 0f,
                lastPlayedAt = remote?.lastPlayedAt ?: 0,
                serverName = server.displayName,
            )
        }
    }

    /** Started items first (most recent first), then next-up episodes. */
    fun order(items: List<ServerHomeItem>): List<ServerHomeItem> =
        items.distinctBy { it.entry.uri }.sortedWith(compareByDescending<ServerHomeItem> { it.progress > 0f }.thenByDescending { it.lastPlayedAt })

    fun episodeCode(season: Int, episode: Int): String? = if (season >= 0 && episode >= 0) "S%02dE%02d".format(season, episode) else null

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}
