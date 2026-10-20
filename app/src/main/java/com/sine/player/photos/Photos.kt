package com.sine.player.photos

import androidx.media3.common.util.UnstableApi
import com.sine.player.source.Browsers
import com.sine.player.source.MediaEntry

@UnstableApi
object Photos {
    /** Images in [folder] in name order. */
    suspend fun inFolder(folder: String): List<MediaEntry> = Browsers.list(folder).filter { it.isImage }

    /**
     * Images in [folder] and its sub-folders (two levels down), for the screensaver. Stops at
     * [limit] so a huge photo library doesn't stall the TV.
     */
    suspend fun collect(folder: String, depth: Int = 2, limit: Int = 1000): List<MediaEntry> {
        val found = mutableListOf<MediaEntry>()
        suspend fun walk(uri: String, level: Int) {
            if (found.size >= limit) return
            val entries = runCatching { Browsers.list(uri) }.getOrDefault(emptyList())
            found += entries.filter { it.isImage }.take(limit - found.size)
            if (level < depth) entries.filter { it.isDirectory }.forEach { walk(it.uri, level + 1) }
        }
        walk(folder, 0)
        return found
    }
}
