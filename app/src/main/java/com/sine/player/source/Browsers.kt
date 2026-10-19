package com.sine.player.source

import android.net.Uri
import com.sine.player.SineApp
import com.sine.player.smb.SmbLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class MediaEntry(
    val name: String,
    val uri: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val modified: Long = 0,
) {
    val isVideo: Boolean get() = !isDirectory && MediaFiles.isVideo(name)
    val isSubtitle: Boolean get() = !isDirectory && MediaFiles.isSubtitle(name)
}

/** Lists folders for every supported location scheme. */
object Browsers {

    suspend fun list(uri: String, showHidden: Boolean = SineApp.instance.settings.showHiddenFiles): List<MediaEntry> =
        withContext(Dispatchers.IO) {
            val parsed = Uri.parse(uri)
            val entries = when (parsed.scheme) {
                "file" -> listLocal(File(parsed.path ?: "/"), showHidden)
                "smb" -> listSmb(parsed, showHidden)
                else -> error("Browsing ${parsed.scheme} is not supported yet")
            }
            entries.sortedWith(compareByDescending<MediaEntry> { it.isDirectory }.thenBy(MediaFiles.naturalOrder) { it.name })
        }

    /** Folders and videos only; subtitles are listed separately so they can attach to videos. */
    fun visible(entries: List<MediaEntry>) = entries.filter { it.isDirectory || it.isVideo }

    fun child(parent: String, name: String): String = Uri.parse(parent).buildUpon().appendPath(name).build().toString()

    fun parent(uri: String): String? {
        val parsed = Uri.parse(uri)
        val segments = parsed.pathSegments
        if (segments.isEmpty()) return null
        if (parsed.scheme == "smb" && segments.size <= 1) return null
        return parsed.buildUpon().path(segments.dropLast(1).joinToString("/", prefix = "/")).build().toString()
    }

    fun displayName(uri: String): String {
        val parsed = Uri.parse(uri)
        return parsed.lastPathSegment ?: parsed.host ?: uri
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

    private const val FILE_ATTRIBUTE_HIDDEN = 0x2L
    private const val FILE_ATTRIBUTE_DIRECTORY = 0x10L
}
