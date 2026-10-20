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

enum class EngineMode(val label: String, val description: String) {
    AUTO("Auto", "Media3 first; switches to mpv when Media3 can't play a file"),
    MPV("Always mpv", "Use mpv for everything. Media3 handles Dolby Vision and passthrough better."),
    MEDIA3("Media3 only", "Never switch to mpv automatically"),
}

enum class DownloadWhen(val label: String, val description: String) {
    NOW("Right away", "Start downloading as soon as something is queued"),
    OVERNIGHT("Overnight", "Wait until 1 AM and stop starting new files after 6 AM"),
}

enum class AutoDelete(val label: String, val days: Int?) {
    NEVER("Never", null),
    WATCHED("When watched", 0),
    DAY("1 day after watching", 1),
    WEEK("7 days after watching", 7),
}

enum class AutoSubtitles(val label: String, val description: String) {
    OFF("Off", "Only search when you ask from the Subtitles panel"),
    MISSING("When missing", "If a video has no subtitles in your language, download the best match automatically"),
}

enum class SkipMode(val label: String, val description: String) {
    OFF("Off", "Never offer to skip"),
    BUTTON("Show a button", "Press OK during an intro or credits to skip it"),
    AUTO("Skip automatically", "Jump over intros and recaps without asking"),
}

enum class MpvOutput(val label: String, val description: String) {
    GPU("GPU", "mpv draws video and subtitles itself. Works everywhere; HDR is tone-mapped to SDR."),
    DIRECT("Direct", "Hardware decoder renders straight to the screen, keeping HDR. mpv can't draw subtitles in this mode."),
}

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
    var upNextCard by pref("up_next_card", true)
    /** Home row with Continue watching / Next up from Jellyfin, Emby and Plex. */
    var serverHomeRow by pref("server_home_row", true)
    /** Library grid sort/filter, see LibraryView.encode. */
    var libraryViewMovies by pref("library_view_movies", "")
    var libraryViewShows by pref("library_view_shows", "")
    /** Ask "Still watching?" after this many episodes in a row with no button pressed; 0 = never. */
    var stillWatchingAfter by pref("still_watching_after", 3)
    var showHiddenFiles by pref("show_hidden", false)
    var engineMode by enumPref("engine_mode", EngineMode.AUTO)
    var mpvOutput by enumPref("mpv_output", MpvOutput.GPU)
    var openSubtitlesApiKey by pref("opensubtitles_api_key", "")
    var openSubtitlesUser by pref("opensubtitles_user", "")
    var openSubtitlesPassword by pref("opensubtitles_password", "")
    var photoIntervalSeconds by pref("photo_interval", 8)
    var photoKenBurns by pref("photo_ken_burns", true)
    var photoShuffle by pref("photo_shuffle", false)
    var screensaverFolder by pref("screensaver_folder", "")
    var downloadTarget by pref("download_target", "")
    var downloadWhen by enumPref("download_when", DownloadWhen.NOW)
    var downloadAutoDelete by enumPref("download_auto_delete", AutoDelete.NEVER)
    var autoSubtitles by enumPref("auto_subtitles", AutoSubtitles.MISSING)
    var avoidHearingImpaired by pref("avoid_sdh", true)
    var podnapisiEnabled by pref("podnapisi", true)
    var subdlApiKey by pref("subdl_api_key", "")
    var householdEnabled by pref("household_enabled", false)
    private var storedHouseholdCode by pref("household_code", "")
    private var storedDeviceName by pref("device_name", "")
    var rememberPerFile by pref("remember_per_file", true)
    /** Audio/subtitle picked on one episode applies to the rest of its folder. */
    var rememberPerFolder by pref("remember_per_folder", true)
    /** Choose the richest audio format in the preferred language, skipping commentary. */
    var bestAudio by pref("best_audio", true)
    var bufferSize by enumPref("buffer_size", com.sine.player.player.BufferSize.NORMAL)
    /** Media server bitrate cap in Mbps (server transcodes to HLS); 0 = original file. */
    var serverQualityMbps by pref("server_quality_mbps", 0)
    /** Apply the cap only when the server isn't on the home network. */
    var transcodeAwayOnly by pref("transcode_away_only", true)
    var skipSegments by enumPref("skip_segments", SkipMode.BUTTON)
    var nightMode by pref("night_mode", false)
    var dialogueBoost by pref("dialogue_boost", false)
    var seekPreviews by pref("seek_previews", true)
    var castReceiver by pref("cast_receiver", false)
    var pictureInPicture by pref("picture_in_picture", true)
    var tmdbApiKey by pref("tmdb_api_key", "")
    var launcherRow by pref("launcher_row", true)
    var traktClientId by pref("trakt_client_id", "")
    var traktClientSecret by pref("trakt_client_secret", "")
    var traktAccessToken by pref("trakt_access_token", "")
    var traktRefreshToken by pref("trakt_refresh_token", "")
    var traktExpiresAt by pref("trakt_expires_at", 0L)
    var mpvUpscaler by pref("mpv_upscaler", "")
    var mpvDeband by pref("mpv_deband", false)
    var mpvInterpolation by pref("mpv_interpolation", false)
    /** Shader file names, separated by "|". */
    var mpvShaders by pref("mpv_shaders", "")
    private var storedDeviceId by pref("device_id", "")

    /** Stable id that media servers use to tell this TV apart from other clients. */
    fun deviceId(): String = storedDeviceId.ifEmpty { java.util.UUID.randomUUID().toString().also { storedDeviceId = it } }

    /** Shared by every TV in the household; set by whichever TV joins another. */
    fun householdCode(): String = storedHouseholdCode.ifEmpty {
        com.sine.player.household.Household.newCode().also { storedHouseholdCode = it }
    }

    fun setHouseholdCode(code: String) {
        storedHouseholdCode = code.trim().uppercase(java.util.Locale.US)
    }

    /** What other TVs call this one, e.g. "Living room". */
    fun deviceName(): String = storedDeviceName.ifEmpty {
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim().replaceFirstChar { it.uppercase() }
    }

    fun setDeviceName(name: String) {
        storedDeviceName = name.trim()
    }

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
                    is Long -> putLong(key, value)
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
        is Long -> prefs.getLong(key, default) as T
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
        val photoIntervals = listOf(4, 8, 15, 30, 60)
    }
}
