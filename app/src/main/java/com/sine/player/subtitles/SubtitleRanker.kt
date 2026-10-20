package com.sine.player.subtitles

import java.util.Locale
import kotlin.math.ln

/** What a release name says about the encode: who made it, from what, at what size. */
data class ReleaseInfo(
    val group: String?,
    val source: String?,
    val resolution: String?,
    val service: String?,
    val season: Int?,
    val episode: Int?,
) {
    companion object {
        private val groupPattern = Regex("""-([A-Za-z0-9]{2,20})(?:\[[^]]*])?$""")
        private val resolutionPattern = Regex("""(?i)\b(2160p|1080p|1080i|720p|576p|480p|4k)\b""")
        private val episodePattern = Regex("""(?i)\bS(\d{1,2})[ ._-]?E(\d{1,3})\b""")
        private val sources = linkedMapOf(
            "remux" to "remux",
            "bluray" to "bluray", "blu-ray" to "bluray", "bdrip" to "bluray", "brrip" to "bluray", "bdremux" to "remux",
            "web-dl" to "web", "webdl" to "web", "webrip" to "web", "web" to "web",
            "hdtv" to "hdtv", "dvdrip" to "dvd", "dvd" to "dvd", "hdrip" to "hdrip",
        )
        private val services = mapOf(
            "amzn" to "amazon", "nf" to "netflix", "dsnp" to "disney", "hmax" to "max", "max" to "max",
            "atvp" to "apple", "pcok" to "peacock", "hulu" to "hulu", "pmtp" to "paramount", "cr" to "crunchyroll",
        )

        fun parse(name: String): ReleaseInfo {
            val base = name.substringBeforeLast('.').takeIf { name.substringAfterLast('.').length in 2..4 } ?: name
            val tokens = base.lowercase(Locale.US).split('.', ' ', '_', '[', ']', '(', ')').filter { it.isNotBlank() }
            val joined = tokens.joinToString(".")
            val source = sources.entries.firstOrNull { (key, _) -> key in tokens || (key.contains('-') && joined.contains(key)) }?.value
            val episode = episodePattern.find(base)
            return ReleaseInfo(
                group = groupPattern.find(base.trim())?.groupValues?.get(1)?.lowercase(Locale.US)?.takeIf { it !in noise },
                source = source,
                resolution = resolutionPattern.find(base)?.value?.lowercase(Locale.US)?.replace("4k", "2160p"),
                service = tokens.firstNotNullOfOrNull { services[it] },
                season = episode?.groupValues?.get(1)?.toInt(),
                episode = episode?.groupValues?.get(2)?.toInt(),
            )
        }

        /** Words that end a release name but aren't groups. */
        private val noise = setOf("dl", "rip", "web", "hd", "sdh", "eng", "en", "x264", "x265", "hevc")
    }
}

/** A subtitle from any site, before it's downloaded. */
data class SubtitleCandidate(
    val provider: String,
    val id: String,
    val release: String,
    val language: String,
    val hearingImpaired: Boolean = false,
    val hashMatch: Boolean = false,
    val downloads: Int = 0,
    val season: Int? = null,
    val episode: Int? = null,
    /** Provider-specific download handle (file id, path, URL). */
    val handle: String = id,
)

data class RankedSubtitle(val candidate: SubtitleCandidate, val score: Int, val match: String)

/**
 * Orders subtitles by how likely they are to be in sync with *this* file. An exact hash match is
 * certain; the same release group and source is nearly always right; the same title is a guess.
 */
object SubtitleRanker {
    const val EXACT = 100
    const val SAME_RELEASE = 60

    fun rank(
        candidates: List<SubtitleCandidate>,
        fileName: String,
        preferredLanguages: List<String>,
        avoidHearingImpaired: Boolean,
    ): List<RankedSubtitle> {
        val file = ReleaseInfo.parse(fileName)
        return candidates
            .map { score(it, file, preferredLanguages, avoidHearingImpaired) }
            .filter { it.score > -50 }
            .sortedWith(compareByDescending<RankedSubtitle> { it.score }.thenByDescending { it.candidate.downloads })
            .distinctBy { "${it.candidate.provider}:${it.candidate.id}" }
    }

    fun score(c: SubtitleCandidate, file: ReleaseInfo, languages: List<String>, avoidHearingImpaired: Boolean): RankedSubtitle {
        val sub = ReleaseInfo.parse(c.release)
        var score = 0
        val reasons = mutableListOf<String>()

        // Wrong episode: almost certainly useless.
        val season = c.season ?: sub.season
        val episode = c.episode ?: sub.episode
        if (file.episode != null && episode != null && (episode != file.episode || (season != null && file.season != null && season != file.season))) {
            return RankedSubtitle(c, -100, "Different episode")
        }

        if (c.hashMatch) {
            score += EXACT
            reasons += "Exact file match"
        }
        val sameGroup = file.group != null && file.group == sub.group
        val sameSource = file.source != null && file.source == sub.source
        if (sameGroup) score += 40
        if (sameSource) score += 20
        if (file.service != null && file.service == sub.service) score += 10
        if (file.resolution != null && file.resolution == sub.resolution) score += 5
        if (!c.hashMatch) {
            reasons += when {
                sameGroup && sameSource -> "Same release (${file.group})"
                sameGroup -> "Same group (${file.group})"
                sameSource -> "Same source (${file.source})"
                else -> "Same title"
            }
        }

        val langIndex = languages.indexOfFirst { Languages.same(it, c.language) }
        score += when (langIndex) {
            0 -> 15
            -1 -> -20
            else -> 5
        }
        if (avoidHearingImpaired && c.hearingImpaired) score -= 8
        if (c.downloads > 0) score += (ln(c.downloads.toDouble()) * 1.2).toInt().coerceAtMost(12)
        return RankedSubtitle(c, score, reasons.first())
    }

    /** Good enough to download without asking: the exact file or the same release. */
    fun confident(best: RankedSubtitle?): Boolean = best != null && best.score >= SAME_RELEASE
}

/** Compares language codes across ISO 639-1/-2 and the odd ones subtitle sites use. */
object Languages {
    private val aliases = mapOf(
        "tl" to "fil", "tgl" to "fil", "fil" to "fil",
        "zh-cn" to "zho", "zh-tw" to "zho", "chi" to "zho", "zh" to "zho",
        "pt-br" to "por", "pt-pt" to "por", "pb" to "por",
        "jp" to "jpn", "gr" to "ell", "cz" to "ces",
    )

    fun normalize(code: String?): String? {
        val c = code?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() && it != "und" } ?: return null
        aliases[c]?.let { return it }
        return runCatching { Locale.forLanguageTag(c).isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() } ?: c
    }

    fun same(a: String?, b: String?): Boolean {
        val x = normalize(a) ?: return false
        return x == normalize(b)
    }
}
