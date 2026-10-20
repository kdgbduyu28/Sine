package com.sine.player.mediaserver

import android.util.Log
import com.sine.player.data.PlaybackRecord
import com.sine.player.data.RemoteServer
import com.sine.player.data.RemoteServers
import com.sine.player.data.ResumeStore
import com.sine.player.data.ServerKind
import com.sine.player.source.MediaEntry
import com.sine.player.source.RemoteProgress
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors

enum class ProgressState { PLAYING, PAUSED, STOPPED }

/**
 * Media server URIs use the saved server's id as the host:
 * `jellyfin://<id>/` (libraries), `jellyfin://<id>/f/<folder>?title=…` and `jellyfin://<id>/i/<item>`.
 * Item URIs carry no title, so resume records stay keyed to the item even if it's renamed.
 */
object MediaServerUris {
    val schemes = setOf("jellyfin", "emby", "plex")

    fun isMediaServer(uri: String) = uri.substringBefore("://") in schemes

    fun folder(server: RemoteServer, folderId: String, title: String) =
        "${server.kind.scheme}://${server.id}/f/${enc(folderId)}?title=${enc(title)}"

    fun item(server: RemoteServer, itemId: String) = "${server.kind.scheme}://${server.id}/i/${enc(itemId)}"

    /** (serverId, "f" | "i" | null, id) from a media server URI. */
    fun parse(uri: String): Triple<String, String?, String?>? {
        if (!isMediaServer(uri)) return null
        val rest = uri.substringAfter("://").substringBefore('?')
        val parts = rest.split('/').filter { it.isNotEmpty() }
        val serverId = parts.getOrNull(0) ?: return null
        val kind = parts.getOrNull(1)
        val id = parts.getOrNull(2)?.let { URLDecoder.decode(it, "UTF-8") }
        return Triple(serverId, kind, id)
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/** Decides whether a server's progress should replace the local resume record. */
object ResumeMerge {
    /** Returns (position, duration) to save locally, or null to keep what's stored. */
    fun decide(local: PlaybackRecord?, remote: RemoteProgress): Pair<Long, Long>? {
        if (remote.durationMs <= 0) return null
        val position = if (remote.played && remote.positionMs == 0L) remote.durationMs else remote.positionMs
        if (local == null) return if (position > 0) position to remote.durationMs else null
        if (remote.lastPlayedAt <= local.updatedAt) return null
        if (local.positionMs == position) return null
        return position to remote.durationMs
    }
}

/** Browsing, item lookup and progress write-back for Jellyfin, Emby and Plex. */
class MediaServers(
    private val servers: RemoteServers,
    private val resume: ResumeStore,
    private val deviceId: () -> String,
) {
    private val reporter = Executors.newSingleThreadExecutor()
    private val started = HashSet<String>()

    fun serverFor(uri: String): RemoteServer? = MediaServerUris.parse(uri)?.first?.let(servers::get)

    /** Lists a server root (libraries) or folder, and pulls the server's resume points into [resume]. */
    fun list(uri: String): List<MediaEntry> {
        val (serverId, kind, id) = MediaServerUris.parse(uri) ?: error("Not a media server URI: $uri")
        val server = servers.get(serverId) ?: error("This server was removed. Add it again from Home.")
        val entries = when (server.kind) {
            ServerKind.PLEX -> PlexClient(server, deviceId()).let { if (kind == "f" && id != null) it.children(id) else it.libraries() }
            else -> JellyfinClient(server, deviceId()).let { if (kind == "f" && id != null) it.children(id) else it.libraries() }
        }
        entries.forEach(::mergeProgress)
        return entries
    }

    fun resolve(uri: String): MediaEntry? {
        val (serverId, kind, id) = MediaServerUris.parse(uri) ?: return null
        if (kind != "i" || id == null) return null
        val server = servers.get(serverId) ?: return null
        val entry = when (server.kind) {
            ServerKind.PLEX -> PlexClient(server, deviceId()).item(id)
            else -> JellyfinClient(server, deviceId()).item(id)
        }
        entry?.let(::mergeProgress)
        return entry
    }

    private fun mergeProgress(entry: MediaEntry) {
        val remote = entry.remote ?: return
        ResumeMerge.decide(resume.get(entry.uri), remote)?.let { (position, duration) ->
            resume.save(entry.uri, entry.title, null, position, duration)
        }
    }

    /** Sends playback progress to the server in the background; non-server URIs are ignored. */
    fun report(uri: String, positionMs: Long, durationMs: Long, state: ProgressState) {
        val (serverId, kind, id) = MediaServerUris.parse(uri) ?: return
        if (kind != "i" || id == null) return
        val server = servers.get(serverId) ?: return
        reporter.execute {
            runCatching {
                when (server.kind) {
                    ServerKind.PLEX -> PlexClient(server, deviceId()).report(id, positionMs, durationMs, state)
                    else -> {
                        val wasStarted = synchronized(started) { if (state == ProgressState.STOPPED) started.remove(uri) else !started.add(uri) }
                        JellyfinClient(server, deviceId()).report(id, positionMs, state, wasStarted)
                    }
                }
            }.onFailure { Log.w("SineMediaServers", "Progress report failed: ${it.message}") }
        }
    }
}
