package com.sine.player.source

import android.net.Uri
import com.sine.player.SineApp
import com.sine.player.data.RemoteUris
import com.sine.player.mediaserver.MediaServerUris
import com.sine.player.net.Dlna
import com.sine.player.net.WebDav
import com.sine.player.smb.SmbLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A subtitle file that lives next to a server item rather than in a browsable folder. */
data class RemoteSubtitle(
    val url: String,
    val label: String,
    val language: String?,
    val mimeType: String?,
    val forced: Boolean = false,
    /** Used to guess the format when [mimeType] is missing. */
    val fileName: String? = null,
)

/** Resume point reported by a media server. */
data class RemoteProgress(val positionMs: Long, val durationMs: Long, val lastPlayedAt: Long, val played: Boolean)

data class MediaEntry(
    val name: String,
    val uri: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val modified: Long = 0,
    /** What the engine opens when it differs from [uri] (a server's stream URL); [uri] stays the resume key. */
    val playUri: String? = null,
    /** Server items are videos whatever their name looks like. */
    val playable: Boolean = false,
    val subtitles: List<RemoteSubtitle> = emptyList(),
    val remote: RemoteProgress? = null,
) {
    val isVideo: Boolean get() = !isDirectory && (playable || MediaFiles.isVideo(name))
    val isSubtitle: Boolean get() = !isDirectory && !playable && MediaFiles.isSubtitle(name)

    /** Name without the extension for files; server titles are shown as they are. */
    val title: String get() = if (playable) name else MediaFiles.baseName(name)
}

/** Lists folders for every supported location scheme. */
object Browsers {

    /** Schemes whose listings are folders of files, sorted by name here. Servers keep their own order. */
    private val fileSchemes = setOf("file", "smb", "webdav", "webdavs", "sftp", "nfs")

    suspend fun list(uri: String, showHidden: Boolean = SineApp.instance.settings.showHiddenFiles): List<MediaEntry> =
        withContext(Dispatchers.IO) {
            val app = SineApp.instance
            val parsed = Uri.parse(uri)
            val scheme = parsed.scheme.orEmpty()
            val entries = when (scheme) {
                "file" -> listLocal(File(parsed.path ?: "/"), showHidden)
                "smb" -> listSmb(parsed, showHidden)
                "webdav", "webdavs" -> WebDav.list(uri, WebDav.serverFor(app.remoteServers.servers, uri), showHidden)
                "sftp" -> app.sftp.list(parsed, showHidden)
                "nfs" -> app.nfs.list(parsed, showHidden)
                "dlna" -> listDlna(parsed)
                in MediaServerUris.schemes -> app.mediaServers.list(uri)
                else -> error("Browsing $scheme is not supported yet")
            }
            if (scheme in fileSchemes) {
                entries.sortedWith(compareByDescending<MediaEntry> { it.isDirectory }.thenBy(MediaFiles.naturalOrder) { it.name })
            } else {
                entries
            }
        }

    /** Looks up a single server or DLNA item, for playing it without its folder. */
    suspend fun resolve(uri: String): MediaEntry? = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(uri)
        when (parsed.scheme) {
            in MediaServerUris.schemes -> SineApp.instance.mediaServers.resolve(uri)
            "dlna" -> {
                val segments = parsed.pathSegments
                if (segments.size < 3 || segments[1] != "i") return@withContext null
                val dlna = SineApp.instance.dlna
                val server = dlna.server(segments[0]) ?: return@withContext null
                dlna.metadata(server, segments[2])?.let { Dlna.toEntries(server.udn, listOf(it)).firstOrNull() }
            }
            else -> null
        }
    }

    /** Folders and videos only; subtitles are listed separately so they can attach to videos. */
    fun visible(entries: List<MediaEntry>) = entries.filter { it.isDirectory || it.isVideo }

    fun child(parent: String, name: String): String = Uri.parse(parent).buildUpon().appendPath(name).build().toString()

    /** Whether files in this folder can have sidecar subtitles found by name. */
    fun hasSidecars(uri: String): Boolean = Uri.parse(uri).scheme in fileSchemes

    fun displayName(uri: String): String {
        val parsed = Uri.parse(uri)
        parsed.getQueryParameter("title")?.takeIf { it.isNotBlank() }?.let { return it }
        when (parsed.scheme) {
            in MediaServerUris.schemes -> SineApp.instance.mediaServers.serverFor(uri)?.let { return it.displayName }
            "dlna" -> return "DLNA servers"
        }
        return parsed.lastPathSegment ?: parsed.host ?: uri
    }

    /** What the browse screen shows under the title: a readable location without tokens. */
    fun describe(uri: String): String {
        val parsed = Uri.parse(uri)
        return when (parsed.scheme) {
            "file" -> Uri.decode(uri).removePrefix("file://")
            in MediaServerUris.schemes -> SineApp.instance.mediaServers.serverFor(uri)?.let { "${it.kind.label} · ${it.summary}" } ?: ""
            "dlna" -> "DLNA / UPnP"
            "webdav", "webdavs" -> Uri.decode(RemoteUris.webDavToHttp(uri))
            else -> Uri.decode(uri)
        }
    }

    private fun listLocal(dir: File, showHidden: Boolean): List<MediaEntry> {
        val files = dir.listFiles() ?: throw SecurityException("Can't read ${dir.path}. Allow file access in Settings.")
        return files
            .filter { showHidden || !it.name.startsWith(".") }
            .map { MediaEntry(it.name, Uri.fromFile(it).toString(), it.isDirectory, it.length(), it.lastModified()) }
    }

    private fun listSmb(uri: Uri, showHidden: Boolean): List<MediaEntry> {
        val location = SmbLocation.parse(uri)
        return SineApp.instance.smb.withShare(location) { share ->
            share.list(location.path).mapNotNull { info ->
                val name = info.fileName
                if (name == "." || name == "..") return@mapNotNull null
                val hidden = info.fileAttributes and FILE_ATTRIBUTE_HIDDEN != 0L || name.startsWith(".")
                if (hidden && !showHidden) return@mapNotNull null
                MediaEntry(
                    name = name,
                    uri = uri.buildUpon().appendPath(name).build().toString(),
                    isDirectory = info.fileAttributes and FILE_ATTRIBUTE_DIRECTORY != 0L,
                    size = info.endOfFile,
                    modified = info.lastWriteTime.toEpochMillis(),
                )
            }
        }
    }

    private fun listDlna(uri: Uri): List<MediaEntry> {
        val dlna = SineApp.instance.dlna
        val segments = uri.pathSegments
        if (segments.isEmpty()) {
            val servers = dlna.discover()
            if (servers.isEmpty()) error("No DLNA servers answered. Check that the server is on and on the same network.")
            return servers.map { MediaEntry(it.name, Dlna.containerUri(it.udn, "0", it.name), isDirectory = true) }
        }
        val server = dlna.server(segments[0]) ?: error("That DLNA server didn't answer. It may be off.")
        val objectId = if (segments.size >= 3 && segments[1] == "c") segments[2] else "0"
        return Dlna.toEntries(server.udn, dlna.browse(server, objectId))
    }

    private const val FILE_ATTRIBUTE_HIDDEN = 0x2L
    private const val FILE_ATTRIBUTE_DIRECTORY = 0x10L
}
