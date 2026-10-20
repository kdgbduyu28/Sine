package com.sine.player.subtitles

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Translates subtitle lines on the TV with Google's ML Kit models (about 30 MB per language,
 * downloaded once). Needs Google Play services, so it won't work on Fire TV.
 */
object SubtitleTranslator {

    /** ML Kit's code for a Sine language code, or null when it has no model for it. */
    fun mlKitLanguage(code: String?): String? {
        val c = when (code?.lowercase()) {
            null, "" -> return null
            "fil", "tgl" -> "tl"
            "eng" -> "en"
            "jpn" -> "ja"
            "kor" -> "ko"
            "chi", "zho", "zh-cn", "zh-tw" -> "zh"
            "spa" -> "es"
            "fre", "fra" -> "fr"
            "ger", "deu" -> "de"
            else -> code.lowercase().take(2)
        }
        return TranslateLanguage.fromLanguageTag(c)
    }

    suspend fun translate(cues: List<Cue>, from: String, to: String, onProgress: (String) -> Unit): List<Cue> {
        val source = mlKitLanguage(from) ?: error("Translation from \"$from\" isn't available")
        val target = mlKitLanguage(to) ?: error("Translation to \"$to\" isn't available")
        val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
        try {
            onProgress("Downloading the translation model (first time only)…")
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            val cache = HashMap<String, String>()
            val out = ArrayList<Cue>(cues.size)
            for ((i, cue) in cues.withIndex()) {
                if (i % 25 == 0) onProgress("Translating… ${i * 100 / cues.size.coerceAtLeast(1)}%")
                var text = cache[cue.text]
                if (text == null) {
                    val lines = ArrayList<String>()
                    for (line in cue.text.split('\n')) lines += if (line.isBlank()) line else translator.translate(line).await()
                    text = lines.joinToString("\n")
                    cache[cue.text] = text
                }
                out += cue.copy(text = text)
            }
            return out
        } finally {
            translator.close()
        }
    }
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
