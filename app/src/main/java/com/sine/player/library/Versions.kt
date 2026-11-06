package com.sine.player.library

import com.sine.player.source.MediaFiles

/** What a file name says about one version of a movie. */
data class VersionInfo(val resolution: Int?, val hdr: String?, val remux: Boolean, val edition: String?, val size: Long)

/** Several files of the same movie (4K remux, 1080p, extended cut…): label them and pick one. */
object Versions {
    private val resolutions = listOf(
        Regex("""(?i)\b(2160p|4k|uhd)\b""") to 2160,
        Regex("""(?i)\b1080[pi]\b""") to 1080,
        Regex("""(?i)\b720p\b""") to 720,
        Regex("""(?i)\b(576p|480p|dvd(rip)?)\b""") to 480,
    )
    private val editions = listOf("Extended", "Director's Cut", "Directors Cut", "Theatrical", "Unrated", "IMAX", "Final Cut", "Ultimate")

    fun info(fileName: String, size: Long): VersionInfo {
        val name = fileName.replace('.', ' ').replace('_', ' ')
        val resolution = resolutions.firstOrNull { it.first.containsMatchIn(name) }?.second
        val hdr = when {
            Regex("""(?i)\b(dv|dovi|dolby ?vision)\b""").containsMatchIn(name) -> "Dolby Vision"
            Regex("""(?i)\bhdr10(\+|plus)""").containsMatchIn(name) -> "HDR10+"
            Regex("""(?i)\bhdr(10)?\b""").containsMatchIn(name) -> "HDR10"
            else -> null
        }
        val edition = editions.firstOrNull { name.contains(it, ignoreCase = true) }?.replace("Directors Cut", "Director's Cut")
        return VersionInfo(resolution, hdr, Regex("""(?i)\bremux\b""").containsMatchIn(name), edition, size)
    }

    fun label(v: VersionInfo): String = listOfNotNull(
        v.resolution?.let { if (it == 480) "SD" else "${it}p" },
        v.hdr,
        "Remux".takeIf { v.remux },
        v.edition,
        MediaFiles.formatSize(v.size).ifEmpty { null },
    ).joinToString(" · ").ifEmpty { "Version" }

    /** Higher is better: resolution, then HDR kind, then remux, then size. */
    fun score(v: VersionInfo): Long {
        val hdr = when (v.hdr) {
            "Dolby Vision" -> 3
            "HDR10+" -> 2
            "HDR10" -> 1
            else -> 0
        }
        return (v.resolution ?: 0) * 1_000_000_000L + hdr * 100_000_000L + (if (v.remux) 50_000_000L else 0) + v.size / 1_000_000_000
    }

    /**
     * Where [chosen] should resume: its own progress, else the latest progress on another version
     * of the same cut (a 1080p copy picks up where the 4K remux stopped; an extended cut doesn't).
     */
    fun resumeFrom(chosen: Int, versions: List<VersionInfo>, records: List<com.sine.player.data.PlaybackRecord?>): com.sine.player.data.PlaybackRecord? {
        records.getOrNull(chosen)?.let { if (it.resumable || it.watched) return it.takeIf { r -> r.resumable } }
        val edition = versions.getOrNull(chosen)?.edition
        return records.indices
            .filter { it != chosen && versions.getOrNull(it)?.edition == edition }
            .mapNotNull { records[it]?.takeIf { r -> r.resumable } }
            .maxByOrNull { it.updatedAt }
    }

    /** The version to start with: the one being watched, else the best. */
    fun pick(versions: List<VersionInfo>, inProgress: Int): Int =
        if (inProgress in versions.indices) inProgress else versions.indices.maxByOrNull { score(versions[it]) } ?: 0
}
