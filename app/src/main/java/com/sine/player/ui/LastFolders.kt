package com.sine.player.ui

import android.content.Context
import androidx.core.content.edit

/** The folder last opened inside each share, drive or server, so a return trip can jump back. */
object LastFolders {
    private fun prefs(context: Context) = context.getSharedPreferences("last_folders", Context.MODE_PRIVATE)

    /**
     * The top of the source [uri] belongs to: smb://host/share/, file:///storage/<drive>/ (or
     * /storage/emulated/0/), otherwise scheme://host/.
     */
    fun rootOf(uri: String): String? {
        val scheme = uri.substringBefore("://", "").ifEmpty { return null }
        val rest = uri.substringAfter("://")
        val segments = rest.split('/').filter { it.isNotEmpty() }
        return when (scheme) {
            "smb" -> if (segments.size >= 2) "smb://${segments[0]}/${segments[1]}/" else null
            "file" -> when {
                segments.take(3) == listOf("storage", "emulated", "0") -> "file:///storage/emulated/0/"
                segments.firstOrNull() == "storage" && segments.size >= 2 -> "file:///storage/${segments[1]}/"
                else -> null
            }
            else -> segments.firstOrNull()?.let { "$scheme://$it/" }
        }
    }

    private fun same(a: String, b: String) = a.trimEnd('/') == b.trimEnd('/')

    fun remember(context: Context, uri: String) {
        val root = rootOf(uri) ?: return
        if (same(root, uri)) return
        prefs(context).edit { putString(root, uri) }
    }

    /** Where to offer to continue when [uri] is the top of its source; null otherwise. */
    fun continueFrom(context: Context, uri: String): String? {
        val root = rootOf(uri) ?: return null
        if (!same(root, uri)) return null
        return prefs(context).getString(root, null)?.takeIf { !same(it, uri) }
    }

    /** "Movies / 2024" for the part of [folder] below [root]. */
    fun relative(root: String, folder: String): String =
        folder.removePrefix(root.trimEnd('/')).split('/').filter { it.isNotEmpty() }.joinToString(" / ") { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
}
