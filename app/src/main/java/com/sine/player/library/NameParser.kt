package com.sine.player.library

import com.sine.player.source.MediaFiles
import com.sine.player.subtitles.SubtitleQuery
import java.util.Locale

/** Guesses titles, years, seasons and episodes from file and folder names. Pure for unit tests. */
object NameParser {
    data class Movie(val title: String, val year: Int?)
    data class Episode(val show: String, val showYear: Int?, val season: Int, val episode: Int)

    private val seasonFolder = Regex("""(?i)^(?:season|series|staffel|saison|temporada|s)[ ._-]*(\d{1,3})$""")
    private val specials = Regex("""(?i)^(specials?|extras? season|season 0+)$""")
    private val episodeOnly = Regex("""(?i)(?:\b|_)(?:e|ep|episode)[ ._-]?(\d{1,3})\b""")
    private val leadingNumber = Regex("""^(\d{1,3})(?:[ ._-]|$)""")
    private val skipFolders = setOf("sample", "samples", "extras", "featurettes", "behind the scenes", "trailers", "deleted scenes", "@eadir", ".@__thumb", "subs", "subtitles")

    fun isSkippedFolder(name: String) = name.lowercase(Locale.US) in skipFolders

    fun isSample(fileName: String, size: Long) =
        size in 1 until 300L * 1024 * 1024 && Regex("""(?i)(^|[ ._-])sample([ ._-]|$)""").containsMatchIn(MediaFiles.baseName(fileName))

    fun seasonOf(folder: String): Int? = when {
        specials.matches(folder.trim()) -> 0
        else -> seasonFolder.find(folder.trim())?.groupValues?.get(1)?.toInt()
    }

    /** "Dune Part Two (2024)/Dune.Part.Two.2024.2160p.mkv" → Dune Part Two, 2024. */
    fun movie(fileName: String, parentFolder: String?): Movie {
        val fromFile = SubtitleQuery.from(fileName)
        val fromFolder = parentFolder?.let { SubtitleQuery.from(it) }
        return when {
            fromFile.year != null -> Movie(fromFile.text, fromFile.year)
            fromFolder?.year != null -> Movie(fromFolder.text, fromFolder.year)
            else -> Movie(fromFile.text, null)
        }
    }

    /**
     * [path] is the folders between the show folder and the file, e.g. ["Season 2"]. The show is
     * named after its folder; season and episode come from the file name, the season folder, or a
     * bare episode number.
     */
    fun episode(showFolder: String, path: List<String>, fileName: String): Episode? {
        val show = SubtitleQuery.from(showFolder)
        val parsed = SubtitleQuery.from(fileName)
        val folderSeason = path.asReversed().firstNotNullOfOrNull { seasonOf(it) }
        if (parsed.season != null && parsed.episode != null) return Episode(show.text, show.year, parsed.season, parsed.episode)
        val base = MediaFiles.baseName(fileName)
        val number = episodeOnly.find(base)?.groupValues?.get(1)?.toInt()
            ?: leadingNumber.find(base.trim())?.groupValues?.get(1)?.toInt()
            ?: return null
        return Episode(show.text, show.year, folderSeason ?: 1, number)
    }

    /** Stable key for matching the same title across rescans. */
    fun key(title: String, year: Int?): String =
        title.lowercase(Locale.US).replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim() + (year?.let { "|$it" } ?: "")
}
