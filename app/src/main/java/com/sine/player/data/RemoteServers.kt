package com.sine.player.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ServerKind(val label: String, val scheme: String, val isMediaServer: Boolean, val defaultPort: Int) {
    WEBDAV("WebDAV", "webdav", false, 0),
    SFTP("SFTP", "sftp", false, 22),
    NFS("NFS", "nfs", false, 2049),
    JELLYFIN("Jellyfin", "jellyfin", true, 8096),
    EMBY("Emby", "emby", true, 8096),
    PLEX("Plex", "plex", true, 32400),
}

/**
 * A saved WebDAV/SFTP/NFS share or Jellyfin/Emby/Plex server.
 *
 * - WebDAV, Jellyfin, Emby, Plex: [address] is a base URL such as `https://nas.local:5006/dav`.
 * - SFTP: [address] is the host; [path] is the folder to start in.
 * - NFS: [address] is the host; [path] is the export, e.g. `/volume1/video`.
 *
 * Media servers keep the [token] and [userId] from signing in instead of reusing the password.
 */
data class RemoteServer(
    val id: String = UUID.randomUUID().toString(),
    val kind: ServerKind,
    val name: String = "",
    val address: String,
    val port: Int = 0,
    val path: String = "",
    val username: String = "",
    val password: String = "",
    val token: String = "",
    val userId: String = "",
) {
    val displayName: String get() = name.ifBlank { address.substringAfter("://").trimEnd('/') + path.takeIf { kind == ServerKind.NFS }.orEmpty() }

    val effectivePort: Int get() = if (port > 0) port else kind.defaultPort

    /** Where browsing starts. */
    val rootUri: String
        get() = when (kind) {
            ServerKind.WEBDAV -> RemoteUris.webDavUri(address)
            ServerKind.SFTP -> "sftp://$address${if (port > 0 && port != 22) ":$port" else ""}/${path.trim('/')}".trimEnd('/') + "/"
            ServerKind.NFS -> "nfs://$address/${path.trim('/')}/"
            else -> "${kind.scheme}://$id/"
        }

    /** One line for the Home tile. */
    val summary: String
        get() = when (kind) {
            ServerKind.SFTP -> "sftp://${username.ifBlank { "?" }}@$address"
            ServerKind.NFS -> "nfs://$address/${path.trim('/')}"
            else -> address.substringAfter("://").trimEnd('/')
        }
}

/** Pure URI helpers shared by the server store and the browsers. */
object RemoteUris {
    /** `https://nas:5006/dav` → `webdavs://nas:5006/dav/`. */
    fun webDavUri(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        val secure = trimmed.startsWith("https://", ignoreCase = true)
        val rest = trimmed.substringAfter("://", trimmed)
        return "${if (secure) "webdavs" else "webdav"}://$rest/"
    }

    /** `webdavs://nas:5006/dav/a.mkv` → `https://nas:5006/dav/a.mkv`. */
    fun webDavToHttp(uri: String): String = when {
        uri.startsWith("webdavs://") -> "https://" + uri.removePrefix("webdavs://")
        uri.startsWith("webdav://") -> "http://" + uri.removePrefix("webdav://")
        else -> uri
    }

    /** Normalises what people type for a server address into a base URL. */
    fun normaliseBaseUrl(input: String, defaultPort: Int): String {
        var text = input.trim().trimEnd('/')
        if (text.isEmpty()) return text
        if (!text.contains("://")) text = "http://$text"
        val scheme = text.substringBefore("://")
        val rest = text.substringAfter("://")
        val hostPort = rest.substringBefore('/')
        val path = rest.substring(hostPort.length)
        val hasPort = hostPort.substringAfterLast(']').contains(':')
        val withPort = if (hasPort || defaultPort <= 0 || scheme.equals("https", true)) hostPort else "$hostPort:$defaultPort"
        return "$scheme://$withPort$path"
    }
}

/** Saved network servers other than SMB; stored like [SmbServers]. */
class RemoteServers(context: Context) {
    private val prefs = context.getSharedPreferences("remote_servers", Context.MODE_PRIVATE)

    val servers = mutableStateListOf<RemoteServer>().apply { addAll(load()) }

    fun get(id: String): RemoteServer? = servers.firstOrNull { it.id == id }

    fun ofKind(vararg kinds: ServerKind): List<RemoteServer> = servers.filter { it.kind in kinds }

    fun upsert(server: RemoteServer) {
        val index = servers.indexOfFirst { it.id == server.id }
        if (index >= 0) servers[index] = server else servers.add(server)
        persist()
    }

    fun remove(id: String) {
        servers.removeAll { it.id == id }
        persist()
    }

    private fun persist() {
        val array = JSONArray()
        servers.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("kind", it.kind.name)
                    .put("name", it.name)
                    .put("address", it.address)
                    .put("port", it.port)
                    .put("path", it.path)
                    .put("username", it.username)
                    .put("password", it.password)
                    .put("token", it.token)
                    .put("userId", it.userId),
            )
        }
        prefs.edit { putString("servers", array.toString()) }
    }

    private fun load(): List<RemoteServer> = runCatching {
        val array = JSONArray(prefs.getString("servers", "[]"))
        (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            val kind = ServerKind.entries.firstOrNull { it.name == o.optString("kind") } ?: return@mapNotNull null
            RemoteServer(
                id = o.getString("id"),
                kind = kind,
                name = o.optString("name"),
                address = o.getString("address"),
                port = o.optInt("port"),
                path = o.optString("path"),
                username = o.optString("username"),
                password = o.optString("password"),
                token = o.optString("token"),
                userId = o.optString("userId"),
            )
        }
    }.getOrDefault(emptyList())
}
