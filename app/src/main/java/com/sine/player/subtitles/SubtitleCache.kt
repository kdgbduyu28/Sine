package com.sine.player.subtitles

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.sine.player.SineApp
import com.sine.player.player.PlayItem
import com.sine.player.player.PlayerSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Subtitles Sine fetched, synced or translated for a video, so they come back the next time it
 * plays (and auto-download doesn't run again).
 */
@UnstableApi
object SubtitleCache {
    private val prefs get() = SineApp.instance.getSharedPreferences("subtitle_cache", Context.MODE_PRIVATE)

    data class Entry(val path: String, val label: String, val language: String?)

    fun dirFor(context: Context, videoUri: String): File =
        File(context.filesDir, "subtitles/${key(videoUri)}").apply { mkdirs() }

    fun remember(videoUri: String, file: File, label: String, language: String?) {
        val list = entries(videoUri).filter { it.path != file.path } + Entry(file.path, label, language)
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("path", it.path).put("label", it.label).put("lang", it.language ?: "")) }
        prefs.edit { putString(videoUri, array.toString()) }
    }

    fun entries(videoUri: String): List<Entry> = runCatching {
        val array = JSONArray(prefs.getString(videoUri, "[]"))
        (0 until array.length()).map {
            val o = array.getJSONObject(it)
            Entry(o.getString("path"), o.optString("label"), o.optString("lang").ifEmpty { null })
        }
    }.getOrDefault(emptyList()).filter { File(it.path).exists() }

    /** Adds the cached subtitles of [item] to its subtitle list. */
    fun attach(item: PlayItem): PlayItem {
        val known = item.subtitles.map { it.uri.toString() }.toSet()
        val extra = entries(item.uri).mapNotNull { entry ->
            val uri = Uri.fromFile(File(entry.path)).toString()
            if (uri in known) return@mapNotNull null
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(uri))
                .setId(uri)
                .setMimeType(PlayerSession.subtitleMime(entry.path))
                .setLanguage(entry.language)
                .setLabel(entry.label)
                .build()
        }
        return if (extra.isEmpty()) item else item.copy(subtitles = item.subtitles + extra)
    }

    private fun key(uri: String): String =
        MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
}
