package com.sine.player.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/** How a track was chosen, in terms that survive a rebuild: language, label, codec and position. */
data class TrackChoice(val language: String?, val label: String?, val mime: String?, val index: Int) {
    fun toJson(): JSONObject = JSONObject().put("lang", language ?: "").put("label", label ?: "").put("mime", mime ?: "").put("index", index)

    companion object {
        fun fromJson(o: JSONObject?): TrackChoice? = o?.let {
            TrackChoice(it.optString("lang").ifEmpty { null }, it.optString("label").ifEmpty { null }, it.optString("mime").ifEmpty { null }, it.optInt("index", -1))
        }

        /**
         * Picks the option that best matches [choice] from (language, label, mime) triples: the
         * same label and language first, then same language and codec, then the same position.
         */
        fun match(choice: TrackChoice, options: List<Triple<String?, String?, String?>>): Int? {
            if (options.isEmpty()) return null
            options.indexOfFirst { it.first == choice.language && it.second == choice.label && it.third == choice.mime }.takeIf { it >= 0 }?.let { return it }
            options.indexOfFirst { it.first == choice.language && it.second == choice.label }.takeIf { it >= 0 }?.let { return it }
            options.indexOfFirst { it.first == choice.language && it.third == choice.mime }.takeIf { it >= 0 }?.let { return it }
            if (choice.index in options.indices && options[choice.index].first == choice.language) return choice.index
            return options.indexOfFirst { it.first != null && it.first == choice.language }.takeIf { it >= 0 }
        }
    }
}

/** What was picked for one file, restored the next time it plays. */
data class FileChoices(
    val audio: TrackChoice? = null,
    /** null = not chosen; [subtitlesOff] = explicitly turned off. */
    val subtitle: TrackChoice? = null,
    val subtitlesOff: Boolean = false,
    val subtitleDelayMs: Long = 0,
    val audioDelayMs: Long = 0,
    val volumeBoost: Int = 100,
    val aspect: AspectMode? = null,
    val speed: Float = 1f,
    /** "MEDIA3" / "MPV" when switched by hand. */
    val engine: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("audio", audio?.toJson())
        .put("sub", subtitle?.toJson())
        .put("subOff", subtitlesOff)
        .put("delay", subtitleDelayMs)
        .put("adelay", audioDelayMs)
        .put("boost", volumeBoost)
        .put("aspect", aspect?.name ?: "")
        .put("speed", speed.toDouble())
        .put("engine", engine ?: "")

    companion object {
        fun fromJson(o: JSONObject): FileChoices = FileChoices(
            audio = TrackChoice.fromJson(o.optJSONObject("audio")),
            subtitle = TrackChoice.fromJson(o.optJSONObject("sub")),
            subtitlesOff = o.optBoolean("subOff"),
            subtitleDelayMs = o.optLong("delay"),
            audioDelayMs = o.optLong("adelay"),
            volumeBoost = o.optInt("boost", 100),
            aspect = AspectMode.entries.firstOrNull { it.name == o.optString("aspect") },
            speed = o.optDouble("speed", 1.0).toFloat(),
            engine = o.optString("engine").ifEmpty { null },
        )
    }
}

/** Per-file audio, subtitle, delay, zoom, speed and engine choices. */
class FileMemory(context: Context) {
    private val prefs = context.getSharedPreferences("file_memory", Context.MODE_PRIVATE)

    fun get(uri: String): FileChoices? = prefs.getString(uri, null)?.let { runCatching { FileChoices.fromJson(JSONObject(it)) }.getOrNull() }

    fun update(uri: String, change: (FileChoices) -> FileChoices) {
        val updated = change(get(uri) ?: FileChoices())
        prefs.edit { putString(uri, updated.toJson().toString()) }
    }

    /** Audio/subtitle choices for a whole folder (a season), used by episodes without their own. */
    fun folder(folderUri: String): FileChoices? = get(FOLDER_PREFIX + folderUri)

    fun updateFolder(folderUri: String, change: (FileChoices) -> FileChoices) {
        val updated = tracksOnly(change(folder(folderUri) ?: FileChoices()))
        prefs.edit { putString(FOLDER_PREFIX + folderUri, updated.toJson().toString()) }
    }

    fun clear() = prefs.edit { clear() }

    companion object {
        private const val FOLDER_PREFIX = "folder:"

        /** Only the track choices carry over to other files; delays, zoom, speed and engine stay per file. */
        fun tracksOnly(c: FileChoices) = FileChoices(audio = c.audio, subtitle = c.subtitle, subtitlesOff = c.subtitlesOff)

        /** The file's own choices, with audio and subtitles from the folder where the file has none. */
        fun merge(file: FileChoices?, folder: FileChoices?): FileChoices? {
            if (folder == null) return file
            val base = file ?: FileChoices()
            val ownSubtitle = base.subtitle != null || base.subtitlesOff
            return base.copy(
                audio = base.audio ?: folder.audio,
                subtitle = if (ownSubtitle) base.subtitle else folder.subtitle,
                subtitlesOff = if (ownSubtitle) base.subtitlesOff else folder.subtitlesOff,
            )
        }
    }
}
