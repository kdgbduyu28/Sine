package com.sine.player.player

import java.util.Locale

/** What [AudioRank] needs to know about an audio track. */
data class AudioCandidate(
    val language: String?,
    val label: String?,
    val mime: String?,
    val channels: Int,
    val commentary: Boolean,
    val describesVideo: Boolean,
    val supported: Boolean,
)

/**
 * Picks the best audio track: the preferred language (else the language of the track the file
 * marks as default), never commentary or audio description when there's anything else, then the
 * richest format (TrueHD Atmos > DTS-HD MA > E-AC3 Atmos > TrueHD > DTS > E-AC3 > AC3 > lossless PCM/FLAC > AAC/Opus), then more channels.
 */
object AudioRank {
    fun formatScore(mime: String?, label: String?): Int {
        val atmos = label?.contains("atmos", ignoreCase = true) == true
        return when (mime?.lowercase(Locale.ROOT)) {
            "audio/true-hd" -> if (atmos) 100 else 80
            "audio/vnd.dts.uhd", "audio/vnd.dts.uhd;profile=p2" -> 95 // DTS:X
            "audio/vnd.dts.hd" -> if (label?.contains("dts:x", ignoreCase = true) == true) 95 else 90
            "audio/eac3-joc" -> 85
            "audio/eac3" -> if (atmos) 85 else 60
            "audio/vnd.dts", "audio/vnd.dts.hd;profile=lbr" -> 70
            "audio/ac3", "audio/ac4" -> 50
            "audio/raw", "audio/flac", "audio/x-flac", "audio/alac" -> 45
            "audio/mp4a-latm", "audio/opus", "audio/vorbis" -> 30
            "audio/mpeg", "audio/mpeg-l2" -> 20
            else -> 10
        }
    }

    private val commentaryWords = Regex("""(?i)\b(commentary|director'?s? comments?|kommentar|commentaire)\b""")
    private val descriptionWords = Regex("""(?i)\b(audio description|described|descriptive|\bAD\b)""")

    fun isExtra(c: AudioCandidate): Boolean =
        c.commentary || c.describesVideo || c.label?.let { commentaryWords.containsMatchIn(it) || descriptionWords.containsMatchIn(it) } == true

    /** Index of the track to play, or null when there's nothing to choose. */
    fun best(candidates: List<AudioCandidate>, preferredLanguage: String?, defaultIndex: Int): Int? {
        val usable = candidates.indices.filter { candidates[it].supported }.ifEmpty { candidates.indices.toList() }
        if (usable.isEmpty()) return null
        val main = usable.filter { !isExtra(candidates[it]) }.ifEmpty { usable }
        val wanted = preferredLanguage?.takeIf { it.isNotBlank() }?.let(::norm)
            ?: candidates.getOrNull(defaultIndex)?.language?.let(::norm)
        val inLanguage = wanted?.let { w -> main.filter { candidates[it].language?.let(::norm) == w } }.orEmpty().ifEmpty { main }
        return inLanguage.maxWithOrNull(
            compareBy<Int> { formatScore(candidates[it].mime, candidates[it].label) }
                .thenBy { candidates[it].channels }
                .thenBy { -it }, // earlier track wins ties
        )
    }

    /** "eng", "en", "en-US" → "en". */
    fun norm(language: String): String = language.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_').let {
        when (it) {
            "eng" -> "en"; "jpn" -> "ja"; "fre", "fra" -> "fr"; "ger", "deu" -> "de"; "spa" -> "es"; "ita" -> "it"
            "kor" -> "ko"; "chi", "zho" -> "zh"; "por" -> "pt"; "rus" -> "ru"; "fil", "tgl" -> "tl"; "hin" -> "hi"
            else -> it
        }
    }
}
