package com.sine.player.library

/** What a Kodi `.nfo` file says about a movie or show. */
data class NfoInfo(
    val title: String?,
    val year: Int?,
    val plot: String?,
    val rating: Double?,
    val genres: List<String>,
    val certification: String?,
    val tmdbId: Int?,
)

/**
 * Reads Kodi `movie.nfo` / `<name>.nfo` / `tvshow.nfo` files (XML, or a bare TMDB/IMDb link), and
 * lays them over TMDB details: what the user curated wins.
 */
object Nfo {
    fun parse(text: String): NfoInfo? {
        val xml = text.trim()
        if (!xml.contains('<')) {
            // A "link" nfo: just a URL pointing at the title.
            val id = Regex("""themoviedb\.org/(?:movie|tv)/(\d+)""").find(xml)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            return NfoInfo(null, null, null, null, emptyList(), null, id)
        }
        val title = tag(xml, "title")
        val year = tag(xml, "year")?.toIntOrNull()
            ?: (tag(xml, "premiered") ?: tag(xml, "aired") ?: tag(xml, "releasedate"))?.take(4)?.toIntOrNull()
        val plot = tag(xml, "plot") ?: tag(xml, "outline")
        // Kodi 18+: <ratings><rating name="themoviedb" default="true"><value>7.9</value>…; older: <rating>7.9</rating>.
        val ratingsBlock = Regex("""<ratings>(.*?)</ratings>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
        val rating = ratingsBlock?.let { block ->
            val ratings = Regex("""<rating([^>]*)>(.*?)</rating>""", RegexOption.DOT_MATCHES_ALL).findAll(block).toList()
            val chosen = ratings.firstOrNull { it.groupValues[1].contains("default=\"true\"") } ?: ratings.firstOrNull()
            chosen?.let { tag(it.groupValues[2], "value")?.toDoubleOrNull() }
        } ?: Regex("""<rating>([\d.]+)</rating>""").find(xml)?.groupValues?.get(1)?.toDoubleOrNull()
        val genres = Regex("""<genre>(.*?)</genre>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .flatMap { it.groupValues[1].split('/', ',') }.map { unescape(it).trim() }.filter { it.isNotEmpty() }.distinct().toList()
        val certification = tag(xml, "mpaa")?.let(::certification)
        val tmdbId = tag(xml, "tmdbid")?.toIntOrNull()
            ?: Regex("""<uniqueid[^>]*type="tmdb"[^>]*>(\d+)</uniqueid>""").find(xml)?.groupValues?.get(1)?.toIntOrNull()
        return NfoInfo(title, year, plot, rating?.takeIf { it > 0 }, genres, certification, tmdbId)
    }

    /** "Rated PG-13", "US:PG-13 / GB:12A", "PG-13" → "PG-13" (the first given). */
    fun certification(raw: String): String? = raw.split('/').firstOrNull()?.trim()
        ?.removePrefix("Rated ")?.removePrefix("rated ")
        ?.substringAfter(':')?.trim()?.ifEmpty { null }

    /** [existing] (TMDB or nothing) with the nfo's fields, and local fanart as the backdrop. */
    fun merge(existing: Meta?, nfo: NfoInfo?, fanart: String?): Meta {
        val base = existing ?: Meta(
            tmdbId = null, title = null, overview = null, poster = null, backdrop = null, rating = null, genres = emptyList(), year = null,
            // No TMDB details yet: stays "stale" so a TMDB key added later still fills posters and episodes.
            fetchedAt = 0,
        )
        if (nfo == null) return if (fanart != null) base.copy(backdrop = fanart) else base
        return base.copy(
            tmdbId = base.tmdbId ?: nfo.tmdbId,
            title = nfo.title ?: base.title,
            overview = nfo.plot ?: base.overview,
            rating = nfo.rating ?: base.rating,
            genres = nfo.genres.ifEmpty { base.genres },
            year = nfo.year ?: base.year,
            certification = nfo.certification ?: base.certification,
            backdrop = fanart ?: base.backdrop,
        )
    }

    private fun tag(xml: String, name: String): String? =
        Regex("""<$name(?:\s[^>]*)?>(.*?)</$name>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
            ?.let { unescape(it.removePrefix("<![CDATA[").removeSuffix("]]>")).trim() }?.ifEmpty { null }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** File names Kodi uses for a movie's nfo and fanart next to [videoName]. */
    fun movieNfoNames(videoName: String): List<String> = listOf(videoName.substringBeforeLast('.') + ".nfo", "movie.nfo")

    fun fanartNames(videoName: String?): List<String> =
        listOfNotNull(videoName?.substringBeforeLast('.')?.let { "$it-fanart" }, "fanart", "backdrop", "background")
}
