package com.sine.player.source

/** File-name rules shared by every source. Pure Kotlin so it can be unit tested. */
object MediaFiles {

    val videoExtensions = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "wmv", "ts", "m2ts", "mts", "webm", "flv",
        "mpg", "mpeg", "vob", "3gp", "ogv", "rmvb", "divx", "asf", "f4v", "mk3d",
    )

    val subtitleExtensions = setOf("srt", "ass", "ssa", "vtt", "ttml", "dfxp")

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun baseName(name: String): String = name.substringBeforeLast('.', name)

    fun isVideo(name: String) = extension(name) in videoExtensions

    fun isSubtitle(name: String) = extension(name) in subtitleExtensions

    /** A subtitle file sitting next to a video, e.g. `Movie.en.forced.srt` for `Movie.mkv`. */
    data class Sidecar(val fileName: String, val language: String?, val forced: Boolean, val label: String)

    /**
     * Finds subtitle files that belong to [videoName] among [siblings]. A sidecar matches when its
     * name starts with the video's base name; the dot-separated tags in between become its
     * language and flags.
     */
    fun findSidecars(videoName: String, siblings: List<String>): List<Sidecar> {
        val base = baseName(videoName).lowercase()
        return siblings
            .filter {
                val sub = baseName(it).lowercase()
                isSubtitle(it) && sub.startsWith(base) && (sub.length == base.length || sub[base.length] == '.')
            }
            .map { file ->
                val tags = baseName(file).substring(base.length)
                    .split('.', '_', '-', ' ')
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                val forced = "forced" in tags
                val sdh = "sdh" in tags || "cc" in tags || "hi" in tags
                val language = tags.firstOrNull { it in languageNames }
                val parts = buildList {
                    add(language?.let { languageNames[it] } ?: "External")
                    if (forced) add("Forced")
                    if (sdh) add("SDH")
                    add(extension(file).uppercase())
                }
                Sidecar(file, language?.let { isoCode(it) }, forced, parts.joinToString(" · "))
            }
            .sortedWith(compareBy({ it.forced }, { it.label }))
    }

    private fun isoCode(tag: String): String = when (tag) {
        "eng", "english" -> "en"
        "jpn", "jap", "japanese" -> "ja"
        "fil", "tgl", "tagalog", "filipino" -> "fil"
        "kor", "korean" -> "ko"
        "chi", "zho", "chs", "cht", "chinese" -> "zh"
        "spa", "spanish" -> "es"
        "fre", "fra", "french" -> "fr"
        "ger", "deu", "german" -> "de"
        "ita", "italian" -> "it"
        "por", "portuguese" -> "pt"
        "ind", "indonesian" -> "id"
        "tha", "thai" -> "th"
        "vie", "vietnamese" -> "vi"
        else -> tag
    }

    private val languageNames: Map<String, String> = buildMap {
        listOf("en", "eng", "english").forEach { put(it, "English") }
        listOf("ja", "jpn", "jap", "japanese").forEach { put(it, "Japanese") }
        listOf("fil", "tl", "tgl", "tagalog", "filipino").forEach { put(it, "Filipino") }
        listOf("ko", "kor", "korean").forEach { put(it, "Korean") }
        listOf("zh", "chi", "zho", "chs", "cht", "chinese").forEach { put(it, "Chinese") }
        listOf("es", "spa", "spanish").forEach { put(it, "Spanish") }
        listOf("fr", "fre", "fra", "french").forEach { put(it, "French") }
        listOf("de", "ger", "deu", "german").forEach { put(it, "German") }
        listOf("it", "ita", "italian").forEach { put(it, "Italian") }
        listOf("pt", "por", "portuguese").forEach { put(it, "Portuguese") }
        listOf("id", "ind", "indonesian").forEach { put(it, "Indonesian") }
        listOf("th", "tha", "thai").forEach { put(it, "Thai") }
        listOf("vi", "vie", "vietnamese").forEach { put(it, "Vietnamese") }
    }

    /** Orders "Episode 2" before "Episode 10", ignoring case. */
    val naturalOrder: Comparator<String> = Comparator { a, b -> naturalCompare(a, b) }

    private fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val startA = i
                val startB = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val numA = a.substring(startA, i).trimStart('0')
                val numB = b.substring(startB, j).trimStart('0')
                if (numA.length != numB.length) return numA.length - numB.length
                val cmp = numA.compareTo(numB)
                if (cmp != 0) return cmp
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (unit <= 1) "${value.toInt()} ${units[unit]}" else String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
    }
}
