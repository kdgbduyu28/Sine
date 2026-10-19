package com.sine.player.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

enum class DolbyVisionMode(val label: String, val description: String) {
    AUTO("Auto", "Dolby Vision when this device can decode it, otherwise the HDR10 base layer"),
    BASE_LAYER("HDR10 base layer", "Always ignore Dolby Vision metadata and play the HDR10 layer"),
}

enum class ResumeMode(val label: String) { ASK("Ask"), ALWAYS("Always resume"), NEVER("Start from beginning") }

enum class AspectMode(val label: String) { FIT("Fit"), FILL("Stretch"), ZOOM("Zoom / crop") }

/** User preferences, persisted to SharedPreferences and observable from Compose. */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var passthrough by pref("passthrough", true)
    var tunneling by pref("tunneling", false)
    var frameRateMatching by pref("frame_rate_matching", true)
    var dolbyVisionMode by enumPref("dv_mode", DolbyVisionMode.AUTO)
    var resumeMode by enumPref("resume_mode", ResumeMode.ASK)
    var defaultAspect by enumPref("aspect", AspectMode.FIT)
    var audioLanguage by pref("audio_language", "")
    var subtitleLanguage by pref("subtitle_language", "")
    var subtitleScale by pref("subtitle_scale", 1.0f)
    var subtitleBottom by pref("subtitle_bottom", 0.08f)
    var subtitleBackground by pref("subtitle_background", false)
    var seekStepSeconds by pref("seek_step", 10)
    var autoplayNext by pref("autoplay_next", true)
    var showHiddenFiles by pref("show_hidden", false)

    private fun <T> pref(key: String, default: T) = PrefDelegate(key, default)

    private inline fun <reified E : Enum<E>> enumPref(key: String, default: E): EnumDelegate<E> =
        EnumDelegate(key, default) { name -> enumValues<E>().firstOrNull { it.name == name } }

    inner class PrefDelegate<T>(private val key: String, default: T) {
        private var state by mutableStateOf(read(key, default))

        operator fun getValue(thisRef: Any?, property: Any?): T = state

        operator fun setValue(thisRef: Any?, property: Any?, value: T) {
            state = value
            prefs.edit {
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Float -> putFloat(key, value)
                    is String -> putString(key, value)
                    else -> error("Unsupported preference type for $key")
                }
            }
        }
    }

    inner class EnumDelegate<E : Enum<E>>(private val key: String, default: E, parse: (String?) -> E?) {
        private var state by mutableStateOf(parse(prefs.getString(key, null)) ?: default)

        operator fun getValue(thisRef: Any?, property: Any?): E = state

        operator fun setValue(thisRef: Any?, property: Any?, value: E) {
            state = value
            prefs.edit { putString(key, value.name) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> read(key: String, default: T): T = when (default) {
        is Boolean -> prefs.getBoolean(key, default) as T
        is Int -> prefs.getInt(key, default) as T
        is Float -> prefs.getFloat(key, default) as T
        is String -> (prefs.getString(key, default) ?: default) as T
        else -> default
    }

    companion object {
        /** Language choices offered for audio and subtitle preferences ("" = no preference). */
        val languages = listOf(
            "" to "No preference",
            "en" to "English",
            "fil" to "Filipino",
            "ja" to "Japanese",
            "ko" to "Korean",
            "zh" to "Chinese",
            "es" to "Spanish",
            "fr" to "French",
            "de" to "German",
        )
        val subtitleScales = listOf(0.75f, 0.9f, 1.0f, 1.15f, 1.3f, 1.5f, 1.75f, 2.0f)
        val subtitleBottoms = listOf(0.02f, 0.05f, 0.08f, 0.12f, 0.16f, 0.20f)
        val seekSteps = listOf(5, 10, 15, 30, 60)
    }
}
