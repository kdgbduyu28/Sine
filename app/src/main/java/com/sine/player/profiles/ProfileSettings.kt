package com.sine.player.profiles

import android.content.Context
import androidx.core.content.edit
import com.sine.player.SineApp
import org.json.JSONObject

/**
 * Settings that belong to a person rather than the TV: languages and how subtitles look. Saved for
 * the profile being left and loaded for the one being entered; a new profile starts from the
 * current values.
 */
object ProfileSettings {
    val keys = listOf(
        "audio_language", "subtitle_language", "subtitle_mode",
        "subtitle_scale", "subtitle_bottom", "subtitle_background", "subtitle_color", "subtitle_edge",
        "best_audio", "clean_sdh",
    )

    private fun store(context: Context) = context.getSharedPreferences("profile_settings", Context.MODE_PRIVATE)

    fun swap(context: Context, fromId: String, toId: String) {
        if (fromId == toId) return
        val settings = SineApp.instance.settings
        store(context).edit { putString(fromId, encode(settings.snapshot(keys))) }
        val saved = store(context).getString(toId, null) ?: return
        settings.restore(decode(saved))
    }

    /** Values with their type, so restoring writes the same kind of preference back. */
    fun encode(values: Map<String, Any?>): String = JSONObject().apply {
        values.forEach { (k, v) ->
            val tagged = when (v) {
                is Boolean -> JSONObject().put("b", v)
                is Int -> JSONObject().put("i", v)
                is Long -> JSONObject().put("l", v)
                is Float -> JSONObject().put("f", v.toDouble())
                is String -> JSONObject().put("s", v)
                else -> null
            }
            tagged?.let { put(k, it) }
        }
    }.toString()

    fun decode(text: String): Map<String, Any?> {
        val o = JSONObject(text)
        return o.keys().asSequence().associateWith { k ->
            val v = o.getJSONObject(k)
            when {
                v.has("b") -> v.getBoolean("b")
                v.has("i") -> v.getInt("i")
                v.has("l") -> v.getLong("l")
                v.has("f") -> v.getDouble("f").toFloat()
                v.has("s") -> v.getString("s")
                else -> null
            }
        }
    }
}
