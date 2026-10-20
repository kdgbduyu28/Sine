package com.sine.player.net

import android.net.Uri
import com.emc.ecs.nfsclient.nfs.io.NfsFileInputStream
import com.emc.ecs.nfsclient.nfs.nfs3.Nfs3
import com.emc.ecs.nfsclient.rpc.CredentialUnix
import com.sine.player.data.RemoteServer
import com.sine.player.data.ServerKind
import com.sine.player.source.MediaEntry
import java.io.InputStream

/** An `nfs://host/export/rest` URI split at the saved export. */
data class NfsLocation(val host: String, val export: String, val path: String) {
    companion object {
        /** Picks the longest saved export that [uri]'s path starts with. */
        fun resolve(host: String?, path: String?, exports: List<String>): NfsLocation? {
            if (host.isNullOrEmpty()) return null
            val full = "/" + path.orEmpty().trim('/')
            val export = exports.map { "/" + it.trim('/') }
                .filter { full == it || full.startsWith("$it/") || it == "/" }
                .maxByOrNull { it.length } ?: return null
            val rest = full.removePrefix(export).ifEmpty { "/" }
            return NfsLocation(host, export, if (rest.startsWith("/")) rest else "/$rest")
        }
    }
}

/**
 * NFSv3 through nfs-client-java. Android apps can't bind ports below 1024, so the export must
 * allow "insecure" (non-privileged) client ports, e.g. `insecure` in /etc/exports or Synology's
 * "Allow connections from non-privileged ports".
 */
class NfsConnections(private val servers: () -> List<RemoteServer>) {
    private val mounts = HashMap<String, Nfs3>()

    fun locate(uri: Uri): NfsLocation {
        val exports = servers().filter { it.kind == ServerKind.NFS && it.address.equals(uri.host, true) }.map { it.path }
        return NfsLocation.resolve(uri.host, uri.path, exports) ?: error("No saved NFS export on ${uri.host} matches ${uri.path}. Add it from Home first.")
    }

    fun list(uri: Uri, showHidden: Boolean): List<MediaEntry> {
        val location = locate(uri)
        val dir = mount(location).newFile(location.path)
        return dir.listFiles().mapNotNull { file ->
            val name = file.name
            if (name == "." || name == "..") return@mapNotNull null
            if (!showHidden && name.startsWith(".")) return@mapNotNull null
            MediaEntry(
                name = name,
                uri = uri.buildUpon().appendPath(name).build().toString(),
                isDirectory = runCatching { file.isDirectory }.getOrDefault(false),
                size = runCatching { file.length() }.getOrDefault(0),
                modified = runCatching { file.lastModified() }.getOrDefault(0),
            )
        }
    }

    fun open(uri: Uri, position: Long): Pair<InputStream, Long> {
        val location = locate(uri)
        val file = mount(location).newFile(location.path)
        return NfsFileInputStream(file, position, READ_SIZE) to file.length()
    }

    fun test(server: RemoteServer): Int {
        val nfs = Nfs3(server.address, "/" + server.path.trim('/'), CredentialUnix(0, 0, null), 2)
        return nfs.newFile("/").listFiles().size
    }

    @Synchronized
    private fun mount(location: NfsLocation): Nfs3 {
        val key = "${location.host.lowercase()}:${location.export}"
        return mounts.getOrPut(key) {
            // uid/gid 0 is squashed to nobody by most servers; read-only access is all we need.
            Nfs3(location.host, location.export, CredentialUnix(0, 0, null), 3)
        }
    }

    private companion object {
        const val READ_SIZE = 1024 * 1024
    }
}
