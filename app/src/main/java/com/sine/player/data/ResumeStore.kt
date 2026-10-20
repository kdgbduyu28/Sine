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
    /** Name of the TV this progress came from, when another TV in the household sent it. */
    val fromDevice: String? = null,
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val watched: Boolean get() = durationMs > 0 && positionMs >= durationMs * WATCHED_FRACTION
    val resumable: Boolean get() = positionMs >= MIN_RESUME_MS && !watched

    companion object {
        const val WATCHED_FRACTION = 0.92
        const val MIN_RESUME_MS = 30_000L
    }
}

/** Remembers where each file was left off, keyed by its URI, separately for each profile. */
class ResumeStore(private val context: Context, private val profileId: () -> String = { "default" }) {
    private val prefs get() = profileId().let { id ->
        context.getSharedPreferences(if (id == "default") "resume" else "resume_$id", Context.MODE_PRIVATE)
    }

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

    /** Records a file as finished without playing it (duration unknown, so a nominal one is used). */
    fun markWatched(uri: String, title: String, folderUri: String?) {
        val known = get(uri)?.durationMs?.takeIf { it > 0 } ?: 1L
        save(uri, title, folderUri, known, known)
    }

    /** Every record of the active profile, for syncing to other TVs. */
    fun all(): List<PlaybackRecord> = prefs.all.mapNotNull { (uri, value) -> (value as? String)?.let { decode(uri, it) } }

    /** Takes records from another TV where they're newer than ours. Returns how many changed. */
    fun merge(records: List<PlaybackRecord>, fromDevice: String): Int {
        var changed = 0
        val editor = prefs.edit()
        records.forEach { incoming ->
            val local = get(incoming.uri)
            if (local == null || incoming.updatedAt > local.updatedAt) {
                editor.putString(incoming.uri, encode(incoming.copy(fromDevice = incoming.fromDevice ?: fromDevice)).toString())
                changed++
            }
        }
        editor.apply()
        if (changed > 0) version.intValue++
        return changed
    }

    /** Called when the active profile changes so screens re-read. */
    fun profileChanged() {
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

    private fun encode(r: PlaybackRecord) = JSONObject()
        .put("title", r.title)
        .put("folder", r.folderUri ?: "")
        .put("pos", r.positionMs)
        .put("dur", r.durationMs)
        .put("at", r.updatedAt)
        .put("from", r.fromDevice ?: "")

    private fun decode(uri: String, raw: String): PlaybackRecord? = runCatching {
        val json = JSONObject(raw)
        PlaybackRecord(
            uri = uri,
            title = json.optString("title"),
            folderUri = json.optString("folder").ifEmpty { null },
            positionMs = json.optLong("pos"),
            durationMs = json.optLong("dur"),
            updatedAt = json.optLong("at"),
            fromDevice = json.optString("from").ifEmpty { null },
        )
    }.getOrNull()

    companion object {
        fun toJson(records: List<PlaybackRecord>): org.json.JSONArray = org.json.JSONArray().apply {
            records.forEach { r ->
                put(
                    JSONObject().put("uri", r.uri).put("title", r.title).put("folder", r.folderUri ?: "")
                        .put("pos", r.positionMs).put("dur", r.durationMs).put("at", r.updatedAt),
                )
            }
        }

        fun fromJson(array: org.json.JSONArray): List<PlaybackRecord> = (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            PlaybackRecord(
                uri = o.optString("uri").ifEmpty { return@mapNotNull null },
                title = o.optString("title"),
                folderUri = o.optString("folder").ifEmpty { null },
                positionMs = o.optLong("pos"),
                durationMs = o.optLong("dur"),
                updatedAt = o.optLong("at"),
            )
        }
    }
}
