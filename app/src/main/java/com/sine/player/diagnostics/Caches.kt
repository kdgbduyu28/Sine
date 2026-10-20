package com.sine.player.diagnostics

import android.content.Context
import java.io.File

/** Sine's throwaway files: thumbnails, posters, downloaded updates, mpv's scratch files. */
object Caches {
    fun size(context: Context): Long = dirs(context).sumOf(::sizeOf)

    /** Deletes everything in the cache folders (not downloads, recordings or settings). */
    fun clear(context: Context) {
        dirs(context).forEach { dir -> dir.listFiles()?.forEach { it.deleteRecursively() } }
    }

    private fun dirs(context: Context): List<File> = listOfNotNull(context.cacheDir, context.externalCacheDir)

    fun sizeOf(file: File): Long = if (file.isDirectory) file.listFiles()?.sumOf(::sizeOf) ?: 0 else file.length()
}
