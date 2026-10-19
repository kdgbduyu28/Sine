package com.sine.player.data

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.edit
import org.json.JSONObject

data class PlaybackRecord(
    val uri: String,
    val title: String,
    val folderUri: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val watched: Boolean get() = durationMs > 0 && positionMs >= durationMs * WATCHED_FRACTION
    val resumable: Boolean get() = positionMs >= MIN_RESUME_MS && !watched

    companion object {
        const val WATCHED_FRACTION = 0.92
        const val MIN_RESUME_MS = 30_000L
    }
}

/** Remembers where each file was left off, keyed by its URI. */
class ResumeStore(context: Context) {
    private val prefs = context.getSharedPreferences("resume", Context.MODE_PRIVATE)

    /** Bumped on every write so Compose screens can re-read. */
    val version = mutableIntStateOf(0)

    fun get(uri: String): PlaybackRecord? = prefs.getString(uri, null)?.let { decode(uri, it) }

    fun save(uri: String, title: String, folderUri: String?, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        val json = JSONObject()
            .put("title", title)
            .put("folder", folderUri ?: "")
            .put("pos", positionMs)
            .put("dur", durationMs)
            .put("at", System.currentTimeMillis())
        prefs.edit { putString(uri, json.toString()) }
        version.intValue++
    }

    fun forget(uri: String) {
        prefs.edit { remove(uri) }
        version.intValue++
    }

    /** Files started but not finished, most recent first. */
    fun continueWatching(limit: Int = 20): List<PlaybackRecord> =
        prefs.all.mapNotNull { (uri, value) -> (value as? String)?.let { decode(uri, it) } }
            .filter { it.resumable }
            .sortedByDescending { it.updatedAt }
            .take(limit)

    private fun decode(uri: String, raw: String): PlaybackRecord? = runCatching {
        val json = JSONObject(raw)
        PlaybackRecord(
            uri = uri,
            title = json.optString("title"),
            folderUri = json.optString("folder").ifEmpty { null },
            positionMs = json.optLong("pos"),
            durationMs = json.optLong("dur"),
            updatedAt = json.optLong("at"),
        )
    }.getOrNull()
}
